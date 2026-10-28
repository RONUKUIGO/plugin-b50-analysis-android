"""把 B50 数据压成「可引用的事实清单」，让锐评能落到配置标签级别。

只做统计与格式化，不做任何主观判断：
    ds_band_lines()      定数分档画像（13 / 13+ / 14 各段均值）
    config_profile()     按谱面配置标签（键盘/星星/扫键/绝赞/touch…）聚合强弱
    evidence_lines()     evidence pack 里的关键证据（同段最强/最弱、14 段高分等）
    song_line()          单曲一行：难度 定数 达成率 RA FC 同段gap 重合度 标签
    community_lines()    社区印象摘要（供「大家都说…」用）
"""
from __future__ import annotations

from typing import Any

DIFF_NAME = {0: "BAS", 1: "ADV", 2: "EXP", 3: "MAS", 4: "ReM"}


def _f(v: Any, d: float = 0.0) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return d


def _i(v: Any, d: int = 0) -> int:
    try:
        return int(v)
    except (TypeError, ValueError):
        return d


def cs_of(song: dict, chart_summaries: dict | None) -> dict:
    """取某首歌的社区摘要（chart_summary.json 里的一坨）。"""
    return (chart_summaries or {}).get(str(song.get("music_id") or "")) or {}


def tags_of(song: dict, chart_summaries: dict | None) -> list[str]:
    """取配置标签，优先用 chart_summary，其次用谱面自带字段。"""
    raw = cs_of(song, chart_summaries).get("config_tags") or song.get("config_tags") or []
    out: list[str] = []
    for t in raw:
        t = str(t).strip()
        if t and t not in out:
            out.append(t)
    return out


# ---------------------------------------------------------------- 单曲
def song_line(idx: int, song: dict, chart_summaries: dict | None = None, max_tags: int = 3) -> str:
    ds = _f(song.get("ds"))
    ach = _f(song.get("achievement"))
    lv = DIFF_NAME.get(_i(song.get("level_index")), "?")
    parts = [f"{idx:>2}. {song.get('title')} | {lv} {ds:.1f} | {ach:.4f}% | RA{_i(song.get('ra'))}"]
    if song.get("fc_label"):
        parts.append(str(song["fc_label"]))
    if _i(song.get("play_count")):
        parts.append(f"PC{_i(song.get('play_count'))}")
    if song.get("gap") is not None:
        seg = f"同段{_f(song.get('gap')):+.4f}"
        if song.get("overlap") is not None:
            seg += f"/重合{_f(song.get('overlap')):.1f}%"
        parts.append(seg)
    tags = tags_of(song, chart_summaries)[:max_tags]
    if tags:
        parts.append("标签:" + "·".join(tags))
    return " | ".join(parts)


# ---------------------------------------------------------------- 定数分档
def _band_value(band: str) -> float:
    return _f(str(band).replace("+", ".5"))


def ds_band_lines(context: dict) -> list[str]:
    bands = ((context.get("b50_evidence_pack") or {}).get("ds_band_summary") or {})
    if not bands:
        return []
    rows = []
    for band, v in bands.items():
        rows.append((_band_value(band), band, v))
    rows.sort(key=lambda x: -x[0])
    out = []
    for _, band, v in rows:
        seg = f"{band} 档 {_i(v.get('count'))} 首 | 均 {_f(v.get('avg_achievement')):.4f}%"
        if v.get("avg_song_rating") is not None:
            seg += f" | 均单曲RA {_i(v.get('avg_song_rating'))}"
        out.append(seg)
    return out


# ---------------------------------------------------------------- 配置画像
def config_profile(context: dict, min_count: int = 2, rows: list[dict] | None = None) -> dict:
    """按配置标签聚合：出现次数、平均达成率、相对自己整体均值的偏离。

    rows: 聚合范围。默认（None）只用 context["b50"] 那 50 首；
          full 模式会传全量记录进来，样本从 50 涨到几千，标签统计才有统计意义。
    """
    b50 = context.get("b50") or [] if rows is None else rows
    cs = context.get("chart_summaries") or {}
    if rows is None:
        overall = _f((context.get("summary") or {}).get("avg_achievement"))
    else:
        vals = [_ach(r) for r in rows if _ach(r) > 0]
        overall = sum(vals) / len(vals) if vals else 0.0

    agg: dict[str, dict] = {}
    for song in b50:
        for tag in tags_of(song, cs):
            d = agg.setdefault(tag, {"count": 0, "ach": 0.0, "gap": 0.0, "gap_n": 0, "songs": []})
            d["count"] += 1
            d["ach"] += _f(song.get("achievement"))
            if song.get("gap") is not None:
                d["gap"] += _f(song.get("gap"))
                d["gap_n"] += 1
            title = str(song.get("title") or "")
            if title and title not in d["songs"] and len(d["songs"]) < 2:
                d["songs"].append(title)

    items = []
    for tag, d in agg.items():
        if d["count"] < min_count:
            continue
        avg = d["ach"] / d["count"]
        items.append({
            "tag": tag,
            "count": d["count"],
            "avg": round(avg, 4),
            "gap": round(d["gap"] / d["gap_n"], 4) if d["gap_n"] else None,
            "vs_overall": round(avg - overall, 4),
            "songs": d["songs"],
        })
    items.sort(key=lambda x: -x["avg"])
    strong = items[:3]
    weak_pool = [x for x in items if x["tag"] not in {s["tag"] for s in strong}]
    weak = sorted(weak_pool, key=lambda x: x["avg"])[:3]
    return {"overall": round(overall, 4), "total_tags": len(items), "min_count": min_count,
            "songs": len(b50), "strong": strong, "weak": weak}


def config_lines(profile: dict) -> list[str]:
    if not profile or not profile.get("strong"):
        return []

    def fmt(item: dict) -> str:
        seg = f"{item['tag']}({item['count']}首 均{item['avg']:.4f}%"
        if item.get("vs_overall") is not None:
            seg += f"，{'高于' if item['vs_overall'] >= 0 else '低于'}全盘均值{abs(item['vs_overall']):.4f}"
        if item.get("gap") is not None:
            seg += f"，同段{item['gap']:+.4f}"
        if item.get("songs"):
            seg += "，例：" + "、".join(item["songs"])
        return seg + ")"

    out = []
    if profile["strong"]:
        out.append("强项：" + "；".join(fmt(x) for x in profile["strong"]))
    if profile["weak"]:
        out.append("相对短板：" + "；".join(fmt(x) for x in profile["weak"]))
    out.append(f"（以上按 {profile['total_tags']} 个出现≥{profile.get('min_count', 2)} 次、"
               f"共 {profile.get('songs', 0)} 张谱的标签聚合，整体均值 {profile['overall']:.4f}%）")
    return out


# ---------------------------------------------------------------- 重点证据
def _ev_line(row: dict, cs: dict, max_tags: int = 3) -> str:
    seg = (f"{row.get('title')}（{row.get('level_label') or DIFF_NAME.get(_i(row.get('level_index')), '')} "
           f"{_f(row.get('ds')):.1f}）{_f(row.get('achievement')):.4f}%"
           f" RA{_i(row.get('song_rating') or row.get('ra'))}")
    if row.get("gap_vs_peer") is not None:
        seg += f" 同段{_f(row.get('gap_vs_peer')):+.4f}"
    if row.get("overlap") is not None:
        seg += f" 重合{_f(row.get('overlap')):.1f}%"
    if row.get("fc_label"):
        seg += f" {row['fc_label']}"
    tags = (row.get("config_tags") or tags_of(row, cs))[:max_tags]
    if tags:
        seg += " 标签:" + "·".join(str(t) for t in tags)
    return seg


def evidence_lines(context: dict) -> list[str]:
    pack = context.get("b50_evidence_pack") or {}
    cs = context.get("chart_summaries") or {}
    out: list[str] = []

    def block(title: str, rows: Any, limit: int = 2) -> None:
        if not rows:
            return
        rows = rows[:limit] if isinstance(rows, list) else []
        if not rows:
            return
        out.append(f"{title}：")
        out.extend("  - " + _ev_line(r, cs) for r in rows if isinstance(r, dict))

    block("同段最强（比同 rating 段高最多）", pack.get("strongest_vs_peer"), 2)
    block("同段最弱（被同段反杀）", pack.get("weakest_vs_peer"), 2)
    block("中定数高产出", pack.get("mid_ds_high_gap"), 2)
    block("异常 gap（需要点名表扬/警惕）", pack.get("abnormal_peer_gaps"), 2)
    block("单曲 RA 最高", pack.get("highest_song_rating"), 2)
    block("理论值 / AP 高光", pack.get("theory_cards"), 2)
    block("高定数 AP", pack.get("high_ds_ap"), 2)
    block("14+ AP", pack.get("level_14_plus_ap"), 2)
    return out


# ---------------------------------------------------------------- 社区印象
def community_lines(context: dict, limit: int = 4) -> list[str]:
    """给几首关键谱面的社区印象，供「大家都说」这类口播使用。"""
    cs = context.get("chart_summaries") or {}
    rows = ((context.get("b50_evidence_pack") or {}).get("selected_evidence") or [])[:limit]
    out = []
    for r in rows:
        info = cs.get(str(r.get("music_id") or "")) or {}
        vibe = str(info.get("chart_identity") or info.get("community_vibe") or "").strip()
        if not vibe:
            continue
        if len(vibe) > 60:
            vibe = vibe[:60] + "…"
        out.append(f"{r.get('title')}：{vibe}")
    return out


# ================================================================ 全量模式专用统计
# 下面这些只有 --mode full 才有数据源（全量记录比 B50 多得多），quick 模式一律返回空，
# 所以调用方（b50.py / roast.py）判空即可，不会影响现有行为。

GRADE_LADDER = (("SSS+", 100.5), ("SSS", 100.0), ("SS", 99.0), ("S", 98.0),
                ("AAA", 97.0), ("AA", 95.0), ("A", 93.0), ("B", 90.0))


def _ra(ds: Any, achievement: Any) -> int:
    """官方单曲 rating 公式（与 vendor/mai/context_builder._calc_ra 同口径）。"""
    ach = _f(achievement)
    ds = _f(ds)
    if ach < 50:
        base = 7.0
    elif ach < 60:
        base = 8.0
    elif ach < 70:
        base = 9.6
    elif ach < 75:
        base = 11.2
    elif ach < 80:
        base = 12.0
    elif ach < 90:
        base = 13.6
    elif ach < 94:
        base = 15.2
    elif ach < 97:
        base = 16.8
    elif ach < 98:
        base = 20.0
    elif ach < 99:
        base = 20.3
    elif ach < 99.5:
        base = 20.8
    elif ach < 100:
        base = 21.1
    elif ach < 100.5:
        base = 21.6
    else:
        base = 22.4
    return int(ds * (min(100.5, ach) / 100) * base)


def _ach(row: dict) -> float:
    """兼容两种字段名：接口原始记录用 achievements，规整后用 achievement。"""
    return _f(row.get("achievement") or row.get("achievements"))


def grade_of(achievement: Any) -> str:
    """达成率 → 评级字母（用于分布统计，不参与评分）。"""
    ach = _f(achievement)
    for name, floor in GRADE_LADDER:
        if ach >= floor:
            return name
    return "C 以下"


def library_counts(music_data: list[dict] | None) -> dict[int, int]:
    """曲库里各难度有多少张谱（算完成度用）。"""
    out: dict[int, int] = {}
    for m in music_data or []:
        for li, ds in enumerate(m.get("ds") or []):
            if _f(ds) > 0:
                out[li] = out.get(li, 0) + 1
    return out


def full_stats(records: list[dict], music_data: list[dict] | None = None) -> dict:
    """账号总量分区：总谱数 / 曲目数 / 总 PC / 全曲平均达成率 / 评级分布 / 各难度完成度。"""
    if not records:
        return {}

    charts = len(records)
    songs = {str(r.get("music_id") or r.get("song_id") or "") for r in records}
    songs.discard("")

    raws = [_ach(r) for r in records]
    graded = [a for a in raws if a > 0]
    master_plus = [_ach(r) for r in records
                   if _i(r.get("level_index")) >= 3 and _ach(r) > 0]

    pc_total = sum(_i(r.get("play_count")) for r in records)

    by_diff: dict[int, dict] = {}
    for r in records:
        li = _i(r.get("level_index"))
        d = by_diff.setdefault(li, {"charts": 0, "ach": 0.0, "n": 0, "ra_max": 0})
        d["charts"] += 1
        ach = _ach(r)
        if ach > 0:
            d["ach"] += ach
            d["n"] += 1
        d["ra_max"] = max(d["ra_max"], _i(r.get("ra")))
    lib = library_counts(music_data)
    for li, d in by_diff.items():
        d["avg"] = round(d["ach"] / d["n"], 4) if d["n"] else 0.0
        total = lib.get(li, 0)
        d["library"] = total
        d["completion"] = round(d["charts"] / total * 100, 1) if total else None

    grades: dict[str, int] = {}
    for a in graded:
        g = grade_of(a)
        grades[g] = grades.get(g, 0) + 1

    return {
        "charts": charts,
        "songs": len(songs),
        "total_pc": pc_total,
        "has_pc": pc_total > 0,
        "avg_achievement": round(sum(graded) / len(graded), 4) if graded else 0.0,
        "master_plus_avg": round(sum(master_plus) / len(master_plus), 4) if master_plus else 0.0,
        "by_difficulty": dict(sorted(by_diff.items())),
        "grades": grades,
        "library_total": sum(lib.values()),
    }


def full_stat_lines(stats: dict) -> list[str]:
    """把 full_stats 转成给 LLM 看的几行事实（不含判断）。"""
    if not stats:
        return []
    out: list[str] = []
    head = f"全量记录 {stats.get('charts')} 张谱 / {stats.get('songs')} 首曲"
    if stats.get("has_pc"):
        head += f" / 总游玩次数 {stats.get('total_pc')}"
    else:
        head += "（接口没给单曲 PC，总 PC 未知）"
    out.append(head)
    out.append(
        f"全曲平均达成率 {_f(stats.get('avg_achievement')):.4f}%"
        f" | Master 及以上 {_f(stats.get('master_plus_avg')):.4f}%"
    )
    grades = stats.get("grades") or {}
    if grades:
        order = [g for g, _ in GRADE_LADDER] + ["C 以下"]
        seg = " / ".join(f"{g} {grades[g]}" for g in order if grades.get(g))
        out.append("评级分布：" + seg)
    for li, d in (stats.get("by_difficulty") or {}).items():
        name = DIFF_NAME.get(int(li), f"L{li}")
        seg = f"{name} 已打 {d.get('charts')} 张"
        if d.get("library"):
            seg += f"（曲库 {d.get('library')} 张，完成度 {d.get('completion')}%）"
        seg += f"，均达成 {_f(d.get('avg')):.4f}%"
        out.append(seg)
    return out


def near_b50(records: list[dict], b50: list[dict], limit: int = 20) -> list[dict]:
    """「差一点就能进 B50」：没进榜的谱里 RA 最接近榜底的那些。

    需要多少才能挤进去（need）按池算：当前版本池跟 B15 榜底比，旧版本池跟 B35 榜底比。
    """
    keys = {(str(c.get("music_id") or c.get("song_id") or ""), _i(c.get("level_index"))) for c in b50}
    b35 = [_i(c.get("ra")) for c in b50 if c.get("bucket") == "B35"]
    b15 = [_i(c.get("ra")) for c in b50 if c.get("bucket") == "B15"]
    b15_versions = {str(c.get("version") or "") for c in b50 if c.get("bucket") == "B15"}
    floor35 = min(b35) if b35 else 0
    floor15 = min(b15) if b15 else 0

    rows: list[dict] = []
    for r in records or []:
        mid = str(r.get("music_id") or r.get("song_id") or "")
        li = _i(r.get("level_index"))
        if (mid, li) in keys:
            continue
        ra = _i(r.get("ra"))
        if ra <= 0:
            continue
        in_current = str(r.get("version") or "") in b15_versions if b15_versions else False
        floor = floor15 if in_current else floor35
        rows.append({
            "music_id": mid,
            "title": str(r.get("title") or ""),
            "level_index": li,
            "level_label": str(r.get("level_label") or DIFF_NAME.get(li, "")),
            "level": str(r.get("level") or ""),
            "ds": round(_f(r.get("ds")), 1),
            "achievement": round(_ach(r), 4),
            "ra": ra,
            "version": str(r.get("version") or ""),
            "pool": "B15" if in_current else "B35",
            "need": floor + 1 - ra,
        })
    rows.sort(key=lambda x: (x["need"], -x["ra"]))
    return rows[:limit]


def near_b50_lines(rows: list[dict], limit: int = 8) -> list[str]:
    out = []
    for r in (rows or [])[:limit]:
        out.append(
            f"{r.get('title')} | {DIFF_NAME.get(_i(r.get('level_index')), '?')} {_f(r.get('ds')):.1f}"
            f" | {_f(r.get('achievement')):.4f}% | RA{_i(r.get('ra'))}"
            f" | 进 {r.get('pool')} 还差 {_i(r.get('need'))} RA"
        )
    return out


def unplayed_charts(records: list[dict], music_data: list[dict] | None,
                    ds_min: float = 14.0, limit: int = 20) -> list[dict]:
    """曲库里该玩家一张都没打过的谱（按「打到 100.5% 能拿多少单曲 RA」降序）。"""
    if not music_data:
        return []
    played = {(str(r.get("music_id") or r.get("song_id") or ""), _i(r.get("level_index")))
              for r in records or []}
    rows: list[dict] = []
    for m in music_data:
        mid = str(m.get("id") or "")
        ds_list = m.get("ds") or []
        lv_list = m.get("level") or []
        for li, ds in enumerate(ds_list):
            ds = _f(ds)
            if li < 3:                       # 只看 Master / Re:Master
                continue
            if ds < ds_min or (mid, li) in played:
                continue
            rows.append({
                "music_id": mid,
                "title": str(m.get("title") or ""),
                "type": str(m.get("type") or ""),
                "level_index": li,
                "level_label": DIFF_NAME.get(li, ""),
                "level": str(lv_list[li]) if len(lv_list) > li else "",
                "ds": round(ds, 1),
                "potential_ra": _ra(ds, 100.5),
            })
    rows.sort(key=lambda r: (-r["potential_ra"], r["title"]))
    return rows[:limit]


def unplayed_lines(rows: list[dict], limit: int = 8) -> list[str]:
    out = []
    for r in (rows or [])[:limit]:
        out.append(
            f"{r.get('title')} | {DIFF_NAME.get(_i(r.get('level_index')), '?')} {_f(r.get('ds')):.1f}"
            f" | 未游玩 | 理论单曲 RA {_i(r.get('potential_ra'))}"
        )
    return out
