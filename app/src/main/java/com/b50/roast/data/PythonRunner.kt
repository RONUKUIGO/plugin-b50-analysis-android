package com.b50.roast.data

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * App 内嵌 Python 运行时：解压 + 以子进程方式跑原始 render.py。
 *
 * 为什么要绕这一圈（Android 10+ 的硬限制）：
 *   /data/data/<pkg>/files 下的文件**不允许 exec**（SELinux execute 拒绝），
 *   所以 Python 主程序放在 APK 的 native lib 目录（jniLibs → /data/app/<pkg>/lib/arm64/），
 *   那个目录天生可执行。其余 .so 与 site-packages 解压到 filesDir，
 *   运行时用 LD_LIBRARY_PATH / PYTHONHOME 指过去即可（dlopen 不受 exec 限制）。
 */
class PythonRunner(private val ctx: Context) {

    companion object {
        private const val TAG = "B50Py"
        /** 与 ResourceInstaller.PACK_VERSION 保持同步 */
        const val PY_VERSION = "6"
        private const val NATIVE_BIN = "libpy_home.so"
    }

    data class Result(val ok: Boolean, val output: String, val image: File?)

    val pyHome: File get() = File(ctx.filesDir, "py/usr")
    val pyApp: File get() = File(ctx.filesDir, "py/app")

    fun isInstalled(): Boolean {
        val v = File(ctx.filesDir, "py/version.txt")
        return v.exists() && v.readText().trim() == PY_VERSION &&
            File(pyApp, "render_worker.py").exists() &&
            File(pyHome, "lib/libpython3.14.so").exists()
    }

    /** Python 可执行文件：优先 native lib 目录，其次 filesDir（老系统 / 调试回退） */
    fun pythonBinary(): File? {
        val native = File(ctx.applicationInfo.nativeLibraryDir, NATIVE_BIN)
        if (native.exists()) return native
        val inFiles = File(pyHome, "bin/python3.14")
        if (inFiles.exists()) return inFiles
        return null
    }

    // ---------------------------------------------------------------- 安装

    /** 首启解压 Python 运行时（20 MB tar.gz）+ py 代码（32 KB zip）。阻塞，放 IO 线程。 */
    fun install(onProgress: (Int, String) -> Unit = { _, _ -> }) {
        val t0 = System.currentTimeMillis()
        val base = File(ctx.filesDir, "py").apply { mkdirs() }

        onProgress(5, "解压 Python 代码…")
        val appDir = File(base, "app")
        appDir.deleteRecursively(); appDir.mkdirs()
        ctx.assets.open("pycode.zip").use { raw ->
            ZipInputStream(raw.buffered()).use { zis -> unzip(zis, appDir) }
        }

        onProgress(20, "解压 Python 运行时（约 63 MB）…")
        val usrDir = File(base, "usr")
        usrDir.deleteRecursively()
        // 用 zip 而不是 tar：Android 自带 ZipInputStream，tar 需要自己写解析（踩过坑）
        ctx.assets.open("py_runtime.zip").use { raw ->
            java.util.zip.ZipInputStream(raw.buffered(128 * 1024)).use { zis -> unzip(zis, base) }
        }

        File(base, "version.txt").writeText(PY_VERSION)
        Log.w(
            TAG, "Python 运行时就绪：${base.absolutePath} " +
                "耗时 ${System.currentTimeMillis() - t0} ms, 大小 ${dirSize(base) / 1048576} MB, " +
                "可执行=${pythonBinary()?.absolutePath ?: "无"}"
        )
        onProgress(100, "Python 就绪")
    }

    private fun dirSize(f: File): Long =
        if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    private fun unzip(zis: ZipInputStream, dest: File) {
        while (true) {
            val e = zis.nextEntry ?: break
            val out = File(dest, e.name)
            if (e.isDirectory) { out.mkdirs(); zis.closeEntry(); continue }
            out.parentFile?.mkdirs()
            out.outputStream().buffered().use { os -> zis.copyTo(os, 64 * 1024) }
            zis.closeEntry()
        }
    }

    /** 极简 POSIX tar 解包（只处理普通文件 + 目录，符号链接跳过） */
    private fun untar(input: InputStream, destRoot: File) {
        val hdr = ByteArray(512)
        var files = 0
        while (true) {
            var read = 0
            while (read < 512) {
                val n = input.read(hdr, read, 512 - read)
                if (n < 0) return
                read += n
            }
            if (hdr.all { it.toInt() == 0 }) break
            val name = String(hdr, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
            if (name.isEmpty()) break
            val sizeStr = String(hdr, 124, 12, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val type = hdr[156].toInt().toChar()
            val out = File(destRoot, name)
            when (type) {
                '5' -> out.mkdirs()
                '2' -> Unit                        // symlink：不需要（我们用 nativeLib 里的二进制）
                else -> {
                    out.parentFile?.mkdirs()
                    var remain = size
                    out.outputStream().buffered().use { os ->
                        val buf = ByteArray(64 * 1024)
                        while (remain > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                            if (n < 0) break
                            os.write(buf, 0, n)
                            remain -= n
                        }
                    }
                    if (name.contains("/bin/")) out.setExecutable(true, false)
                    files++
                }
            }
            val pad = ((512 - (size % 512)) % 512).toInt()
            var p = pad
            val skip = ByteArray(512)
            while (p > 0) {
                val n = input.read(skip, 0, minOf(p, 512))
                if (n < 0) break
                p -= n
            }
        }
        Log.w(TAG, "tar 解包完成：$files 个文件 → ${destRoot.absolutePath}")
    }


    /**
     * 头像：render.py 从 assets/avatars/<qq>.png 读，所以渲染前要确保它在。
     * 失败不阻断（只是图上没有头像）。
     */
    fun ensureAvatar(assets: File, qq: String) {
        if (qq.isBlank()) return
        val dir = File(assets, "avatars").apply { mkdirs() }
        val f = File(dir, "$qq.png")
        if (f.exists() && f.length() > 1024) return
        try {
            val url = java.net.URL("https://q.qlogo.cn/headimg_dl?dst_uin=$qq&spec=640")
            val c = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
            }
            c.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            c.disconnect()
            Log.w(TAG, "头像已下载 ${f.absolutePath} (${f.length()} B)")
        } catch (e: Exception) {
            Log.w(TAG, "头像下载失败 qq=$qq: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 跑 render_worker.py。阻塞（放 IO 线程），超时返回失败。
     *
     * @param recordsJson 水鱼 /player/records 原始响应
     * @param musicJson   曲库 music_data.json
     * @param assets      资源目录（fonts/ui/covers）
     * @param out         输出 PNG
     * @param versions    当前版本（| 分隔，Kotlin 已校准）
     */
    fun render(
        recordsJson: File,
        musicJson: File,
        assets: File,
        out: File,
        width: Int,
        qq: String,
        versions: String,
        analysis: String,
        timeoutSec: Long = 180,
    ): Result {
        val bin = pythonBinary() ?: return Result(false, "找不到 Python 运行时", null)
        val worker = File(pyApp, "render_worker.py")
        if (!worker.exists()) return Result(false, "缺少 render_worker.py", null)

        val cmd = listOf(
            bin.absolutePath, worker.absolutePath,
            recordsJson.absolutePath, musicJson.absolutePath, assets.absolutePath, out.absolutePath,
            width.toString(), qq, versions, analysis,
        )
        Log.w(TAG, "启动 Python 渲染：$cmd")

        val pb = ProcessBuilder(cmd).apply {
            directory(pyApp)
            redirectErrorStream(true)
            environment().putAll(baseEnv(assets))
        }

        val t0 = System.currentTimeMillis()
        return try {
            val p = pb.start()
            val text = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); return Result(false, "Python 渲染超时（${timeoutSec}s）", null) }
            val code = p.exitValue()
            Log.w(TAG, "Python 退出码=$code 耗时=${System.currentTimeMillis() - t0}ms\n$text")
            if (code == 0 && out.exists() && out.length() > 1024) Result(true, text, out)
            else Result(false, text.ifBlank { "Python 退出码 $code" }, null)
        } catch (e: Exception) {
            Log.e(TAG, "Python 启动失败", e)
            Result(false, "${e.javaClass.simpleName}: ${e.message}", null)
        }
    }

    /**
     * 只生成锐评提示词：建完 context 就把 system/user 提示词写进 outJson 然后退出，
     * 不发 HTTP、不渲染。Kotlin 拿去调 LLM，拿到正文后再调 [render] 正式出图。
     *
     * 为什么拆成两次跑：HTTP 走 Kotlin 侧（系统 CA、超时、错误提示都更可控），
     * Python 只负责「拼提示词」和「画图」，各干各擅长的事。
     */
    fun makePrompt(
        recordsJson: File,
        musicJson: File,
        assets: File,
        outJson: File,
        width: Int,
        qq: String,
        versions: String,
        tone: String,
        angle: String,
        words: String,
        timeoutSec: Long = 120,
    ): Boolean {
        val bin = pythonBinary() ?: return false
        val worker = File(pyApp, "render_worker.py")
        if (!worker.exists()) return false

        val dummyOut = File(ctx.cacheDir, "prompt_mode_placeholder.png").absolutePath
        val cmd = listOf(
            bin.absolutePath, worker.absolutePath,
            recordsJson.absolutePath, musicJson.absolutePath, assets.absolutePath, dummyOut,
            width.toString(), qq, versions, "",
        )
        Log.w(TAG, "生成锐评提示词 → " + outJson.absolutePath)

        val pb = ProcessBuilder(cmd).apply {
            directory(pyApp)
            redirectErrorStream(true)
            environment().apply {
                putAll(baseEnv(assets))
                put("B50_PROMPT_OUT", outJson.absolutePath)
                put("B50_TONE", tone)
                put("B50_ANGLE", angle)
                put("B50_WORDS", words)
            }
        }
        val t0 = System.currentTimeMillis()
        return try {
            val p = pb.start()
            val text = p.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Log.e(TAG, "提示词生成超时")
                return false
            }
            val code = p.exitValue()
            val ok = code == 0 && outJson.exists() && outJson.length() > 0
            Log.w(TAG, "提示词 exit=$code 耗时=${System.currentTimeMillis() - t0}ms ok=$ok\n" + text.takeLast(400))
            ok
        } catch (e: Exception) {
            Log.e(TAG, "提示词生成失败", e)
            false
        }
    }

    /** 所有 Python 调用共用的环境变量 */
    private fun baseEnv(assets: File): Map<String, String> = mapOf(
        "PYTHONHOME" to pyHome.absolutePath,
        "PYTHONPATH" to pyApp.absolutePath,
        "LD_LIBRARY_PATH" to (File(pyHome, "lib").absolutePath + ":" + ctx.applicationInfo.nativeLibraryDir),
        "TMPDIR" to ctx.cacheDir.absolutePath,
        "HOME" to pyHome.absolutePath,
        "B50_ASSETS_PATH" to assets.absolutePath,
        "LANG" to "C.UTF-8",
        "PYTHONUTF8" to "1",
        "PYTHONDONTWRITEBYTECODE" to "1",
    )
}
