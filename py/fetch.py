"""水鱼查分器（diving-fish）数据拉取 + 规整。

对外只暴露三个函数：
    fetch_b50(qq=..., username=..., token=...)  -> 原始 B50 数据
    load_music_data(cache_file)                 -> 曲库（带本地缓存）
    normalize_b50(payload, music_data)          -> 补齐 title/ds/type 的 B50 数据
"""
from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

import httpx

BASE = "https://www.diving-fish.com/api/maimaidxprober"
# 水鱼没有严格白名单，但带上浏览器 UA 更稳
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 b50-local/1.0",
    "Content-Type": "application/json",
}
TIMEOUT = 30.0
MUSIC_CACHE_AGE = 7 * 24 * 3600  # 曲库缓存 7 天


class FishError(RuntimeError):
    """水鱼接口的业务错误，消息是可以直接给用户看的中文提示。"""


# ---------------------------------------------------------------- B50 数据
def fetch_b50(qq: str | None = None, username: str | None = None, token: str = "") -> dict:
    """调用 POST /query/player 获取 B50。

    qq:        QQ 号（字符串/整数都行），优先使用
    username:  水鱼用户名（QQ 查询失败时的备选）
    token:     水鱼开发者 token，可选
    """
    body: dict[str, Any] = {"b50": True}
    if qq:
        body["qq"] = int(str(qq).strip())
    elif username:
        body["username"] = str(username).strip()
    else:
        raise ValueError("必须提供 QQ 号或水鱼 username")

    headers = dict(HEADERS)
    if token:
        headers["developer-token"] = token  # 有 token 可以查别人

    try:
        with httpx.Client(timeout=TIMEOUT, headers=headers, follow_redirects=True) as client:
            resp = client.post(f"{BASE}/query/player", json=body)
    except httpx.HTTPError as exc:
        raise FishError(f"连不上水鱼查分器：{exc}") from exc

    if resp.status_code == 403:
        raise FishError(
            "该账号未开放公开查询。\n"
            "→ 手机浏览器打开 https://www.diving-fish.com/maimaidx/prober/ ，登录后\n"
            "   1) 同意用户协议  2) 把「允许他人查询我的成绩」打开，再重试。"
        )
    if resp.status_code == 400:
        raise FishError("水鱼查不到这个账号：QQ 号 / username 写错了，或者还没导入过成绩。")
    if resp.status_code == 429:
        raise FishError("请求太频繁了，等 1 分钟再试。")
    try:
        resp.raise_for_status()
    except httpx.HTTPStatusError as exc:
        raise FishError(f"水鱼返回异常状态 HTTP {resp.status_code}：{resp.text[:120]}") from exc

    data = resp.json()
    if not data.get("charts"):
        raise FishError("水鱼返回的数据里没有 B50，请确认成绩已导入。")
    return data


# ---------------------------------------------------------------- 曲库
def load_music_data(cache_file: Path, refresh: bool = False) -> list[dict]:
    """获取曲库，优先用本地缓存（默认 7 天有效）。"""
    cache_file = Path(cache_file)
    if not refresh and cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < MUSIC_CACHE_AGE:
        try:
            return json.loads(cache_file.read_text(encoding="utf-8"))
        except Exception:
            pass
    try:
        with httpx.Client(timeout=TIMEOUT, headers=HEADERS) as client:
            resp = client.get(f"{BASE}/music_data")
        resp.raise_for_status()
        data = resp.json()
    except Exception:
        # 网络失败时退回旧缓存（哪怕过期），没有就返回空列表
        if cache_file.exists():
            try:
                return json.loads(cache_file.read_text(encoding="utf-8"))
            except Exception:
                pass
        return []
    cache_file.parent.mkdir(parents=True, exist_ok=True)
    cache_file.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return data


def build_lookup(music_data: list[dict]) -> dict[str, dict]:
    return {str(m.get("id")): m for m in (music_data or []) if m.get("id") is not None}


# ---------------------------------------------------------------- 规整
def _pick_song_info(music_id: str, lookup: dict[str, dict]) -> dict | None:
    """在曲库里找歌曲信息；DX 曲（10xxx/11xxx）找不到时退回同名 SD 曲。"""
    if music_id in lookup:
        return lookup[music_id]
    try:
        num = int(music_id)
    except (TypeError, ValueError):
        return None
    if num >= 10000:
        return lookup.get(str(num - 10000))
    if num < 10000:
        return lookup.get(str(num + 10000))
    return None


def _enrich_chart(chart: dict, lookup: dict[str, dict]) -> dict:
    c = dict(chart)
    mid = str(c.get("song_id") or c.get("music_id") or c.get("id") or "")
    c["music_id"] = mid
    info = _pick_song_info(mid, lookup) or {}
    level_index = int(c.get("level_index") or 0)

    # 标题 / 定数 / 类型：接口没给就用曲库补
    if not c.get("title"):
        c["title"] = info.get("title") or f"Song#{mid}"
    if c.get("ds") is None:
        ds_list = info.get("ds") or []
        c["ds"] = ds_list[level_index] if len(ds_list) > level_index else 0.0
    if not c.get("type"):
        c["type"] = info.get("type") or ""

    if c.get("achievement") is None:
        c["achievement"] = c.get("achievements")
    # 达成率换算：接口给的是百分数（如 100.1234）
    ach = float(c.get("achievement") or 0)
    c["achievement"] = ach
    c["ra"] = int(c.get("ra") or 0)
    c["level_index"] = level_index
    c["play_count"] = int(c.get("play_count") or c.get("pc") or 0)
    fc = str(c.get("fc") or "").lower()
    c["fc_label"] = {"fc": "FC", "fcp": "FC+", "ap": "AP", "app": "AP+"}.get(fc, "")
    return c


def normalize_b50(payload: dict, music_data: list[dict]) -> dict:
    """把水鱼返回的原始数据补齐成绘图/分析需要的样子。"""
    lookup = build_lookup(music_data)
    charts = payload.get("charts") or {}
    sd = [_enrich_chart(c, lookup) for c in (charts.get("sd") or [])]
    dx = [_enrich_chart(c, lookup) for c in (charts.get("dx") or [])]
    sd.sort(key=lambda c: c.get("ra", 0), reverse=True)
    dx.sort(key=lambda c: c.get("ra", 0), reverse=True)

    total_ra = sum(c["ra"] for c in sd[:35]) + sum(c["ra"] for c in dx[:15])
    rating = int(payload.get("rating") or total_ra or 0)
    return {
        "nickname": str(payload.get("nickname") or payload.get("username") or "maimai player"),
        "username": str(payload.get("username") or ""),
        "rating": rating,
        "charts": {"sd": sd, "dx": dx},
        "additional_rating": payload.get("additional_rating") or {},
        "plate": payload.get("plate") or "",
        "mode": "quick",
    }


# ================================================================ 全量记录（full 模式）
#
# 只有 --mode full 才走这一段：数据来自 GET /player/records（OAuth Bearer 令牌），
# 令牌由 oauth.access_token() 负责，本文件只管「网络 + 规整」，不碰授权细节。
#
# 与 quick 模式的结构差别只有一处：
#   quick：charts.sd = 水鱼给好的 B35（35 首），charts.dx = 水鱼给好的 B15（15 首）
#   full ：charts.sd / charts.dx 里塞**全量**（旧版本池 / 当前版本池，不截断），
#          context_builder 取前 35 / 前 15 当 B50，剩下的自动进推分候选池。

DIFF_LABEL = ("Basic", "Advanced", "Expert", "Master", "Re:Master")

# 拿不到公开 B50、曲库里也没有 is_new 时的最后退路（换版本时手动往上加一行）
FALLBACK_CURRENT_VERSIONS = ("maimai でらっくす PRiSM PLUS",)


class TokenExpired(FishError):
    """401：令牌失效。调用方可以 oauth.access_token(force=True) 换一张重试一次。"""


def fetch_full_records(token: str, params: dict | None = None) -> dict:
    """GET /api/maimaidxprober/player/records（Authorization: Bearer）。

    token:  由 oauth.access_token() 拿到，5 分钟有效
    params: 可选的服务端过滤，如 {"level_index": 3, "ds": "13.5.."}
    返回:   {"username","nickname","rating","additional_rating","plate","records":[...]}

    注意：这个端点不需要也不接受 qq / username 参数，查谁完全由令牌决定。
    """
    if not token:
        raise FishError("全量成绩需要 OAuth 访问令牌。先运行 `python3 oauth_login.py` 绑定一次。")

    headers = dict(HEADERS)
    headers.pop("Content-Type", None)          # GET 不需要
    headers["Authorization"] = f"Bearer {token}"
    try:
        with httpx.Client(timeout=60, headers=headers, follow_redirects=True) as client:
            resp = client.get(f"{BASE}/player/records", params=params or {})
    except httpx.HTTPError as exc:
        raise FishError(f"连不上水鱼全量成绩接口：{exc}") from exc

    if resp.status_code == 401:
        raise TokenExpired("访问令牌失效（401），换一张令牌重试。")
    if resp.status_code == 403:
        raise FishError(
            "这个账号没有授权本应用读取成绩（403）。\n"
            "→ 运行 `python3 oauth_login.py` 完成一次设备码绑定"
        )
    if resp.status_code == 404:
        raise FishError("水鱼查不到这个账号的成绩（404），确认账号里导入过成绩。")
    if resp.status_code == 410:
        raise FishError("该端点已废弃（410）。老 Developer-Token 接口 2027-01-01 起全部下架，"
                        "本项目用的是 OAuth /player/records，看到这条说明配置指到了旧端点。")
    if resp.status_code == 429:
        raise FishError("今日全量查询配额用尽（每应用对每用户 200 次/日），明天再来。")
    if resp.status_code != 200:
        raise FishError(f"拉全量成绩失败 HTTP {resp.status_code}：{resp.text[:160]}")

    try:
        data = resp.json()
    except Exception as exc:  # noqa: BLE001
        raise FishError(f"水鱼返回的不是 JSON：{resp.text[:160]}") from exc
    if not isinstance(data.get("records"), list):
        raise FishError(f"水鱼返回里没有 records 字段：{str(data)[:160]}")
    return data


def music_version(music_id: Any, lookup: dict[str, dict]) -> str:
    """查一张谱的版本文案（曲库 basic_info.from）。DX 曲找不到时自动退回同名 SD 曲。"""
    info = _pick_song_info(str(music_id or ""), lookup) or {}
    return str((info.get("basic_info") or {}).get("from") or info.get("from") or "")


def detect_current_versions(
    b50_payload: dict | None = None,
    lookup: dict[str, dict] | None = None,
    min_share: float = 0.5,
) -> dict:
    """判断「当前版本」的版本文案集合（B50 里的 B15 只收当前版本的谱）。

    三级策略，零维护优先：
      1. 反向校准（推荐）：公开 B50 的 charts.dx 一定是当前版本的 15 首，
         拿它们的 basic_info.from 投票 —— 水鱼换版本时自动跟上，不用改代码；
      2. 曲库兜底：music_data 里 basic_info.is_new == true 的曲目即当前版本新曲；
      3. 手写常量：FALLBACK_CURRENT_VERSIONS（最后退路，换版本要手动改）。

    返回 {"versions": set[str], "source": "b50|is_new|fallback", "counts": {...}, "sample": n}
    """
    lookup = lookup or {}

    counts: dict[str, int] = {}
    dx_charts = ((b50_payload or {}).get("charts") or {}).get("dx") or []
    for chart in dx_charts:
        ver = music_version(chart.get("song_id") or chart.get("music_id") or "", lookup)
        if ver:
            counts[ver] = counts.get(ver, 0) + 1
    total = sum(counts.values())
    if total:
        # 取票数最多那一版；其余只要票数不小于它的一半也算「当前版本」
        # （改版过渡期 B15 里可能混着上一版的歌，这样能一起收进来）
        top = max(counts.values())
        versions = {v for v, n in counts.items() if n * 2 >= top}
        return {"versions": versions, "source": "b50", "counts": counts, "sample": total}

    is_new: dict[str, int] = {}
    for mid, info in lookup.items():
        if (info.get("basic_info") or {}).get("is_new"):
            ver = music_version(mid, lookup)
            if ver:
                is_new[ver] = is_new.get(ver, 0) + 1
    if is_new:
        return {"versions": set(is_new), "source": "is_new", "counts": is_new,
                "sample": sum(is_new.values())}

    return {"versions": set(FALLBACK_CURRENT_VERSIONS), "source": "fallback",
            "counts": {}, "sample": 0}


def split_by_version(
    charts: list[dict],
    current_versions: set[str] | None,
    lookup: dict[str, dict],
) -> tuple[list[dict], list[dict]]:
    """按版本分成两池：旧版本池（B35 候选） / 当前版本池（B15 候选），各自按 RA 降序。"""
    versions = set(current_versions or ())
    current: list[dict] = []
    legacy: list[dict] = []
    for c in charts:
        ver = c.get("version") or music_version(c.get("music_id") or c.get("song_id") or "", lookup)
        (current if ver in versions else legacy).append(c)
    # 同 RA 时按「定数高 → 达成率高」再排，这和官方榜的取舍一致
    # （实测：B15 榜底若出现 RA 并列，官方优先放定数更高的那首）
    def _order(c: dict) -> tuple[int, float, float]:
        return (int(c.get("ra") or 0), float(c.get("ds") or 0), float(c.get("achievement") or 0))

    current.sort(key=_order, reverse=True)
    legacy.sort(key=_order, reverse=True)
    return legacy, current


def rating_total_of(legacy: list[dict], current: list[dict],
                    top_legacy: int = 35, top_current: int = 15) -> int:
    """按「旧版本前 35 + 当前版本前 15」自算单曲 RA 总和（用来和接口给的 rating 对账）。"""
    return (sum(int(c.get("ra") or 0) for c in legacy[:top_legacy])
            + sum(int(c.get("ra") or 0) for c in current[:top_current]))


def normalize_records(
    payload: dict,
    music_data: list[dict],
    current_versions: set[str] | None = None,
) -> dict:
    """把 /player/records 的全量记录规整成 normalize_b50 的同款结构。

    - charts.sd = 旧版本池（全量，RA 降序）   → context_builder 取前 35 当 B35
    - charts.dx = 当前版本池（全量，RA 降序） → 取前 15 当 B15
    - full_records = 所有谱面按 RA 降序的副本，供全量统计 / 差一点进 B50 使用
    """
    lookup = build_lookup(music_data)
    versions = set(current_versions or ())

    charts: list[dict] = []
    for raw in payload.get("records") or []:
        c = _enrich_chart(raw, lookup)
        li = int(c.get("level_index") or 0)
        if not c.get("level_label"):
            c["level_label"] = DIFF_LABEL[li] if 0 <= li < len(DIFF_LABEL) else ""
        if not c.get("level"):
            info = _pick_song_info(str(c.get("music_id")), lookup) or {}
            levels = info.get("level") or []
            c["level"] = str(levels[li]) if len(levels) > li else ""
        c["version"] = music_version(c.get("music_id"), lookup)
        charts.append(c)

    legacy, current = split_by_version(charts, versions, lookup)
    self_rating = rating_total_of(legacy, current)

    return {
        "nickname": str(payload.get("nickname") or payload.get("username") or "maimai player"),
        "username": str(payload.get("username") or ""),
        "rating": int(payload.get("rating") or self_rating or 0),
        "charts": {"sd": legacy, "dx": current},
        "additional_rating": payload.get("additional_rating") or {},
        "plate": payload.get("plate") or "",
        "mode": "full",
        "records_total": len(charts),
        "full_records": sorted(charts, key=lambda c: int(c.get("ra") or 0), reverse=True),
        "current_versions": sorted(versions),
        "rating_selfcalc": self_rating,
    }
