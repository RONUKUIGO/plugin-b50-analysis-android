package com.b50.roast.data

import java.io.File

/**
 * 解压后资源的统一入口。业务代码不要自己拼 filesDir，全从这里拿。
 *
 * 目录结构（pack.zip 解压后）：
 *   covers/17.webp            曲绘 1569 张（200x200 WebP）
 *   ui/icons/bj.png           背景（与 render.py 的 self.icons / "bj.png" 一致）
 *   ui/Master.webp            难度图标
 *   ui/UI_NUM_Drating_3.webp  数字
 *   ui/default_cover.png      无曲绘时的占位（内层是 WebP 数据，名字仍是 .png）
 *   fonts/cn.otf              中文圆体（GB2312 子集）
 *   fonts/num.otf             数字/拉丁（Torus SemiBold）
 *   music_data.json           1326 首曲目元数据
 *   chart_summary.json        谱面难度摘要
 *   peer_stats.json           同段 ARPI 统计
 *   kb/ 目录下的 json          社区黑话词条
 */
class Res(private val installer: ResourceInstaller) {

    private val root: File get() = installer.root()

    /** 资源根目录（含 fonts/ ui/ covers/）—— 给 Python 渲染器用 */
    fun root(): File = root

    fun data(name: String) = File(root, name)
    fun ui(name: String) = File(root, "ui/icons/$name.png")
    /** 字体在 ui/fonts/ 下（与桌面版目录结构一致，Python 渲染器直接可用） */
    fun font(name: String) = File(root, "ui/fonts/$name")

    fun fontCn() = font("ResourceHanRoundedCN.otf")
    fun fontEn() = font("Torus SemiBold.otf")
    fun kb(): List<File> = File(root, "kb").listFiles()?.toList() ?: emptyList()

    /** 与 render.py 的 load_cover 完全一致的 id 归一化 */
    fun coverId(songId: String): String {
        var s = songId.trimStart('0')
        if (s.length == 5 && s.startsWith("10")) s = s.substring(2)
        return s.trimStart('0').ifBlank { "0" }
    }

    /** 本地曲绘；没有就 null（渲染层回落 default_cover） */
    fun cover(songId: String): File? =
        File(root, "cover/${coverId(songId)}.png").takeIf { it.exists() }

    fun defaultCover(): File = File(root, "ui/default_cover.png")

    fun avatarsDir(): File = File(root, "avatars").apply { mkdirs() }

    /**
     * 资源是否齐全（防止解压被中断 / 打包漏文件）。
     *
     * 这份清单必须与 `tools/pack_assets.py` 的 `REQUIRED_IN_PACK` 一致 ——
     * 2026-10-28 之前这里写着 `ui/bj.webp` 和 `ui/default_cover.webp`，
     * 而包里真实是 `ui/icons/bj.png` 和 `ui/default_cover.png`，
     * 于是设置页永远显示「缺 2 项」（假警报）。
     */
    fun sanityCheck(): List<String> {
        val miss = mutableListOf<String>()
        for (f in listOf(
            "ui/fonts/ResourceHanRoundedCN.otf",
            "ui/fonts/Torus SemiBold.otf",
            "ui/icons/bj.png",
            "ui/default_cover.png",
            "music_data.json",
            "chart_summary.json",
            "peer_stats.json",
        )) {
            val file = File(root, f)
            if (!file.exists()) {
                miss.add(f)
                continue
            }
            // 两个字体体量差两个数量级，阈值必须分开：
            //   ResourceHanRoundedCN.otf 中文子集实测 2.9 MB（<1 MB 说明子集化失败）
            //   Torus SemiBold.otf       拉丁/数字，实测只有 66 KB
            val tooSmall = when (f) {
                "ui/fonts/ResourceHanRoundedCN.otf" -> file.length() < 1_000_000
                "ui/fonts/Torus SemiBold.otf" -> file.length() < 10_000
                else -> false
            }
            if (tooSmall) miss.add(f + "(" + file.length() / 1024 + " KB，过小)")
        }
        val covers = File(root, "cover").listFiles()?.size ?: 0
        if (covers < 1400) miss.add("cover/(曲绘仅 $covers 张)")
        return miss
    }
}
