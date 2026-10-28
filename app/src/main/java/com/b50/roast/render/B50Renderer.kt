package com.b50.roast.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.b50.roast.data.Res
import com.b50.roast.data.RecordsNormalizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * B50 出图（原生 Canvas 版，对应 Python 的 vendor/mai/render.py）。
 *
 * 坐标系与 Python 版一致：内部按 1800 宽排版，最后等比缩到 900/1200/1500。
 * 版式：顶部信息卡（头像/昵称/Rating/数字图/B35+B15 RA）→ B35 35 张 → B15 15 张 →
 *      锐评正文 → 页脚。每张卡：曲绘 + 难度色边框 + 难度图标 + 标题 + 达成率 + ds/RA + 评级 + FC徽章。
 *
 * 与 Python 版的差异（有意为之）：
 *  · 不画「亮点/普通点/推分」等分析分区卡片 —— 那依赖 context_builder 的 7 套算法与
 *    peer_stats/chart_summary 统计，属于下一阶段；当前先出标准 B50 榜 + 锐评正文。
 *  · 字体缺字（GB2312 子集以外的生僻字/日文汉字）自动回落系统字体，靠 Paint.hasGlyph 逐字判断。
 */
class B50Renderer(private val ctx: Context, private val res: Res) {

    companion object {
        private const val TAG = "B50Render"
        const val CANVAS_W = 1800
        const val HEADER_H = 450
        private const val CARD_W = 820f
        private const val CARD_H = 210f
        private const val CARD_GAP = 15f
        private const val SECTION_TITLE_H = 38f
    }

    /** 渲染输入 */
    data class Input(
        val b50: RecordsNormalizer.B50,
        val qq: String,               // 取头像用；空则跳过头像
        val nickname: String,
        val rating: Int,
        val b35Ra: Int,
        val b15Ra: Int,
        val bodyText: String,         // 锐评正文（可为空，空则不出正文区）
        val analysisTitle: String = "锐评",
        val outWidth: Int = 1200,
    )

    // ---------------------------------------------------------------- 字体

    private val cnType: Typeface = runCatching { Typeface.createFromFile(res.fontCn()) }
        .getOrElse { Typeface.DEFAULT }
    private val enType: Typeface = runCatching { Typeface.createFromFile(res.fontEn()) }
        .getOrElse { Typeface.DEFAULT_BOLD }
    private val cnBold: Typeface = runCatching { Typeface.create(cnType, Typeface.BOLD) }
        .getOrElse { Typeface.DEFAULT_BOLD }
    private val enBold: Typeface = runCatching { Typeface.create(enType, Typeface.BOLD) }
        .getOrElse { Typeface.DEFAULT_BOLD }

    private fun paint(size: Float, color: Int, en: Boolean = false, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = when {
                en && bold -> enBold
                en -> enType
                bold -> cnBold
                else -> cnType
            }
        }

    /** 逐字判断：字体没有这个字就切系统字体画（避免豆腐块） */
    private fun drawText(
        c: Canvas, text: String, x: Float, y: Float,
        size: Float, color: Int, en: Boolean = false, bold: Boolean = false,
    ): Float {
        if (text.isEmpty()) return x
        val main = paint(size, color, en, bold)
        // 全都有字 → 一次画完（99% 情况）
        if (text.all { main.hasGlyph(it.toString()) }) {
            c.drawText(text, x, y, main)
            return x + main.measureText(text)
        }
        val sys = Paint(main).apply { typeface = Typeface.DEFAULT }
        var cx = x
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                c.drawText(buf.toString(), cx, y, main)
                cx += main.measureText(buf.toString())
                buf.setLength(0)
            }
        }
        for (ch in text) {
            if (main.hasGlyph(ch.toString())) {
                buf.append(ch)
            } else {
                flush()
                val s = ch.toString()
                c.drawText(s, cx, y, sys)
                cx += sys.measureText(s)
            }
        }
        flush()
        return cx
    }

    /** 按宽度换行（中文逐字，英文尽量按空格） */
    private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        for (para in text.replace("\r", "").split("\n")) {
            if (para.isEmpty()) { out.add(""); continue }
            var line = StringBuilder()
            var lastSpace = -1
            for (ch in para) {
                line.append(ch)
                if (ch == ' ') lastSpace = line.length - 1
                if (p.measureText(line.toString()) > maxWidth && line.length > 1) {
                    if (lastSpace > 0) {
                        out.add(line.substring(0, lastSpace))
                        val rest = line.substring(lastSpace + 1)
                        line = StringBuilder(rest)
                        lastSpace = -1
                    } else {
                        line.deleteCharAt(line.length - 1)
                        out.add(line.toString())
                        line = StringBuilder().append(ch)
                    }
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    /** 塞进一行：从 maxSize 往下缩，实在不行就截断加 … */
    private fun fitLine(text: String, maxWidth: Float, maxSize: Float, minSize: Float, en: Boolean = false): Pair<Paint, String> {
        var size = maxSize
        while (size > minSize) {
            val p = paint(size, Color.rgb(51, 51, 51), en)
            if (p.measureText(text) <= maxWidth) return p to text
            size -= 1f
        }
        val p = paint(minSize, Color.rgb(51, 51, 51), en)
        var s = text
        while (s.length > 1 && p.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return p to if (s.length < text.length) "$s…" else s
    }

    // ---------------------------------------------------------------- 资源缓存

    private val bmpCache = HashMap<String, Bitmap?>()

    private fun loadUi(name: String): Bitmap? = bmpCache.getOrPut("ui:$name") {
        val f = res.ui(name)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun loadCover(musicId: String): Bitmap? = bmpCache.getOrPut("cover:$musicId") {
        val f = res.cover(musicId) ?: res.defaultCover()
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun loadAvatar(qq: String): Bitmap? {
        if (qq.isBlank()) return null
        return bmpCache.getOrPut("avatar:$qq") {
            val dir = File(ctx.filesDir, "avatars").apply { mkdirs() }
            val f = File(dir, "$qq.png")
            if (!f.exists()) downloadAvatar(qq, f)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        }
    }

    private fun downloadAvatar(qq: String, dst: File) {
        try {
            val url = URL("https://q.qlogo.cn/headimg_dl?dst_uin=$qq&spec=640")
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 12000
            }
            c.inputStream.use { input -> dst.outputStream().use { input.copyTo(it) } }
            c.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "头像下载失败 qq=$qq: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- 尺寸

    private fun rowsOf(n: Int) = (n + 1) / 2

    private fun measureHeight(input: Input): Int {
        var h = HEADER_H.toFloat()
        h += SECTION_TITLE_H + rowsOf(input.b50.b35.size) * (CARD_H + CARD_GAP) + 10
        h += SECTION_TITLE_H + rowsOf(input.b50.b15.size) * (CARD_H + CARD_GAP) + 10
        if (input.bodyText.isNotBlank()) h += 700f
        h += 120f
        return h.toInt().coerceAtLeast(1200)
    }

    // ---------------------------------------------------------------- 主流程

    fun render(input: Input): Bitmap {
        val t0 = System.currentTimeMillis()
        val h = measureHeight(input)
        val canvasBmp = Bitmap.createBitmap(CANVAS_W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvasBmp)
        c.drawColor(Color.WHITE)

        drawHeader(c, input)
        var y = HEADER_H + 10f
        y = drawSongSection(c, y, "B35", "旧版本 BEST 35", Color.rgb(26, 115, 232), input.b50.b35)
        y = drawSongSection(c, y, "B15", "当前版本 BEST 15", Color.rgb(232, 124, 32), input.b50.b15)
        if (input.bodyText.isNotBlank()) y = drawAnalysis(c, y + 20f, input)
        drawFooter(c, y + 40f)

        // 缩放输出
        val outW = input.outWidth.coerceIn(600, CANVAS_W)
        val outH = (outW.toFloat() * h / CANVAS_W).toInt()
        val out = Bitmap.createScaledBitmap(canvasBmp, outW, outH, true)
        canvasBmp.recycle()
        Log.w(TAG, "渲染完成 ${outW}x$outH 耗时 ${System.currentTimeMillis() - t0} ms")
        return out
    }

    // ---------------------------------------------------------------- 顶部信息卡

    private fun drawHeader(c: Canvas, input: Input) {
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 245, 248, 255) }
        c.drawRoundRect(RectF(40f, 30f, 1080f, 420f), 20f, 20f, panel)

        // 头像（圆形裁剪）
        val av = loadAvatar(input.qq)
        if (av != null) {
            val size = 140
            val scaled = Bitmap.createScaledBitmap(av, size, size, true)
            val round = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val rc = Canvas(round)
            val path = android.graphics.Path().apply { addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW) }
            rc.clipPath(path)
            rc.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            c.drawBitmap(round, 80f, 50f, Paint(Paint.ANTI_ALIAS_FLAG))
        }

        // 昵称
        val (nickPaint, nickText) = fitLine(input.nickname, 330f, 34f, 20f)
        nickPaint.color = Color.rgb(51, 51, 51)
        c.drawText(nickText, 240f, 72f, nickPaint)

        // 段位小图（DXRating 01~11）
        loadUi(ratingPic(input.rating))?.let { bmp ->
            c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(240f, 111f, 463f, 153f), Paint(Paint.ANTI_ALIAS_FLAG))
        }

        // Rating 数字（用数字图拼）
        var nx = 240f
        for (ch in String.format("%05d", input.rating)) {
            loadUi("UI_NUM_Drating_$ch")?.let { bmp ->
                c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(nx, 165f, nx + 30f, 201f), Paint(Paint.ANTI_ALIAS_FLAG))
            }
            nx += 34f
        }

        drawText(c, "B35", 80f, 245f, 26f, Color.rgb(120, 120, 120), en = true)
        drawText(c, "${input.b35Ra}  /  ${input.b15Ra}", 220f, 245f, 32f, Color.rgb(51, 51, 51), en = true)

        drawText(c, "总 rating", 80f, 305f, 26f, Color.rgb(120, 120, 120))
        drawText(c, "${input.rating}", 220f, 305f, 36f, Color.rgb(46, 125, 50), en = true)

        drawText(c, "成绩条数", 80f, 360f, 26f, Color.rgb(120, 120, 120))
        drawText(c, "${input.b50.recordsTotal}", 220f, 360f, 36f, Color.rgb(66, 133, 244), en = true)

        // 右侧说明
        val panel2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 251, 235) }
        c.drawRoundRect(RectF(1100f, 30f, 1760f, 420f), 20f, 20f, panel2)
        drawText(c, "数据来源", 1130f, 90f, 28f, Color.rgb(180, 110, 20))
        drawText(c, "水鱼查分器 · OAuth 全量成绩", 1130f, 140f, 24f, Color.rgb(95, 85, 65))
        drawText(c, "当前版本", 1130f, 210f, 28f, Color.rgb(180, 110, 20))
        val ver = input.b50.currentVersions.joinToString("、").ifBlank { "未知" }
        for ((i, line) in wrap(ver, paint(22f, Color.rgb(95, 85, 65)), 600f).take(3).withIndex()) {
            drawText(c, line, 1130f, 258f + i * 34f, 22f, Color.rgb(95, 85, 65))
        }
        drawText(c, "两池规则：旧版前 35 + 当前版前 15", 1130f, 380f, 20f, Color.rgb(140, 120, 90))
    }

    private fun ratingPic(rating: Int): String = when {
        rating < 1000 -> "UI_CMN_DXRating_01"
        rating < 2000 -> "UI_CMN_DXRating_02"
        rating < 4000 -> "UI_CMN_DXRating_03"
        rating < 7000 -> "UI_CMN_DXRating_04"
        rating < 10000 -> "UI_CMN_DXRating_05"
        rating < 12000 -> "UI_CMN_DXRating_06"
        rating < 13000 -> "UI_CMN_DXRating_07"
        rating < 14000 -> "UI_CMN_DXRating_08"
        rating < 14500 -> "UI_CMN_DXRating_09"
        rating < 15000 -> "UI_CMN_DXRating_10"
        else -> "UI_CMN_DXRating_11"
    }

    // ---------------------------------------------------------------- 曲目分区

    private fun drawSongSection(c: Canvas, top: Float, label: String, desc: String, color: Int, charts: List<RecordsNormalizer.Chart>): Float {
        var y = top
        // 标题胶囊
        val tp = paint(30f, color, bold = true)
        val tw = tp.measureText(label)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(200, Color.red(color).let { it + 140 }.coerceAtMost(255),
                Color.green(color).let { it + 140 }.coerceAtMost(255),
                Color.blue(color).let { it + 140 }.coerceAtMost(255))
        }
        c.drawRoundRect(RectF(50f, y - 4f, 70f + tw + 10f, y + 40f), 12f, 12f, bg)
        drawText(c, label, 60f, y + 26f, 30f, color, bold = true)
        drawText(c, desc, 90f + tw + 10f, y + 26f, 20f, Color.rgb(140, 140, 140))
        y += SECTION_TITLE_H

        for (row in charts.indices step 2) {
            for (col in 0..1) {
                val i = row + col
                if (i >= charts.size) break
                drawSongCard(c, 60f + col * 880f, y, CARD_W, CARD_H, charts[i], color)
            }
            y += CARD_H + CARD_GAP
        }
        return y + 10f
    }

    private fun drawSongCard(c: Canvas, x: Float, y: Float, w: Float, h: Float, ch: RecordsNormalizer.Chart, accent: Int) {
        // 卡背景
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 250, 252, 255) }
        c.drawRoundRect(RectF(x, y, x + w, y + h), 16f, 16f, bg)

        val li = ch.levelIndex.coerceIn(0, 4)
        val diffColor = BORDER[li]

        // 曲绘 + 难度色边框
        val coverSize = 160f
        val border = 7f
        val coverBmp = loadCover(ch.musicId)
        if (coverBmp != null) {
            val scaled = Bitmap.createScaledBitmap(coverBmp, coverSize.toInt(), coverSize.toInt(), true)
            val paintImg = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(x + 30f - border, y + 28f - border,
                x + 30f + coverSize + border, y + 28f + coverSize + border), 8f, 8f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = diffColor })
            c.drawBitmap(scaled, x + 30f, y + 28f, paintImg)
        }

        val tx = x + 220f
        // 标题
        val (titlePaint, titleText) = fitLine(ch.title, 580f, 26f, 16f)
        titlePaint.color = Color.rgb(51, 51, 51)
        c.drawText(titleText, tx, y + 42f, titlePaint)

        // 分隔线
        c.drawRect(RectF(tx, y + 55f, x + 800f, y + 62f), Paint().apply { color = diffColor })

        // 达成率
        drawText(c, String.format("%.4f%%", ch.achievement), tx, y + 108f, 48f, Color.rgb(33, 33, 33), en = true)

        // 难度图标 + ds + RA
        val infoY = y + 150f
        var dsX = tx
        val diffIcon = res.ui(DIFF_FILE[li])
        if (diffIcon.exists()) {
            val bmp = BitmapFactory.decodeFile(diffIcon.absolutePath)
            if (bmp != null) {
                val nh = 42f
                val nw = bmp.width * nh / bmp.height
                c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(tx, infoY - 32f, tx + nw, infoY - 32f + nh), Paint(Paint.ANTI_ALIAS_FLAG))
                dsX = tx + nw + 8f
            }
        } else {
            dsX = drawText(c, DIFF_SHORT[li], tx, infoY, 24f, Color.rgb(140, 140, 140), en = true) + 6f
        }
        dsX = drawText(c, String.format("%.1f", ch.ds), dsX, infoY, 26f, diffColor, en = true) + 6f
        dsX = drawText(c, "RA:${ch.ra}", dsX, infoY, 26f, diffColor, en = true) + 10f

        // 评级图标
        val rankName = rankIcon(ch.achievement)
        if (rankName.isNotBlank()) {
            loadUi(rankName)?.let { bmp ->
                c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height),
                    RectF(dsX, y + 66f, dsX + 110f, y + 121f), Paint(Paint.ANTI_ALIAS_FLAG))
            }
        }

        // FC / AP 徽章
        val fcName = when (ch.fcLabel) {
            "FC" -> "UI_MSS_MBase_Icon_FC"
            "FC+" -> "UI_MSS_MBase_Icon_FCp"
            "AP" -> "UI_MSS_MBase_Icon_AP"
            "AP+" -> "UI_MSS_MBase_Icon_APp"
            else -> ""
        }
        if (fcName.isNotBlank()) {
            loadUi(fcName)?.let { bmp ->
                c.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height),
                    RectF(dsX, y + 128f, dsX + 62f, y + 190f), Paint(Paint.ANTI_ALIAS_FLAG))
            }
        }

        // 第二行：定数 / 版本
        val (verPaint, verText) = fitLine(ch.version, 560f, 20f, 14f)
        verPaint.color = Color.rgb(150, 150, 150)
        c.drawText(verText, tx, y + 195f, verPaint)
    }

    private val DIFF_FILE = listOf("Basic", "Advanced", "Expert", "Master", "Re_master")
    private val DIFF_SHORT = listOf("BAS", "ADV", "EXP", "MAS", "ReM")
    private val BORDER = listOf(
        Color.rgb(46, 125, 50), Color.rgb(249, 168, 37), Color.rgb(229, 57, 53),
        Color.rgb(156, 39, 176), Color.rgb(156, 100, 220),
    )

    private fun rankIcon(ach: Double): String = when {
        ach >= 100.5 -> "UI_TTR_Rank_SSSp"
        ach >= 100.0 -> "UI_TTR_Rank_SSS"
        ach >= 99.5 -> "UI_TTR_Rank_SSp"
        ach >= 99.0 -> "UI_TTR_Rank_SS"
        ach >= 98.0 -> "UI_TTR_Rank_Sp"
        ach >= 97.0 -> "UI_TTR_Rank_S"
        ach >= 94.0 -> "UI_TTR_Rank_AAA"
        ach >= 90.0 -> "UI_TTR_Rank_AA"
        ach >= 80.0 -> "UI_TTR_Rank_A"
        else -> ""
    }

    // ---------------------------------------------------------------- 锐评正文

    private fun drawAnalysis(c: Canvas, top: Float, input: Input): Float {
        val bodyPaint = paint(26f, Color.rgb(80, 65, 45))
        val lines = wrap(input.bodyText, bodyPaint, 1560f)
        val step = 42f
        val panelH = maxOf(300f, 100f + lines.size * step + 40f)

        // 标题
        drawText(c, input.analysisTitle, 100f, top + 40f, 34f, Color.rgb(26, 115, 232), bold = true)

        val bodyTop = top + 60f
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 255, 249, 238) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 210, 150); style = Paint.Style.STROKE; strokeWidth = 3f
        }
        c.drawRoundRect(RectF(70f, bodyTop, 1730f, bodyTop + panelH), 16f, 16f, panel)
        c.drawRoundRect(RectF(70f, bodyTop, 1730f, bodyTop + panelH), 16f, 16f, borderPaint)

        var y = bodyTop + 60f
        for (line in lines) {
            drawText(c, line, 120f, y, 26f, Color.rgb(80, 65, 45))
            y += step
        }
        return bodyTop + panelH
    }

    private fun drawFooter(c: Canvas, y: Float) {
        val text = "B50 锐评机 · 数据来自水鱼查分器"
        val p = paint(24f, Color.rgb(180, 160, 130))
        val tw = p.measureText(text)
        c.drawText(text, (CANVAS_W - tw) / 2f, y, p)
    }

    // ---------------------------------------------------------------- 保存

    /** 保存到 App 私有目录（用户要求：先落私有目录，再由用户选择保存/分享） */
    fun saveToPrivate(bmp: Bitmap, format: String = "png"): File {
        val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
        val name = "b50_" + System.currentTimeMillis() + "." + format
        val f = File(dir, name)
        f.outputStream().use { out ->
            when (format.lowercase()) {
                "jpg", "jpeg" -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                "webp" -> bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 92, out)
                else -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        Log.w(TAG, "已保存 ${f.absolutePath} (${f.length() / 1024} KB)")
        return f
    }
}
