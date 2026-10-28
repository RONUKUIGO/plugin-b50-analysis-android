package com.b50.roast.data

/**
 * 7 个可画分区。
 *
 * 键名与桌面版 render.py / context_builder.py 里的 evidence 分区一一对应，
 * 所以 App 这边不用维护映射表，直接把 spec 字符串（"all" / "highlights,push"）
 * 传下去就行。
 */
object Sections {
    val ALL: List<Pair<String, String>> = listOf(
        "highlights" to "亮点",
        "ordinaries" to "普通点",
        "highest" to "最高RA",
        "overlap" to "重合",
        "push" to "推分",
        "config" to "配置",
        "least" to "少PC",
    )
    val KEYS: List<String> = ALL.map { it.first }
}
