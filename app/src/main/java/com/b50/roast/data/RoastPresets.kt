package com.b50.roast.data

/**
 * 「写什么」卡片里那两个按钮（语气/人设、角度）的预设菜单数据源。
 *
 * 设计约定（改文案只动这个文件，UI 不用碰）：
 *   · label = 菜单里显示的那一行，越短越好（2~6 个字）
 *   · value = 真正塞进提示词里的内容
 *       - 语气/人设 → Python 侧 `【用户指定语气/人设】{tone}`；空串 = 整段省略（默认）
 *       - 角度      → Python 侧 `【分析角度】{angle}`；默认值必须与 AppState.angle 初值一致
 *   · 预设只是一个"快捷填词"，用户还能在菜单最后选「自定义…」自己写，
 *     所以这里加条目不会限制能力。
 */
data class RoastPreset(val label: String, val value: String)

object RoastPresets {

    /** 语气 / 人设 —— value 为空串表示不指定 */
    val TONES = listOf(
        RoastPreset("默认", ""),                                // 空串 = 不指定，提示词里整段省略
        RoastPreset("毒舌锐评", "毒舌但不人身攻击，专挑成绩结构里的痛点扎"),
        RoastPreset("温柔教练", "温和、鼓励式：先肯定优点，再明确指出问题"),
        RoastPreset("雌小鬼", "雌小鬼口吻，居高临下地嘲讽，可以叫玩家「杂鱼」"),
        RoastPreset("猫娘", "温柔猫娘，句尾自然带「喵」，偶尔撒娇"),
        RoastPreset("中二解说", "中二解说腔，用夸张比喻，气势拉满"),
        RoastPreset("圈内老玩家", "舞萌圈老玩家随口点评，口语化、不用敬语"),
    )

    /** 角度 —— ANGLES[0] 的值必须等于 AppState.angle 的初值，否则首启会显示成「自定义」 */
    val ANGLES = listOf(
        RoastPreset("综合锐评", "综合锐评"),
        RoastPreset("推分建议", "推分建议：重点说哪几张值得上分、大概能涨多少"),
        RoastPreset("配置诊断", "配置诊断：重点说谱面配置的强项、短板与练习方向"),
        RoastPreset("定数趋势", "定数趋势：重点说各定数档的软硬变化"),
        RoastPreset("与同段对比", "与同段对比：重点用 ARPI 和同段差值说话"),
    )

    /** 找当前值命中的预设；返回 null = 用户自定义过（或来自旧版本的自由文本） */
    fun match(list: List<RoastPreset>, value: String): RoastPreset? =
        list.firstOrNull { it.value == value.trim() }
}
