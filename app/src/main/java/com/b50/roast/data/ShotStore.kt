package com.b50.roast.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一张已生成的成绩图（App 私有目录 filesDir/shots/b50_<毫秒>.png）。
 * 时间优先从文件名里的毫秒数解析，解析不出来就退回 lastModified。
 */
data class ShotItem(
    val file: File,
    val timeMillis: Long,
    val sizeBytes: Long,
) {
    val name: String get() = file.name

    val timeText: String
        get() = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timeMillis))

    val sizeText: String
        get() = if (sizeBytes >= 1_048_576) {
            String.format(Locale.CHINA, "%.1f MB", sizeBytes / 1_048_576f)
        } else {
            (sizeBytes / 1024).toString() + " KB"
        }
}

/**
 * 本地出图仓库：扫描 / 缩略图（带 LruCache）/ 删除 / 存相册 / 分享。
 *
 * 设计要点：
 *   · 缩略图用 BitmapFactory.inSampleSize 二段解码 + RGB_565，三列格子每张约 0.4 MB，
 *     比直接解原图（1200×2104 ARGB = 9.6 MB/张）省 20 倍，滚动不炸内存。
 *   · 缓存上限取堆的 1/8，滚出视野的图被回收，回滚时重新解码，可接受。
 *   · 存相册走 MediaStore（API 29+ 用 RELATIVE_PATH + IS_PENDING 原子写入，不需要权限）；
 *     API < 29 退化成直接写 Pictures/B50，需要 WRITE_EXTERNAL_STORAGE（Manifest 里 maxSdkVersion=28）。
 */
class ShotStore(private val ctx: Context) {

    /** filesDir/shots，不存在就建 */
    val dir: File
        get() = File(ctx.filesDir, "shots").apply { if (!exists()) mkdirs() }

    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024L) / 8L).toInt().coerceAtLeast(4096)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    // ---------------------------------------------------------------- 列表

    /** 按时间倒序返回所有成绩图（文件名 b50_*.png 才算） */
    fun list(): List<ShotItem> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("b50_") } ?: return emptyList()
        return files.map { f ->
            val ts = f.name.substringAfter("b50_", "").substringBefore('.').toLongOrNull()
                ?: f.lastModified()
            ShotItem(f, ts, f.length())
        }.sortedByDescending { it.timeMillis }
    }

    fun count(): Int = list().size

    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    fun delete(item: ShotItem): Boolean {
        cache.remove(item.name + "@420")
        cache.remove(item.name + "@full")
        return runCatching { item.file.delete() }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 解码

    /**
     * 缩略图。reqWidthPx 是目标解码宽度（像素），比屏幕格子略大一点就行。
     * 返回 null 表示解码失败（文件损坏 / 被删）。
     */
    fun thumb(item: ShotItem, reqWidthPx: Int = 420): Bitmap? {
        val key = item.name + "@" + reqWidthPx
        cache.get(key)?.let { return it }

        val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.file.absolutePath, bound)
        if (bound.outWidth <= 0) return null

        var sample = 1
        while (bound.outWidth / (sample * 2) >= reqWidthPx) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565   // 缩略图不需要 alpha，省一半内存
        }
        val bmp = runCatching { BitmapFactory.decodeFile(item.file.absolutePath, opts) }
            .getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 大图预览（按屏宽采样，通常就是原图 1200 宽）。 */
    fun full(item: ShotItem, maxWidthPx: Int = 1200): Bitmap? {
        val key = item.name + "@full"
        cache.get(key)?.let { return it }

        val bound = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(item.file.absolutePath, bound)
        if (bound.outWidth <= 0) return null

        var sample = 1
        while (bound.outWidth / (sample * 2) >= maxWidthPx) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = runCatching { BitmapFactory.decodeFile(item.file.absolutePath, opts) }
            .getOrNull() ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun clearCache() = cache.evictAll()

    // ---------------------------------------------------------------- 存相册

    /**
     * 把图写进系统相册，返回给人看的提示文案（成功/失败都在里面）。
     * 整个过程在 IO 线程跑，可以放心从主线程 await。
     */
    suspend fun saveToGallery(item: ShotItem): String = withContext(Dispatchers.IO) {
        val mime = mimeOf(item.file)
        val subDir = "B50"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, item.name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + subDir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = runCatching {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull()

            if (uri == null) return@withContext "写相册失败：系统拒绝了 MediaStore 插入"

            val wrote = runCatching {
                resolver.openOutputStream(uri)?.use { os ->
                    item.file.inputStream().use { it.copyTo(os) }
                } ?: error("无法打开输出流")
            }
            if (wrote.isFailure) {
                runCatching { resolver.delete(uri, null, null) }   // 回滚，别留半张坏图
                return@withContext "写相册失败：" + (wrote.exceptionOrNull()?.message ?: "未知错误")
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
            "已保存到相册 Pictures/$subDir/${item.name}"
        } else {
            @Suppress("DEPRECATION")
            val outDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                subDir
            )
            if (!outDir.exists() && !outDir.mkdirs()) {
                return@withContext "无法创建目录 " + outDir.absolutePath + "（需要存储权限）"
            }
            val dest = File(outDir, item.name)
            val r = runCatching { item.file.copyTo(dest, overwrite = true) }
            if (r.isFailure) {
                "保存失败：" + (r.exceptionOrNull()?.message ?: "未知错误")
            } else {
                "已保存到相册 Pictures/$subDir/${item.name}"
            }
        }
    }

    // ---------------------------------------------------------------- 分享

    /** content:// Uri，走 FileProvider（App 私有目录不能被别的 App 直接读） */
    fun shareUri(item: ShotItem): Uri? = runCatching {
        FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", item.file)
    }.getOrNull()

    /** 系统分享选择器 Intent；拿不到 Uri 返回 null */
    fun shareIntent(item: ShotItem): Intent? {
        val uri = shareUri(item) ?: return null
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(item.file)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, item.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "分享成绩图").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun mimeOf(f: File): String = when (f.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}
