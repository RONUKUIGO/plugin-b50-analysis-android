# -*- coding: utf-8 -*-
"""App 内嵌 Python 全流程 worker（由 Kotlin 通过 ProcessBuilder 调用）。

入参（位置参数）：
    <records.json>   水鱼 /player/records 的原始响应（Kotlin 已取好）
    <music_data.json> 曲库
    <assets_dir>      资源目录（fonts / ui / covers）
    <out.png>         输出图片路径
    <width>           输出宽度（900/1200/1500）
    [qq]              用于头像（可空）
    [versions]        当前版本（用 | 分隔，由 Kotlin 校准；空则用曲库 is_new 兜底）
    [analysis]        锐评正文（可空）

流程：normalize_records → build_context → attach_full_context → add_extras → render_image
这是与桌面版完全相同的调用链，所以出图效果一致。

锐评模式（环境变量 B50_PROMPT_OUT 有值时）：建完 context 就只把提示词写到该文件然后退出，
不发 HTTP、不渲染。HTTP 由 Kotlin 侧的 LlmClient 发（系统 CA / 超时 / 错误提示都更可控），
拿到正文后 Kotlin 再启动一次本脚本、把正文塞进 analysis 参数正式出图。

    环境变量：
      B50_PROMPT_OUT  提示词输出 JSON 路径（有值即进入锐评模式）
      B50_TONE        语气/人设
      B50_ANGLE       分析角度
      B50_WORDS       字数（"600" 或 "400-800"，空则用默认 150~300）
"""
import json
import os
import sys
import types
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# ---- httpx stub：fetch.py / render.py 顶层 import 它，但 App 内全部走本地，不需要网络 ----
if "httpx" not in sys.modules:
    _stub = types.ModuleType("httpx")

    class _HTTPError(Exception):
        pass

    class _Client:
        def __init__(self, *a, **k):
            pass

        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def get(self, *a, **k):
            raise _HTTPError("httpx disabled in app")

        post = get

    _stub.Client = _Client
    _stub.AsyncClient = _Client
    _stub.HTTPError = _HTTPError
    sys.modules["httpx"] = _stub


def attach_full_context(context, data, music_data, notes):
    """与 b50.py 同名函数一致：把全量才有的统计挂到 context 上。"""
    import profile

    all_charts = context.get("b50") or []
    ranked = [c for c in all_charts if c.get("bucket") in ("B35", "B15")]
    if ranked:
        context["b50"] = ranked
    records = data.get("full_records") or []
    context["full_records"] = records
    context["records_total"] = int(data.get("records_total") or len(records))
    try:
        context["full_stats"] = profile.full_stats(records, music_data)
    except Exception:
        context["full_stats"] = {}
    try:
        context["near_b50"] = profile.near_b50(records, context.get("b50") or [])
    except Exception:
        context["near_b50"] = []
    try:
        context["unplayed"] = profile.unplayed_charts(records, music_data)
    except Exception:
        context["unplayed"] = []


def add_extras(context):
    """与 b50.py 同名函数一致：补齐渲染器会读、context_builder 不生成的分区。"""
    import profile

    b50 = context.get("b50") or []
    evidence = context.setdefault("evidence", {})
    evidence.setdefault("highest_song_rating", [])
    played = [c for c in b50 if int(c.get("play_count") or 0) > 0]
    if played and not evidence.get("least_played"):
        evidence["least_played"] = sorted(played, key=lambda c: int(c.get("play_count") or 0))[:2]
    cs = context.get("chart_summaries") or {}
    try:
        prof = profile.config_profile(context)
    except Exception:
        prof = {}
    strong_tags = {x["tag"] for x in (prof.get("strong") or [])}
    if strong_tags and not evidence.get("config_specialized"):
        hit = [c for c in b50 if strong_tags & set(profile.tags_of(c, cs))]
        hit.sort(key=lambda c: -float(c.get("achievement") or 0))
        if hit:
            evidence["config_specialized"] = hit[:2]


def emit_prompt(context, tone, angle, words_spec, out_path):
    """只生成提示词，不渲染也不联网。Kotlin 拿去调 LLM，拿到正文后再回来真正渲染。"""
    import roast

    words = roast.parse_words(words_spec)
    payload = {
        "system": roast.build_system_prompt(words),
        "user": roast.build_user_prompt(context, tone, angle, words),
        "words": list(words),
    }
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False)
    print("PROMPT %s system=%d user=%d words=%s" % (
        out_path, len(payload["system"]), len(payload["user"]), words))


def main():
    if len(sys.argv) < 5:
        print("ERROR usage: render_worker.py <records.json> <music_data.json> <assets> <out.png> [width] [qq] [versions] [analysis]")
        return 2

    rec_path, music_path, assets, out_path = sys.argv[1:5]
    width = int(sys.argv[5]) if len(sys.argv) > 5 else 1200
    qq = sys.argv[6] if len(sys.argv) > 6 else ""
    versions_arg = sys.argv[7] if len(sys.argv) > 7 else ""
    analysis = sys.argv[8] if len(sys.argv) > 8 else ""

    try:
        import fetch
        import profile  # noqa: F401  (add_extras 里用)
        from vendor.mai.context_builder import build_context, load_peer_stats
        from vendor.mai import render

        records = json.load(open(rec_path, encoding="utf-8"))
        music = json.load(open(music_path, encoding="utf-8"))

        lookup = fetch.build_lookup(music)
        if versions_arg:
            versions = set(v for v in versions_arg.split("|") if v)
        else:
            det = fetch.detect_current_versions(None, lookup)
            versions = det["versions"]
        print("INFO versions=" + "、".join(sorted(versions)))

        data = fetch.normalize_records(records, music, versions)
        print("INFO records=%d 自算=%s 接口=%s" % (data.get("records_total") or 0,
                                                 data.get("rating_selfcalc"), data.get("rating")))

        data["_assets_path"] = assets
        peer = load_peer_stats(assets)
        context = build_context(data, peer)
        context["player"]["qq"] = qq
        context["mode_used"] = "full"
        notes = []
        attach_full_context(context, data, music, notes)
        add_extras(context)
        ev = context.get("evidence") or {}
        print("INFO evidence=" + json.dumps({k: len(v or []) for k, v in ev.items()}, ensure_ascii=False))

        # 锐评模式：只吐提示词就退出
        prompt_out = (os.environ.get("B50_PROMPT_OUT") or "").strip()
        if prompt_out:
            emit_prompt(
                context,
                os.environ.get("B50_TONE") or "",
                os.environ.get("B50_ANGLE") or "综合锐评",
                os.environ.get("B50_WORDS") or "",
                prompt_out,
            )
            return 0

        render.CACHE_COVER = False
        render.CACHE_AVATAR = False
        render.OUT_WIDTH = width
        img = render.render_image(context, analysis, assets)
        os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
        img.save(out_path)
        print("OK %s %dx%d" % (out_path, img.width, img.height))
        return 0
    except Exception:
        print("ERROR " + traceback.format_exc().replace("\n", " | ")[:1200])
        return 4


if __name__ == "__main__":
    sys.exit(main())
