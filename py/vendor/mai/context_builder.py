from __future__ import annotations

import gzip
import json
import random
import zipfile
from pathlib import Path
from typing import Any

FC_LABEL_MAP = {"fc": "FC", "fcp": "FC+", "ap": "AP", "app": "AP+"}


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


def _load_file(p: Path) -> dict | None:
    try:
        if p.suffix == ".zip":
            with zipfile.ZipFile(p) as zf:
                name = next(n for n in zf.namelist() if n.endswith(".json"))
                return json.loads(zf.read(name))
        if p.suffix == ".gz":
            with gzip.open(p) as f:
                return json.loads(f.read())
        return json.loads(p.read_text(encoding="utf-8"))
    except Exception:
        return None


def _load_json(assets: Path, *parts: str) -> dict | list | None:
    p = assets.joinpath(*parts)
    if not p.exists():
        return None
    return _load_file(p)


def load_peer_stats(assets_path: str) -> dict | None:
    """从 assets 目录自动查找 peer_stats 文件。"""
    if not assets_path:
        return None
    assets = Path(assets_path)
    for name in ("peer_stats.zip", "peer_stats.json.gz", "peer_stats.json"):
        p = assets / name
        if p.exists():
            return _load_file(p)
    return None


def _normalize(chart: dict) -> dict:
    c = dict(chart)
    c["music_id"] = str(c.get("song_id") or c.get("music_id") or "")
    c["achievement"] = _f(c.get("achievements") or c.get("achievement"))
    c["fc_label"] = FC_LABEL_MAP.get(str(c.get("fc") or "").lower(), "")
    return c


def _fine_rating_segment(rating: int) -> dict:
    if rating >= 16500:
        return {
            "label": "16500+ 顶级门槛段",
            "range": "16500+",
            "tone": "按顶段尺度评价，不要按普通 w6 轻描淡写。",
        }
    if rating >= 15000:
        start = (rating // 200) * 200
        return {
            "label": f"{start}-{start + 199} 细分段",
            "range": f"{start}-{start + 199}",
            "tone": "按 200 分细分段评价，不要只粗暴说 w5/w6。",
        }
    if rating >= 13500:
        start = (rating // 200) * 200
        return {
            "label": f"{start}-{start + 199} 上升段",
            "range": f"{start}-{start + 199}",
            "tone": "按 200 分细分段评价，重点看基本盘和推分空间。",
        }
    return {"label": "入门-进阶段", "range": "<13500", "tone": "以基础能力和推分空间为主。"}


def _ds_class(ds: float) -> str:
    if ds >= 14.6:
        return "14+"
    if ds >= 14.0:
        return "14"
    if ds >= 13.6:
        return "13+"
    if ds >= 13.0:
        return "13"
    return "<13"


def _gap_tier(gap: float | None) -> str:
    if gap is None:
        return ""
    if gap > 0.8:
        return "异常领先"
    if gap >= 0.5:
        return "明显领先"
    if gap < -0.8:
        return "异常落后"
    if gap <= -0.5:
        return "明显落后"
    return ""


def _song_evidence_row(chart: dict, chart_summaries: dict | None, rank: int) -> dict:
    gap = chart.get("gap")
    avg_achievement = chart.get("peer_avg")
    ds = _f(chart.get("ds"))
    achievement = _f(chart.get("achievement"))
    summary = (chart_summaries or {}).get(str(chart.get("music_id") or "")) or {}
    row = {
        "rank": rank,
        "music_id": str(chart.get("music_id") or ""),
        "title": str(chart.get("title") or ""),
        "bucket": chart.get("bucket"),
        "chart_type": chart.get("type") or chart.get("chart_type"),
        "level_label": chart.get("level_label"),
        "ds": ds,
        "ds_class": _ds_class(ds),
        "achievement": round(achievement, 4),
        "avg_achievement": round(_f(avg_achievement), 4) if avg_achievement is not None else None,
        "peer_avg": round(_f(avg_achievement), 4) if avg_achievement is not None else None,
        "gap": round(_f(gap), 4) if gap is not None else None,
        "gap_vs_peer": round(_f(gap), 4) if gap is not None else None,
        "gap_tier": _gap_tier(_f(gap)) if gap is not None else "",
        "song_rating": _i(chart.get("ra")),
        "fc_label": str(chart.get("fc_label") or ""),
        "is_ap": str(chart.get("fc_label") or "").upper() in {"AP", "AP+"},
        "config_tags": [str(x) for x in (summary.get("config_tags") or [])[:5]],
        "is_theory": achievement >= 101.0,
        "is_ap_target_reasonable": achievement >= 100.8,
        "overlap": chart.get("overlap"),
        "peer_sample_count": chart.get("peer_sample_count"),
    }
    return {k: v for k, v in row.items() if v not in (None, "", [])}


def _unique_rows(rows: list[dict], limit: int) -> list[dict]:
    seen: set[tuple[str, str]] = set()
    result: list[dict] = []
    for row in rows:
        key = (str(row.get("music_id") or ""), str(row.get("level_label") or ""))
        if key in seen:
            continue
        seen.add(key)
        result.append(row)
        if len(result) >= limit:
            break
    return result


def _section_summary(rows: list[dict], label: str) -> dict:
    gaps = [_f(r.get("gap_vs_peer")) for r in rows if r.get("gap_vs_peer") is not None]
    peers = [_f(r.get("avg_achievement")) for r in rows if r.get("avg_achievement") is not None]
    by_rating_desc = sorted(rows, key=lambda r: _i(r.get("song_rating")), reverse=True)
    by_rating_asc = sorted(rows, key=lambda r: _i(r.get("song_rating")))
    by_gap = sorted([r for r in rows if r.get("gap_vs_peer") is not None], key=lambda r: _f(r.get("gap_vs_peer")), reverse=True)
    return {
        "label": label,
        "count": len(rows),
        "role": "旧版本/历史 best 35，看基本盘、下限和长期结构" if label == "B35" else "当前版本/new best 15，看新版本适应、上限突破和近期推分效率",
        "avg_ds": round(sum(_f(r.get("ds")) for r in rows if _f(r.get("ds")) > 0) / len([r for r in rows if _f(r.get("ds")) > 0]), 2) if any(_f(r.get("ds")) > 0 for r in rows) else None,
        "avg_achievement": round(sum(_f(r.get("achievement")) for r in rows if _f(r.get("achievement")) > 0) / len([r for r in rows if _f(r.get("achievement")) > 0]), 4) if any(_f(r.get("achievement")) > 0 for r in rows) else None,
        "avg_peer_achievement": round(sum(peers) / len(peers), 4) if peers else None,
        "avg_gap_vs_peer": round(sum(gaps) / len(gaps), 4) if gaps else None,
        "avg_song_rating": round(sum(_f(r.get("song_rating")) for r in rows if _f(r.get("song_rating")) > 0) / len([r for r in rows if _f(r.get("song_rating")) > 0]), 1) if any(_f(r.get("song_rating")) > 0 for r in rows) else None,
        "top_cards": by_rating_desc[:5],
        "floor_cards": by_rating_asc[:5],
        "best_peer_gaps": by_gap[:4],
        "worst_peer_gaps": list(reversed(by_gap[-4:])),
    }


def _build_b50_evidence_pack(charts: list[dict], rating: int, peer_data: dict, chart_summaries: dict | None = None) -> dict:
    rows = [_song_evidence_row(c, chart_summaries, idx + 1) for idx, c in enumerate(charts)]
    rows_by_rating = sorted(rows, key=lambda r: _i(r.get("song_rating")), reverse=True)
    rows_with_gap = sorted([r for r in rows if r.get("gap_vs_peer") is not None], key=lambda r: _f(r.get("gap_vs_peer")), reverse=True)
    b35 = [r for r in rows if r.get("bucket") == "B35"]
    b15 = [r for r in rows if r.get("bucket") == "B15"]
    ds_bands: dict[str, list[dict]] = {}
    for row in rows:
        ds_bands.setdefault(str(row.get("ds_class") or "<13"), []).append(row)

    ds_summary = {
        band: {
            "count": len(items),
            "avg_achievement": round(sum(_f(x.get("achievement")) for x in items if _f(x.get("achievement")) > 0) / len([x for x in items if _f(x.get("achievement")) > 0]), 4) if any(_f(x.get("achievement")) > 0 for x in items) else None,
            "avg_peer_achievement": round(sum(_f(x.get("avg_achievement")) for x in items if x.get("avg_achievement") is not None) / len([x for x in items if x.get("avg_achievement") is not None]), 4) if any(x.get("avg_achievement") is not None for x in items) else None,
            "avg_gap_vs_peer": round(sum(_f(x.get("gap_vs_peer")) for x in items if x.get("gap_vs_peer") is not None) / len([x for x in items if x.get("gap_vs_peer") is not None]), 4) if any(x.get("gap_vs_peer") is not None for x in items) else None,
            "avg_song_rating": round(sum(_f(x.get("song_rating")) for x in items if _f(x.get("song_rating")) > 0) / len([x for x in items if _f(x.get("song_rating")) > 0]), 1) if any(_f(x.get("song_rating")) > 0 for x in items) else None,
        }
        for band, items in ds_bands.items()
    }

    strongest = rows_with_gap[:8]
    weakest = list(reversed(rows_with_gap[-8:]))
    selected = _unique_rows(strongest[:4] + weakest[:4] + rows_by_rating[:4], 10)
    entry_points = _unique_rows(strongest[:6] + weakest[:6], 10)

    return {
        "peer_comparison": {
            "available": bool(peer_data),
            "rating_bucket": peer_data.get("bucket"),
            "matched": peer_data.get("matched", 0),
            "ARPI": peer_data.get("arpi"),
            "b50_overlap": peer_data.get("b50_overlap") or {},
            "rule": "peer_avg/avg_achievement 是同 rating 桶玩家在同一谱同一难度的平均达成率；gap_vs_peer=当前达成率-peer_avg；ARPI 是所有可匹配 B50 谱面的平均 gap。",
        },
        "rating_split": {
            "total": rating,
            "fine_segment": _fine_rating_segment(rating),
            "b35_ra": sum(_i(r.get("song_rating")) for r in b35),
            "b15_ra": sum(_i(r.get("song_rating")) for r in b15),
            "top10_avg_song_rating": round(sum(_f(r.get("song_rating")) for r in rows_by_rating[:10]) / len(rows_by_rating[:10]), 1) if rows_by_rating[:10] else None,
            "bottom10_avg_song_rating": round(sum(_f(r.get("song_rating")) for r in sorted(rows, key=lambda r: _i(r.get("song_rating")))[:10]) / len(sorted(rows, key=lambda r: _i(r.get("song_rating")))[:10]), 1) if rows else None,
        },
        "b35_b15_structure": {
            "rule": "B35 是旧版本/历史 best 35，主要看基本盘、下限、长期结构；B15 是当前版本/new best 15，主要看新版本适应、上限突破、近期推分效率。",
            "b35": _section_summary(b35, "B35"),
            "b15": _section_summary(b15, "B15"),
        },
        "ds_band_summary": ds_summary,
        "config_focus": _build_config_profile(rows, chart_summaries),
        "same_rating_average_entry_points": entry_points,
        "selected_evidence": selected,
        "strongest_vs_peer": strongest,
        "weakest_vs_peer": weakest,
        "abnormal_peer_gaps": [r for r in rows_with_gap if str(r.get("gap_tier") or "").startswith("异常")][:8],
        "highest_song_rating": rows_by_rating[:8],
        "b50_floor": sorted(rows, key=lambda r: _i(r.get("song_rating")))[:8],
        "theory_cards": [r for r in rows_by_rating if r.get("is_theory")][:8],
        "impossible_15_theory": [r for r in rows_by_rating if _f(r.get("ds")) >= 15.0 and r.get("is_theory")][:4],
        "high_ds_ap": [r for r in rows_by_rating if r.get("is_ap") and _f(r.get("ds")) >= 14.0][:8],
        "level_14_plus_ap": [r for r in rows_by_rating if r.get("is_ap") and _f(r.get("ds")) >= 14.6][:6],
        "mid_ds_high_gap": [r for r in rows_by_rating if 13.0 <= _f(r.get("ds")) < 14.6 and _f(r.get("gap_vs_peer")) >= 0.25][:8],
    }


def _calc_ra(ds: float, achievement: float) -> int:
    if achievement < 50:
        base = 7.0
    elif achievement < 60:
        base = 8.0
    elif achievement < 70:
        base = 9.6
    elif achievement < 75:
        base = 11.2
    elif achievement < 80:
        base = 12.0
    elif achievement < 90:
        base = 13.6
    elif achievement < 94:
        base = 15.2
    elif achievement < 97:
        base = 16.8
    elif achievement < 98:
        base = 20.0
    elif achievement < 99:
        base = 20.3
    elif achievement < 99.5:
        base = 20.8
    elif achievement < 100:
        base = 21.1
    elif achievement < 100.5:
        base = 21.6
    else:
        base = 22.4
    return int(ds * (min(100.5, achievement) / 100) * base)


def _build_push_candidates(all_charts: list[dict]) -> list[dict]:
    b35 = [c for c in all_charts if c.get("bucket") == "B35"]
    b35_ds_list = sorted([_f(c.get("ds")) for c in b35])
    b35_ras = sorted([_i(c.get("ra")) for c in b35], reverse=True)
    ds_floor = b35_ds_list[0] if b35_ds_list else 0
    ds_ceil = b35_ds_list[-1] if b35_ds_list else 0
    b35_floor = b35_ras[-1] if b35_ras else 0
    b15_ras = sorted([_i(c.get("ra")) for c in all_charts if c.get("bucket") == "B15"], reverse=True)
    b15_floor = b15_ras[-1] if b15_ras else 0

    DS_ABOVE_BUFFER = 0.4
    candidates: list[dict] = []
    for c in all_charts:
        ach = _f(c.get("achievement"))
        if ach >= 100.5:
            continue
        ds = _f(c.get("ds"))
        if ds < ds_floor or ds > ds_ceil + DS_ABOVE_BUFFER:
            continue
        bucket = c.get("bucket")
        if bucket:
            current_ra = _i(c.get("ra"))
        else:
            current_ra = min(b35_floor, b15_floor)
        gain_1005 = max(0, _calc_ra(ds, 100.5) - current_ra)
        gain_100 = max(0, _calc_ra(ds, 100.0) - current_ra)
        if ach >= 100.0:
            target_gain = gain_1005
            target_label = "SSS+"
        else:
            if gain_100 >= 2:
                target_gain = gain_100
                target_label = "SSS"
            else:
                target_gain = gain_1005
                target_label = "SSS+"
        if target_gain < 2:
            continue
        candidates.append({
            "song_id": _i(c.get("song_id") or c.get("music_id")),
            "title": str(c.get("title") or ""),
            "level_index": _i(c.get("level_index"), -1),
            "level_label": str(c.get("level_label") or ""),
            "level": str(c.get("level") or ""),
            "ds": round(ds, 1),
            "achievements": round(ach, 4),
            "ra": _i(c.get("ra")),
            "fc": str(c.get("fc") or c.get("fc_label") or "").lower(),
            "type": str(c.get("type") or "SD").upper(),
            "gain_100": 0,
            "gain_1005": target_gain,
            "target": target_label,
        })
    if not candidates:
        return []
    random.shuffle(candidates)
    result = [_normalize(c) for c in candidates[:15]]
    return result


def _enrich_push_candidates(
    candidates: list[dict],
    chart_summaries: dict | None = None,
    b50_rows: list[dict] | None = None,
) -> list[dict]:
    summary_map = chart_summaries or {}
    b50_map: dict[tuple[str, int], dict] = {}
    for row in b50_rows or []:
        key = (str(row.get("music_id") or row.get("song_id") or ""), _i(row.get("level_index"), -1))
        if key[0]:
            b50_map[key] = row

    enriched: list[dict] = []
    for song in candidates:
        item = dict(song)
        mid = str(item.get("music_id") or item.get("song_id") or "")
        level_index = _i(item.get("level_index"), -1)
        row = b50_map.get((mid, level_index), {})
        summary = summary_map.get(mid) or {}
        achievement = _f(item.get("achievements") or item.get("achievement"))
        item["music_id"] = mid
        item["achievement"] = round(achievement, 4)
        item["achievements"] = round(achievement, 4)
        item["ra"] = _i(item.get("ra") or row.get("ra"))
        item["bucket"] = row.get("bucket") or item.get("bucket") or "候选"
        item["chart_type"] = row.get("type") or row.get("chart_type") or item.get("type") or ""
        item["type"] = row.get("type") or item.get("type") or ""
        item["level_index"] = level_index
        item["level_label"] = str(item.get("level_label") or row.get("level_label") or "")
        item["fc_label"] = str(row.get("fc_label") or item.get("fc_label") or FC_LABEL_MAP.get(str(item.get("fc") or "").lower(), ""))
        item["play_count"] = _i(row.get("play_count") or row.get("playCount") or item.get("play_count") or item.get("playCount"))
        item["peer_avg"] = row.get("peer_avg")
        item["gap"] = row.get("gap")
        item["gap_vs_peer"] = row.get("gap") if row.get("gap") is not None else row.get("gap_vs_peer")
        item["overlap"] = row.get("overlap")
        item["config_tags"] = [str(x).strip() for x in (summary.get("config_tags") or row.get("config_tags") or []) if str(x).strip()][:6]
        item["keywords"] = item.get("config_tags")[:]
        item["chart_identity"] = str(summary.get("chart_identity") or summary.get("community_vibe") or "")[:120]
        item["source"] = "b50_push"
        enriched.append(item)

    enriched.sort(
        key=lambda x: (
            -max(_i(x.get("gain_1005"), 0), _i(x.get("gain_100"), 0)),
            -len(x.get("config_tags") or []),
            abs(99.5 - _f(x.get("achievement"), 0.0)),
            -_i(x.get("play_count"), 0),
            str(x.get("title") or ""),
        )
    )
    return enriched[:15]


def _build_config_profile(rows: list[dict], chart_summaries: dict | None = None) -> dict:
    """全量谱面配置强弱分析（偏科检测）：按 config_tags 聚合，识别强项和短板。"""
    groups: dict[str, list[dict]] = {}
    for row in rows:
        mid = str(row.get("music_id") or "")
        summary = (chart_summaries or {}).get(mid) or {}
        tags = [str(x).strip() for x in (summary.get("config_tags") or row.get("config_tags") or [])[:6]]
        for tag in tags:
            if not tag:
                continue
            groups.setdefault(tag, []).append(row)

    strong: list[dict] = []
    weak: list[dict] = []
    neutral: list[dict] = []
    for tag, items in groups.items():
        if len(items) < 2:
            continue
        ach_vals = [_f(x.get("achievement")) for x in items if _f(x.get("achievement")) > 0]
        if not ach_vals:
            continue
        avg_ach = sum(ach_vals) / len(ach_vals)
        gap_vals = [_f(x.get("gap")) for x in items if x.get("gap") is not None]
        avg_gap = sum(gap_vals) / len(gap_vals) if gap_vals else None
        entry = {"tag": tag, "count": len(items), "avg_achievement": round(avg_ach, 4)}
        if avg_gap is not None:
            entry["avg_gap_vs_peer"] = round(avg_gap, 4)
        if avg_ach >= 100.3:
            strong.append(entry)
        elif avg_ach < 100.0:
            weak.append(entry)
        else:
            neutral.append(entry)

    strong.sort(key=lambda x: (-x["avg_achievement"], -x["count"]))
    weak.sort(key=lambda x: (x["avg_achievement"], -x["count"]))
    neutral.sort(key=lambda x: (-x["count"], -x["avg_achievement"]))
    return {"strong": strong[:5], "weak": weak[:5], "neutral": neutral[:5]}

def _load_assets_context(assets_path: str) -> dict:
    if not assets_path:
        return {}
    assets = Path(assets_path)
    kb = _load_json(assets, "kb", "mai_knowledge.json") or {}
    roast = _load_json(assets, "kb", "roast_memory.json") or {}
    chart_summary = _load_json(assets, "chart_summary.json") or {}
    music_data = _load_json(assets, "music_data.json") or {}
    return {
        "kb": kb,
        "roast_memory": roast,
        "chart_summary": chart_summary,
        "music_data": music_data,
    }


def build_context(b50_data: dict, peer_stats: dict | None = None) -> dict:
    player = {
        "nickname": b50_data.get("nickname") or b50_data.get("username") or "maimai player",
        "username": b50_data.get("username") or "",
        "rating": _i(b50_data.get("rating")),
        "qq": str(b50_data.get("qq") or ""),
    }

    sd_charts = [_normalize(c) for c in ((b50_data.get("charts") or {}).get("sd") or [])]
    dx_charts = [_normalize(c) for c in ((b50_data.get("charts") or {}).get("dx") or [])]
    if not sd_charts and not dx_charts:
        return {"player": player, "peer_stats": {}, "summary": {}, "evidence": {}, "b50": [], **_load_assets_context(str(b50_data.get("_assets_path") or ""))}
    for c in sd_charts[:35]:
        c["bucket"] = "B35"
    for c in dx_charts[:15]:
        c["bucket"] = "B15"
    all_charts = sd_charts + dx_charts

    assets_ctx = _load_assets_context(str(b50_data.get("_assets_path") or ""))

    b50 = [c for c in all_charts if c.get("bucket") in ("B35", "B15")]
    b35 = [c for c in b50 if c.get("bucket") == "B35"]
    b15 = [c for c in b50 if c.get("bucket") == "B15"]
    b35_ra = sum(_i(c.get("ra")) for c in b35)
    b15_ra = sum(_i(c.get("ra")) for c in b15)
    avg_ach = sum(c["achievement"] for c in b50) / len(b50) if b50 else 0.0
    avg_ds = sum(_f(c.get("ds")) for c in b50) / len(b50) if b50 else 0.0
    b35_avg = sum(c["achievement"] for c in b35) / len(b35) if b35 else 0.0
    b15_avg = sum(c["achievement"] for c in b15) / len(b15) if b15 else 0.0

    peer_data: dict = {}
    if peer_stats:
        rating = player["rating"]
        sz = _i(peer_stats.get("rating_bucket_size"), 200)
        lo = (rating // sz) * sz
        bucket = (peer_stats.get("buckets") or {}).get(f"{lo}-{lo + sz - 1}") or {}
        chart_stats = bucket.get("charts") or {}
        if chart_stats:
            gaps, overlaps = [], []
            for c in b50:
                key = f"{c['music_id']}:{_i(c.get('level_index'), -1)}"
                stat = chart_stats.get(key)
                if stat:
                    avg = _f(stat.get("avg_achievement"))
                    gap = c["achievement"] - avg
                    appear = _f(stat.get("b50_appear_rate"))
                    if appear <= 1:
                        appear *= 100
                    c["peer_avg"] = avg
                    c["gap"] = gap
                    c["overlap"] = appear
                    gaps.append(gap)
                    overlaps.append(appear)
            if gaps:
                peer_data = {
                    "available": True,
                    "bucket": f"{lo}-{lo + sz - 1}",
                    "matched": len(gaps),
                    "arpi": round(sum(gaps) / len(gaps), 4),
                    "b50_overlap": {"value": round(sum(overlaps) / len(overlaps), 2)},
                }

    with_gap = [c for c in b50 if c.get("gap") is not None]
    highlights = sorted(with_gap, key=lambda c: c.get("gap", 0), reverse=True)[:4]
    ordinaries = sorted(with_gap, key=lambda c: c.get("gap", 0))[:2]
    highest_ra = sorted(b50, key=lambda c: _i(c.get("ra")), reverse=True)[:1]
    overlap_extremes: list[dict] = []
    if with_gap:
        hi = max(with_gap, key=lambda c: c.get("overlap", 0))
        lo_c = min(with_gap, key=lambda c: c.get("overlap", 100))
        overlap_extremes = [hi, lo_c] if hi is not lo_c else [hi]

    summary = {
        "b35_ra": b35_ra,
        "b15_ra": b15_ra,
        "avg_achievement": round(avg_ach, 4),
        "avg_ds": round(avg_ds, 2),
        "b35": {"avg_achievement": round(b35_avg, 4)},
        "b15": {"avg_achievement": round(b15_avg, 4)},
    }
    if peer_data.get("arpi") is not None and with_gap:
        summary["avg_peer"] = round(
            sum(c.get("peer_avg", 0) for c in with_gap) / len(with_gap), 4
        )
        summary["avg_gap"] = round(
            sum(c.get("gap", 0) for c in with_gap) / len(with_gap), 4
        )

    chart_summaries = assets_ctx.get("chart_summary") or {}
    evidence_pack = _build_b50_evidence_pack(b50, player["rating"], peer_data, chart_summaries)
    config_focus = evidence_pack.get("config_focus") or {}
    push_candidates = _enrich_push_candidates(
        _build_push_candidates(all_charts),
        chart_summaries,
        all_charts,
    )

    return {
        "player": player,
        "peer_stats": peer_data,
        "summary": summary,
        "evidence": {
            "highlights": highlights,
            "ordinaries": ordinaries,
            "highest_song_rating": highest_ra,
            "overlap_extremes": overlap_extremes,
            "push_recommendations": push_candidates,
            "same_rating_average_entry_points": evidence_pack.get("same_rating_average_entry_points", []),
            "selected_evidence": evidence_pack.get("selected_evidence", []),
        },
        "b50_evidence_pack": evidence_pack,
        "config_focus": config_focus,
        "push_candidates": push_candidates,
        "b50": all_charts,
        "chart_summaries": chart_summaries,
        **assets_ctx,
    }
