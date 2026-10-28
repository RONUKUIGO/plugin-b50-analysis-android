package com.b50.roast.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 首启资源安装器：把 assets 里的 pack.zip（约 21 MB / 1600+ 条目）解压到 filesDir/res/。
 *
 * 为什么这么做：曲绘 1569 张原始体积 357 MB，压缩后 21 MB，
 * 塞进 assets 一次性分发，首次启动解压，之后完全离线可用（出图不需要网络）。
 *
 * 设计要点：
 *  · 版本化：res/version.txt != PACK_VERSION 才重新解压（换素材发新版即可）
 *  · 原子性：先解到 res.tmp，全部成功再 rename 成 res，中途被杀不留半截资源
 *  · 不多占磁盘：直接从 AssetManager 流式读 zip，不先复制一份到 filesDir
 *  · 空间预检：不够 60 MB 立刻报错，而不是解压到一半失败
 */
class ResourceInstaller(private val ctx: Context) {

    companion object {
        /** ★ 换素材包时改这里，App 升级后会自动重新解压 */
        // ★ 换素材包时改这里：v5 = ui/icons/*.png + cover/*.png + ui/fonts/（与桌面版同结构）
        /**
         * 资源包版本 = **机台内部版本代号**（cn:NNNNN）。上游每次更新素材就换一个代号，
         * 素材包 URL 里的端口号就是这个数字（tools/fetch_assets.py 会从 URL 自动解析）。
         * 变更判定：res/version.txt != PACK_VERSION 就重新解压。
         */
        const val PACK_VERSION = "34274"

        private const val ZIP = "pack.zip"
        private const val NEED_BYTES = 60L * 1024 * 1024
    }

    data class Progress(val done: Int, val total: Int) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total)
    }

    fun root(): File = File(ctx.filesDir, "res")

    fun isInstalled(): Boolean {
        val v = File(root(), "version.txt")
        return v.exists() && v.readText().trim() == PACK_VERSION
    }

    fun installedVersion(): String =
        File(root(), "version.txt").takeIf { it.exists() }?.readText()?.trim() ?: ""

    fun freeBytes(): Long = ctx.filesDir.usableSpace

    /** 解压后的总占用（给设置页显示） */
    fun usedBytes(): Long {
        val r = root()
        if (!r.exists()) return 0
        return r.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 确保资源已解压（幂等）。必须在协程里调用，内部切 IO。
     * @throws IllegalStateException 空间不足 / 资源包缺失
     */
    suspend fun ensureInstalled(onProgress: (Progress) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isInstalled()) {
            onProgress(Progress(1, 1))
            return@withContext
        }
        if (freeBytes() < NEED_BYTES) {
            throw IllegalStateException(
                "存储空间不足：需要约 ${NEED_BYTES / 1048576} MB，当前只剩 ${freeBytes() / 1048576} MB"
            )
        }

        // ① 先数一遍条目，让进度条准确
        val total = ctx.assets.open(ZIP).use { input ->
            ZipInputStream(input.buffered(64 * 1024)).use { zis ->
                var n = 0
                while (true) {
                    zis.nextEntry ?: break
                    n++
                    zis.closeEntry()
                }
                n
            }
        }
        if (total <= 0) throw IllegalStateException("资源包损坏（0 个条目），请重新安装 App")

        // ② 解压到 res.tmp
        val target = root()
        val tmp = File(ctx.filesDir, "res.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) throw IllegalStateException("无法创建目录 ${tmp.absolutePath}")

        val t0 = System.currentTimeMillis()
        Log.i("B50Assets", "开始解压 $ZIP：共 $total 个条目，可用空间 ${freeBytes() / 1048576} MB")
        var done = 0
        ctx.assets.open(ZIP).use { input ->
            ZipInputStream(input.buffered(64 * 1024)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) { zis.closeEntry(); continue }
                    val out = File(tmp, e.name)
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered(32 * 1024).use { os -> zis.copyTo(os, 32 * 1024) }
                    zis.closeEntry()
                    done++
                    if (done % 32 == 0 || done == total) onProgress(Progress(done, total))
                }
            }
        }
        Log.i("B50Assets", "解压完成：$done 个文件，耗时 ${System.currentTimeMillis() - t0} ms")
        File(tmp, "version.txt").writeText(PACK_VERSION)

        // ③ 原子替换
        target.deleteRecursively()
        if (!tmp.renameTo(target)) {
            tmp.copyRecursively(target, overwrite = true)
            tmp.deleteRecursively()
        }
        onProgress(Progress(total, total))
        Log.i("B50Assets", "资源就绪：v$PACK_VERSION → ${target.absolutePath}")
    }

    /** 清掉已解压资源（下次启动会重解） */
    fun clear() {
        root().deleteRecursively()
    }
}
