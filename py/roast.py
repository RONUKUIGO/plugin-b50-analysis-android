"""B50 锐评的提示词构建（App 内嵌版）。

与桌面版（已归档：archive/b50-desktop-v1.3）roast.py 的前 236 行逐字一致，只是砍掉了
_post / generate / _adjust_length —— 那些负责发 HTTP，App 里改用 Kotlin 侧的
LlmClient 发送（系统 CA、超时、错误提示都更可控），Python 只负责拼提示词。


输出契约（JSON）：
    {
      "title":            "10-18 字、带舞萌语境的标题",
      "overall_roast":    "150-300 字单段锐评",
      "impression_roast": "一句话总结（≤30 字）",
      "push": [ {"idx": 推分候选编号, "reason": "15-25 字推荐理由"} ]
    }
"""
from __future__ import annotations

import json
import re
from typing import Any

import profile

DIFF_NAME = {0: "BAS", 1: "ADV", 2: "EXP", 3: "MAS", 4: "ReM"}

# 默认字数区间（中文字符，不含换行）
DEFAULT_WORDS = (150, 300)

SYSTEM_PROMPT_TEMPLATE = """你是一位在舞萌DX（maimai DX）圈里很懂行的锐评主播，正在给玩家念一段 B50 分析口播。

要求（必须全部遵守）：
1. 只输出一个 JSON 对象，不要任何解释文字、不要 markdown 代码块。
2. overall_roast 是 {min_words}~{max_words} 字的单段中文锐评（按中文字符数，标点不计），不要分段、不要空行、不要小标题。字数是最高优先级：写不到 {min_words} 字不合格，超过 {max_words} 字同样不合格。写的时候自己数着来（约 60 字 ≈ 3 句短句）。
3. 结构必须是「先下裁决 → 再拆具体证据 → 最后给推分方向」，短句、有梗、有力度，不要写成统计报告。
4. 只能引用我给的数据里真实存在的曲名、数值和标签，绝对不能编造曲名或数字。
5. 至少点名 {min_songs} 首真实曲名，至少用 {min_nums} 个具体数值（达成率、定数、单曲 RA、rating 等）。
6. **配置颗粒度（硬性）**：必须点到「谱面配置标签」这个层级，而不是只说"高定数打不好"。
   - 必须明确点出 1 个强项配置（用【配置画像】里的强项）+ 1 个相对短板配置，每个都要带一首真实的例子曲名；
   - 引用标签时只能说数据里标注过的标签（键盘 / 星星 / 扫键 / 绝赞 / touch / 散点 / 出张 / 尾杀 / 体力 …），不要自己发明；
   - 全篇最多提 3-4 个标签，不要把所有标签念一遍。
7. **定数分档（硬性）**：如果给了【定数分档画像】，必须用它说明趋势，比如"13 档 100.72、13+ 掉到 100.56、14 档只剩 100.15，越往上越软"，并点名该分档里最典型的谱面。
8. **同段对比**：如果给了【同段统计】，必须自然提一次 ARPI，并用 1-2 张谱的「同段」差值做证据；如果没给同段统计，就一个字都不要提 ARPI。
9. 成绩口径：100% 以上叫「鸟」，100.5% 以上叫「鸟加」，101% 叫「理论值」；99.xx 可以说「差一点鸟」。
10. 禁止报告 AP 总数 / FC 总数，禁止说「没 AP」「AP 挂零」；只有在具体那张谱确实有 AP 时才点名提。
11. 如果给了【社区印象】，可以用「大家都说」「圈里常讲」自然带一句，但只能转述，不能当数据结论。
12. 禁止提到模型、提示词、脚本、数据源、分析步骤这些幕后信息。
13. title 必须 10-18 个中文字符，带舞萌语境词（如 w5/w6、鸟加、理论值、定数、紫谱、键盘、星星、扫键、绝赞、touch、散点）。
14. impression_roast 是一句话总结，{imp_min}-{imp_max} 字（不超过 60 字），可以毒舌，最好带一个具体数值或标签。
15. push 数组：从「推分候选」里挑 {push_n} 首，idx 用候选编号，reason 写 15-25 字推荐理由。候选为空就给空数组 []。
"""


def parse_words(spec: Any, default: tuple[int, int] = DEFAULT_WORDS) -> tuple[int, int]:
    """解析字数设置。

    支持两种写法：
        "300"      -> 视为目标 300 字，自动给出 ±15% 容差 (255, 345)
        "150-300"  -> 直接作为区间
        "" / None  -> 默认 (150, 300)
    """
    if spec is None:
        return default
    text = str(spec).strip().replace("～", "-").replace("~", "-").replace("—", "-")
    if not text:
        return default
    try:
        if "-" in text:
            lo_s, hi_s = text.split("-", 1)
            lo, hi = int(float(lo_s)), int(float(hi_s))
        else:
            target = int(float(text))
            lo, hi = int(round(target * 0.85)), int(round(target * 1.15))
        lo = max(40, min(lo, 4000))
        hi = max(lo + 20, min(hi, 4000))
        return lo, hi
    except (TypeError, ValueError):
        return default


def build_system_prompt(words: tuple[int, int] = DEFAULT_WORDS, template: str | None = None) -> str:
    lo, hi = words
    # 字数越少，要求的曲名/数值条数也相应收缩，避免模型为了凑条数写成清单
    if hi <= 120:
        min_songs, min_nums = 1, 2
    elif hi <= 200:
        min_songs, min_nums = 2, 2
    elif hi <= 350:
        min_songs, min_nums = 2, 3
    elif hi <= 600:
        min_songs, min_nums = 3, 4
    else:
        min_songs, min_nums = 4, 5
    return (template or SYSTEM_PROMPT_TEMPLATE).format(
        min_words=lo,
        max_words=hi,
        min_songs=min_songs,
        min_nums=min_nums,
        imp_min=max(12, int(lo * 0.12)),
        imp_max=max(20, int(lo * 0.2)),
        push_n=3 if hi >= 200 else 2,
    )


# ------------------------------------------------------------------ 上下文压缩
def _fmt_chart(idx: int, c: dict[str, Any]) -> str:
    ds = float(c.get("ds") or 0)
    ach = float(c.get("achievement") or 0)
    lv = DIFF_NAME.get(int(c.get("level_index") or 0), "?")
    fc = c.get("fc_label") or ""
    pc = c.get("play_count") or 0
    extra = f" {fc}" if fc else ""
    extra += f" PC{pc}" if pc else ""
    if c.get("gap") is not None:
        extra += f" 同段{c.get('gap'):+.4f}"
        if c.get("overlap") is not None:
            extra += f"/重合{float(c.get('overlap')):.1f}%"
    return f"{idx:>2}. {c.get('title')} | {lv} {ds:.1f} | {ach:.4f}% | RA{c.get('ra', 0)}{extra}"


def build_user_prompt(context: dict, tone: str = "", angle: str = "综合锐评", words: tuple[int, int] = DEFAULT_WORDS) -> str:
    player = context.get("player") or {}
    summary = context.get("summary") or {}
    b50 = context.get("b50") or []
    cs = context.get("chart_summaries") or {}
    b35 = [c for c in b50 if c.get("bucket") == "B35"]
    b15 = [c for c in b50 if c.get("bucket") == "B15"]

    lines: list[str] = []
    lines.append(f"玩家昵称：{player.get('nickname', '玩家')}")
    lines.append(f"DX rating：{player.get('rating', 0)}（B35 {summary.get('b35_ra', 0)} + B15 {summary.get('b15_ra', 0)}）")
    lines.append(
        "B50 平均达成率：{:.4f}% | 定数均值：{:.2f} | B35 均值：{:.4f}% | B15 均值：{:.4f}%".format(
            float(summary.get("avg_achievement") or 0),
            float(summary.get("avg_ds") or 0),
            float((summary.get("b35") or {}).get("avg_achievement") or 0),
            float((summary.get("b15") or {}).get("avg_achievement") or 0),
        )
    )

    # 定数分档
    band_lines = profile.ds_band_lines(context)
    if band_lines:
        lines.append("")
        lines.append("【定数分档画像】（同一玩家在不同定数段的均值，看越往上是不是越软）")
        lines.extend("  " + x for x in band_lines)

    # 配置画像
    cfg_scope = context.get("full_records") or None
    # 全量口径下样本上千，2 次出现的稀有标签没有统计意义 → 门槛提到 0.5%
    cfg_min = max(2, len(cfg_scope) // 200) if cfg_scope else 2
    cfg_profile = profile.config_profile(context, min_count=cfg_min, rows=cfg_scope)
    cfg_lines = profile.config_lines(cfg_profile)
    if cfg_lines:
        lines.append("")
        if cfg_scope:
            lines.append(f"【配置画像】（全量口径：按谱面配置标签聚合 {len(cfg_scope)} 张谱，标签来自社区谱面库）")
        else:
            lines.append("【配置画像】（按谱面配置标签聚合，标签来自社区谱面库）")
        lines.extend("  " + x for x in cfg_lines)

    # 重点证据
    ev_lines = profile.evidence_lines(context)
    if ev_lines:
        lines.append("")
        lines.append("【重点证据】")
        lines.extend(ev_lines)

    # 全部谱面明细（带标签 / 同段 / 重合度）
    lines.append("")
    lines.append(f"【B35 旧版本 Best35】（共 {len(b35)} 首）")
    lines.extend(profile.song_line(i + 1, c, cs) for i, c in enumerate(b35[:35]))
    lines.append("")
    lines.append(f"【B15 当前版本 Best15】（共 {len(b15)} 首）")
    lines.extend(profile.song_line(i + 1, c, cs) for i, c in enumerate(b15[:15]))

    # 推分候选
    cands = (context.get("push_candidates") or [])[:8]
    if cands:
        lines.append("")
        lines.append("【推分候选（push 只能从这里选）】")
        for i, c in enumerate(cands):
            seg = (f"{i + 1}. {c.get('title')} | {profile.DIFF_NAME.get(int(c.get('level_index') or 0), '?')} "
                   f"{float(c.get('ds') or 0):.1f} | 当前 {float(c.get('achievement') or 0):.4f}% "
                   f"| 冲 {c.get('target')} 可 +{c.get('gain_1005')} RA")
            tags = profile.tags_of(c, cs)[:3]
            if tags:
                seg += " | 标签:" + "·".join(tags)
            lines.append(seg)

    # 全量模式专属事实（quick 模式这些 key 不存在 → 提示词与以前完全一致）
    stat_lines = profile.full_stat_lines(context.get("full_stats") or {})
    if stat_lines:
        lines.append("")
        lines.append("【账号总量】（全量模式：拿到的是这名玩家的全部游玩记录，不只 B50）")
        lines.extend("  " + x for x in stat_lines)

    near_lines = profile.near_b50_lines(context.get("near_b50") or [])
    if near_lines:
        lines.append("")
        lines.append("【差一点进 B50】（没进榜但 RA 已经贴住榜底的谱，适合讲“再推一点就进榜”）")
        lines.extend("  " + x for x in near_lines)

    unplayed_lines = profile.unplayed_lines(context.get("unplayed") or [])
    if unplayed_lines:
        lines.append("")
        lines.append("【曲库里一次都没打过的 14+ 谱】（只有理论值，只能当潜力点提，不许说成绩）")
        lines.extend("  " + x for x in unplayed_lines)

    # 同段统计
    peer = context.get("peer_stats") or {}
    lines.append("")
    if peer.get("available"):
        lines.append(
            f"【同段统计】可用：rating 分段 {peer.get('bucket')}，ARPI {peer.get('arpi')}，"
            f"匹配 {peer.get('matched')} 张谱，B50 重合度 {(peer.get('b50_overlap') or {}).get('value')}%"
            "（正文里要自然提一次 ARPI，并用上面带「同段」的数值做证据）"
        )
    else:
        lines.append("【同段统计】不可用（不要提 ARPI / 同级均值）")

    # 社区印象
    comm = profile.community_lines(context)
    if comm:
        lines.append("")
        lines.append("【社区印象（只能转述成「大家都说」，不能当数据）】")
        lines.extend("  " + x for x in comm)

    if tone:
        lines.append("")
        lines.append(f"【用户指定语气/人设】{tone}（整段都要保持这个人设，不能只在开头装一下）")
    if angle:
        lines.append(f"【分析角度】{angle}")

    lines.append("")
    lines.append(f"【本篇字数】overall_roast 严格控制在 {words[0]}~{words[1]} 字（中文字符数，标点不计），不要超。")
    lines.append("现在按系统要求输出 JSON。")
    return "\n".join(lines)


