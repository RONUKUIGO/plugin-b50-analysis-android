from __future__ import annotations

import io
import json
import math
import random
import re
from pathlib import Path
from typing import Any

import httpx

from .paths import DATA_DIR

try:
    from PIL import Image, ImageDraw, ImageFont
    _PIL_OK = True
except ImportError:
    _PIL_OK = False

# ---------------------------------------------------------------
# 字体回退：assets 里没有官方字体时，自动使用系统中文字体，
# 保证任何环境下都不会因为缺字体而崩溃。
# ---------------------------------------------------------------
_PROJECT_ROOT = Path(__file__).resolve().parents[2]
_FONT_FALLBACK_CN = (
    # Android（proot / Termux 都能读 /system）
    "/system/fonts/NotoSansCJK-Bold.ttc",
    "/system/fonts/NotoSansCJK-Regular.ttc",
    "/system/fonts/DroidSansFallback.ttf",
    # Linux
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    # Windows
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyhl.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    # macOS
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    # 发布包自带的最小兜底字体（由 tools/make_fallback_font.py 生成）
    str(_PROJECT_ROOT / "fonts" / "fallback-cn.otf"),
    # 实在没有就用 DejaVu（只覆盖拉丁，中文会缺字）
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
)
_font_path_cache: dict[str, str] = {}


def _font_path(font_dir: Path, family: str) -> str | None:
    """返回可用字体文件路径：优先 assets 原字体，其次系统中文字体。"""
    if family in _font_path_cache:
        return _font_path_cache[family] or None
    name = "ResourceHanRoundedCN.otf" if family == "cn" else "Torus SemiBold.otf"
    candidates = [font_dir / name]
    if font_dir.exists():
        kw = ("han", "cn", "cjk", "ryu", "source", "rounded") if family == "cn" else ("torus", "bold")
        for p in sorted(font_dir.glob("*")):
            if p.suffix.lower() in (".otf", ".ttf", ".ttc") and any(k in p.name.lower() for k in kw):
                candidates.append(p)
    for p in candidates:
        try:
            if p.exists():
                _font_path_cache[family] = str(p)
                return str(p)
        except Exception:
            continue
    for s in _FONT_FALLBACK_CN:
        if Path(s).exists():
            _font_path_cache[family] = s
            return s
    _font_path_cache[family] = ""
    return None

CANVAS_W = 1800

# ---------------------------------------------------------------
# 以下三项由 b50.py 在调用前动态设置，用来控制出图结果的形态
OUT_WIDTH = 900                        # 最终输出宽度（内部按 1800 排版后缩放）
SECTIONS_ENABLED: set[str] | None = None   # None = 全部；set = 只画这些 key
CACHE_AVATAR = True                    # 是否下载 QQ 头像
CACHE_COVER = True                     # 是否下载曲绘

DIFF_SHORT = {0: "BAS", 1: "ADV", 2: "EXP", 3: "MAS", 4: "ReM"}
FC_ICON = {
    "FC": "UI_MSS_MBase_Icon_FC.png",
    "FC+": "UI_MSS_MBase_Icon_FCp.png",
    "AP": "UI_MSS_MBase_Icon_AP.png",
    "AP+": "UI_MSS_MBase_Icon_APp.png",
}


BORDER_COLOR = [(46, 125, 50), (249, 168, 37), (229, 57, 53), (156, 39, 176), (156, 100, 220)]
_bg_image = None


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


def _strip(text: str) -> str:
    return re.sub(r"[\U00010000-\U0010ffff]", "", str(text or ""))


def _rank_icon(ach: float) -> str:
    for threshold, name in [
        (100.5, "SSSp"), (100.0, "SSS"), (99.5, "SSp"), (99.0, "SS"),
        (98.0, "Sp"), (97.0, "S"), (94.0, "AAA"), (90.0, "AA"), (80.0, "A"),
    ]:
        if ach >= threshold:
            return f"UI_TTR_Rank_{name}.png"
    return ""


def _ra_pic(rating: int) -> str:
    for threshold, name in [
        (1000, "01"), (2000, "02"), (4000, "03"), (7000, "04"), (10000, "05"),
        (12000, "06"), (13000, "07"), (14000, "08"), (14500, "09"), (15000, "10"),
    ]:
        if rating < threshold:
            return f"UI_CMN_DXRating_{name}.png"
    return "UI_CMN_DXRating_11.png"


def _parse_analysis_result(analysis: Any) -> dict:
    if isinstance(analysis, dict):
        raw = dict(analysis)
    else:
        text = str(analysis or "").strip()
        text = re.sub(r"^```(?:json)?\s*", "", text, flags=re.I)
        text = re.sub(r"\s*```$", "", text, flags=re.I)
        try:
            raw = json.loads(text)
        except Exception:
            m = re.search(r"\{[\s\S]*\}", text)
            if m:
                try:
                    raw = json.loads(m.group(0))
                except Exception:
                    raw = {}
            else:
                raw = {}
        if not raw:
            raw = {"title": "B50锐评", "overall_roast": text, "impression_roast": "", "push_recommendations": []}

    title = _strip(str(raw.get("title") or "")).replace("\r", " ").replace("\n", " ").strip()
    overall = _strip(str(raw.get("overall_roast") or "")).replace("\r", " ").strip()
    impression = _strip(str(raw.get("impression_roast") or "")).replace("\r", " ").strip()
    overall = re.sub(r"\s*\n\s*", " ", overall)
    impression = re.sub(r"\s*\n\s*", " ", impression)
    if not title:
        title = "B50锐评"
    push_recommendations = raw.get("push_recommendations") if isinstance(raw.get("push_recommendations"), list) else []
    return {"title": title, "overall_roast": overall, "impression_roast": impression, "push_recommendations": push_recommendations}


class _Draw:
    def __init__(self, data: dict, screen_title: str, analysis_result: dict, assets: Path) -> None:
        self.data = data
        self.screen_title = _strip(screen_title)
        self.analysis_title = _strip(str(analysis_result.get("title") or ""))
        self.analysis_overall = _strip(str(analysis_result.get("overall_roast") or ""))
        self.analysis_impression = _strip(str(analysis_result.get("impression_roast") or ""))
        self.assets = assets
        self.ui = assets / "ui"
        self.icons = self.ui / "icons"
        self.cover_cache_dir = assets / "cover"
        self.im = Image.new("RGBA", (CANVAS_W, 5800), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)
        self.fonts: dict[str, Any] = {}
        self.covers: dict[str, Any] = {}
        self.avatar: Any = None

    def font(self, family: str, size: int) -> Any:
        key = f"{family}:{size}"
        if key not in self.fonts:
            path = _font_path(self.ui / "fonts", family)
            if path:
                self.fonts[key] = ImageFont.truetype(path, size)
            else:  # 极端兜底：连系统字体都没有
                self.fonts[key] = ImageFont.load_default()
        return self.fonts[key]

    def _ensure_h(self, min_h: int) -> None:
        if min_h <= self.im.height:
            return
        new_h = int(math.ceil(min_h / 200.0) * 200)
        new_im = Image.new("RGBA", (CANVAS_W, new_h), (0, 0, 0, 0))
        new_im.alpha_composite(self.im)
        self.im = new_im
        self.d = ImageDraw.Draw(self.im)

    def rrect(self, xy: tuple, radius: int, fill: Any, outline: Any = None, width: int = 1) -> None:
        x1, y1, x2, y2 = (int(v) for v in xy)
        layer = Image.new("RGBA", (max(1, x2 - x1), max(1, y2 - y1)), (0, 0, 0, 0))
        ImageDraw.Draw(layer).rounded_rectangle((0, 0, x2 - x1, y2 - y1), radius=radius, fill=fill, outline=outline, width=width)
        self.im.alpha_composite(layer, (x1, y1))
        self.d = ImageDraw.Draw(self.im)

    def paste(self, img: Any, xy: tuple) -> None:
        self.im.alpha_composite(img.convert("RGBA"), xy)
        self.d = ImageDraw.Draw(self.im)

    def icon(self, filename: str, size: tuple) -> Any:
        if not filename:
            return None
        path = self.icons / filename
        if not path.exists():
            return None
        try:
            return Image.open(path).convert("RGBA").resize(size, Image.Resampling.LANCZOS)
        except Exception:
            return None

    def wrap(self, text: str, font: Any, max_w: int) -> list[str]:
        lines: list[str] = []
        for raw in str(text or "").replace("\r", "").split("\n"):
            cur = ""
            for ch in raw:
                if font.getbbox(cur + ch)[2] > max_w:
                    if cur:
                        lines.append(cur)
                    cur = ch
                else:
                    cur += ch
            if cur:
                lines.append(cur)
        return lines

    def fit_line(self, text: str, max_w: int, max_sz: int = 28, min_sz: int = 16) -> tuple:
        clean = " ".join(str(text or "").replace("\r", " ").replace("\n", " ").split())
        for sz in range(max_sz, min_sz - 1, -2):
            f = self.font("cn", sz)
            if f.getbbox(clean)[2] <= max_w:
                return f, clean
        f = self.font("cn", min_sz)
        lo, hi = 0, len(clean)
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if f.getbbox(clean[:mid] + "...")[2] <= max_w:
                lo = mid
            else:
                hi = mid - 1
        return f, clean if lo == len(clean) else clean[:lo] + "..."

    def fit_text(self, text: str, max_w: int, max_lines: int, max_sz: int = 28, min_sz: int = 16) -> tuple:
        for sz in range(max_sz, min_sz - 1, -2):
            f = self.font("cn", sz)
            lines = self.wrap(text, f, max_w)
            step = max(sz + 10, int(sz * 1.45))
            if len(lines) <= max_lines:
                return f, lines, step
        f = self.font("cn", min_sz)
        lines = self.wrap(text, f, max_w)[:max_lines]
        if lines and len(self.wrap(text, f, max_w)) > max_lines:
            lines[-1] = lines[-1].rstrip("。.，；") + "..."
        return f, lines, max(min_sz + 10, int(min_sz * 1.45))

    def load_cover(self, song_id: Any, size: int = 120) -> Any:
        sid = str(song_id or "")
        norm = sid.lstrip("0")
        if len(norm) == 5 and norm.startswith("10"):
            norm = norm[2:]
        norm = norm.lstrip("0")
        key = f"{norm}:{size}"
        if key in self.covers:
            return self.covers[key]
        self.cover_cache_dir.mkdir(parents=True, exist_ok=True)
        path = self.cover_cache_dir / f"{norm}.png"
        default = self.ui / "default_cover.png"
        try:
            img = Image.open(path if path.exists() else default).convert("RGBA")
        except Exception:
            img = Image.new("RGBA", (size, size), (200, 200, 200, 255))
        self.covers[key] = img.resize((size, size), Image.Resampling.LANCZOS)
        return self.covers[key]

    def load_avatar(self) -> None:
        qq = str((self.data.get("player") or {}).get("qq") or "")
        if not qq:
            return
        path = self.assets / "avatars" / f"{qq}.png"
        if not path.exists():
            return
        try:
            self.avatar = Image.open(path).resize((140, 140)).convert("RGBA")
        except Exception:
            self.avatar = None

    def draw_header(self) -> None:
        player = self.data.get("player") or {}
        peer = self.data.get("peer_stats") or {}
        summary = self.data.get("summary") or {}
        rating = _i(player.get("rating"))

        self.rrect((40, 30, 620, 420), 16, (245, 248, 255, 230))
        if self.avatar:
            mask = Image.new("L", (140, 140), 0)
            ImageDraw.Draw(mask).ellipse((0, 0, 140, 140), fill=255)
            circle = Image.new("RGBA", (140, 140), (0, 0, 0, 0))
            circle.paste(self.avatar, (0, 0), mask)
            self.paste(circle, (80, 50))
        nick = str(player.get("nickname") or player.get("username") or "maimai player")
        nf, nl = self.fit_line(nick, 330, 32, 20)
        self.d.text((240, 55), nl, font=nf, fill=(51, 51, 51))
        ra_img = self.icon(_ra_pic(rating), (223, 42))
        if ra_img:
            self.paste(ra_img, (240, 111))
        rating_str = f"{rating:05d}"
        for n, i in enumerate(rating_str):
            num_img = self.icon(f"UI_NUM_Drating_{i}.png", (20, 24))
            if num_img:
                self.paste(num_img, (343 + 18 * n, 120))
            elif ra_img:  # 没有数字贴图就直接用字体画
                self.d.text((346 + 18 * n, 116), i, font=self.font("en", 22), fill=(48, 48, 48))

        arpi = peer.get("arpi")
        overlap_val = (peer.get("b50_overlap") or {}).get("value")
        arpi_text = "N/A" if arpi is None else f"{_f(arpi):+.4f}"
        overlap_text = "N/A" if overlap_val is None else f"{_f(overlap_val):.2f}%"
        self.d.text((80, 251), "DXRating", font=self.font("en", 26), fill=(120, 120, 120))
        self.d.text((220, 249), f"{summary.get('b35_ra', 0)} / {summary.get('b15_ra', 0)}", font=self.font("en", 32), fill=(51, 51, 51))
        self.d.text((80, 310), "ARPI", font=self.font("en", 26), fill=(120, 120, 120))
        self.d.text((220, 306), arpi_text, font=self.font("en", 36), fill=(46, 125, 50) if _f(arpi, 0) >= 0 else (198, 40, 40))
        self.d.text((80, 360), "平均重合", font=self.font("cn", 26), fill=(120, 120, 120))
        self.d.text((220, 358), overlap_text, font=self.font("en", 36), fill=(66, 133, 244))

        self.rrect((640, 30, 1080, 420), 16, (245, 248, 255, 230))
        self.d.text((660, 55), "平均值", font=self.font("cn", 30), fill=(26, 115, 232))
        rows = [
            ("全B50达成", summary.get("avg_achievement"), "%"),
            ("全B50同段", summary.get("avg_peer"), "%"),
            ("B35均值", (summary.get("b35") or {}).get("avg_achievement"), "%"),
            ("B15均值", (summary.get("b15") or {}).get("avg_achievement"), "%"),
            ("定数均值", summary.get("avg_ds"), ""),
        ]
        y = 110
        for label, value, suffix in rows:
            txt = "N/A" if value is None else f"{_f(value):.4f}{suffix}" if suffix else f"{_f(value):.2f}"
            self.d.text((660, y), label, font=self.font("cn", 22), fill=(110, 110, 110))
            self.d.text((830, y - 8), txt, font=self.font("en", 30), fill=(51, 51, 51))
            y += 55

        self.rrect((1100, 30, 1760, 420), 16, (255, 251, 235, 230), (245, 221, 160, 255))
        self.d.text((1130, 52), "指数说明", font=self.font("cn", 28), fill=(180, 110, 20))
        explain = (
            "ARPI：对比同rating分段玩家在同一谱面的平均达成率,得到综合表现差异。"
            "B50重合度：统计同段玩家 B50 与本 B50 的平均重合比例。"
            "低于30%偏小众审美,超过50%偏模板路线;高分段重合度仅作娱乐参考。"
        )
        f, lines, step = self.fit_text(explain, 600, 7, 24, 18)
        for i, line in enumerate(lines):
            self.d.text((1130, 100 + i * step), line, font=f, fill=(95, 85, 65))
        slogan = "分析内容仅供娱乐参考，不要攀比和焦虑，玩得开心就好。"
        f2, lines2, step2 = self.fit_text(slogan, 600, 2, 22, 18)
        for i, line in enumerate(lines2):
            self.d.text((1130, 325 + i * step2), line, font=f2, fill=(198, 40, 40))

    def song_card(
        self,
        x: int,
        y: int,
        w: int,
        h: int,
        song: dict,
        label: str,
        lc: Any,
        bg: Any,
        show_peer: bool = False,
        show_reason: bool = False,
    ) -> None:
        self.rrect((x, y, x + w, y + h), 14, bg)
        mid = song.get("music_id") or song.get("musicId") or ""
        level_idx = _i(song.get("level_index"), -1)
        cover_size = 160
        cover = self.load_cover(mid, cover_size)
        DIFF_FILE = ["Basic.png", "Advanced.png", "Expert.png", "Master.png", "Re_master.png"]
        border_color = BORDER_COLOR[level_idx] if 0 <= level_idx <= 4 else (180, 180, 180)
        border = 7
        border_size = cover_size + border * 2
        border_layer = Image.new("RGBA", (border_size, border_size), (0, 0, 0, 0))
        ImageDraw.Draw(border_layer).rounded_rectangle((0, 0, border_size, border_size), radius=6, fill=border_color + (255,))
        border_layer.alpha_composite(cover, (border, border))
        self.paste(border_layer, (x + 30 - border, y + 28 - border))
        tx = x + 220
        tf, title = self.fit_line(str(song.get("title") or ""), 580, 24, 18)
        self.d.text((tx, y + 18), title, font=tf, fill=(51, 51, 51))
        line_y = y + 55
        self.d.line((tx, line_y, x + 800, line_y), fill=border_color + (255,), width=7)
        ach = _f(song.get("achievement"))
        ach_text = f"{ach:.4f}%"
        self.d.text((tx, y + 53), ach_text, font=self.font("en", 48), fill=(33, 33, 33))
        ds = _f(song.get("ds"))
        info_y = y + 105
        if 0 <= level_idx <= 4:
            diff_path = self.icons / DIFF_FILE[level_idx]
            if diff_path.exists():
                diff_img = Image.open(diff_path).convert("RGBA")
                new_h = 45
                new_w = int(diff_img.width * new_h / diff_img.height)
                self.paste(diff_img.resize((new_w, new_h), Image.Resampling.LANCZOS), (tx, info_y + 2))
                ds_x = tx + new_w + 6
            else:
                self.d.text((tx, info_y), DIFF_SHORT.get(level_idx, ""), font=self.font("en", 22), fill=(140, 140, 140))
                ds_x = tx + self.font("en", 22).getbbox(DIFF_SHORT.get(level_idx, ""))[2] + 4
            ds_color = BORDER_COLOR[level_idx]
        else:
            self.d.text((tx, info_y), DIFF_SHORT.get(level_idx, ""), font=self.font("en", 22), fill=(140, 140, 140))
            ds_x = tx + self.font("en", 22).getbbox(DIFF_SHORT.get(level_idx, ""))[2] + 4
            ds_color = (140, 140, 140)
        ds_y_offset = 6 if level_idx == 3 else 10
        self.d.text((ds_x, info_y + ds_y_offset), f"{ds:.1f}", font=self.font("en", 25), fill=ds_color)
        ra_txt = f"  RA:{_i(song.get('ra'))}"
        rx = ds_x + self.font("en", 25).getbbox(f"{ds:.1f}")[2] + 4
        self.d.text((rx, info_y + ds_y_offset), ra_txt, font=self.font("en", 25), fill=ds_color)
        icon_x = rx + self.font("en", 25).getbbox(ra_txt)[2] + 10
        if song.get("overlap") is not None:
            ol_text = f"重合{_f(song.get('overlap')):.2f}%"
            self.d.text((icon_x, info_y + ds_y_offset), ol_text, font=self.font("cn", 20), fill=(66, 133, 244))
            icon_x += self.font("cn", 20).getbbox(ol_text)[2] + 8
        rank = self.icon(_rank_icon(ach), (110, 55))
        if rank:
            self.paste(rank, (icon_x, y + 66))
        fc_img = self.icon(FC_ICON.get(str(song.get("fc_label") or ""), ""), (72, 72))
        if fc_img:
            self.paste(fc_img, (icon_x, info_y + ds_y_offset + 3))
        row2_y = y + 145
        gain_1005 = _i(song.get("gain_1005"))
        if gain_1005 > 0:
            target = song.get("target", "SSS+")
            self.d.text((tx, row2_y), f"{target} +{gain_1005}", font=self.font("en", 22), fill=(232, 124, 32))
        elif show_peer and song.get("peer_avg") is not None:
            p_avg = _f(song.get("peer_avg"))
            p_text = f"同级均值: {p_avg:.4f}%"
            self.d.text((tx, row2_y), p_text, font=self.font("cn", 20), fill=(120, 120, 120))
            if song.get("gap") is not None:
                gap = _f(song.get("gap"))
                gap_x = tx + self.font("cn", 20).getbbox(p_text)[2] + 8
                self.d.text((gap_x, row2_y), f"ARPI {gap:+.4f}", font=self.font("en", 24), fill=(46, 125, 50) if gap >= 0 else (198, 40, 40))
        row3_y = y + 177
        if show_reason:
            reason = " ".join(str(song.get("reason") or song.get("recommend_reason") or "").replace("\r", " ").replace("\n", " ").split())
            if reason:
                reason_font, reason_lines, reason_step = self.fit_text(reason, 560, 2, 18, 14)
                reason_y = row2_y + 28
                self.d.text((tx, reason_y), "推荐理由", font=self.font("cn", 16), fill=(160, 96, 32))
                text_y = reason_y + 20
                for line in reason_lines:
                    self.d.text((tx, text_y), line, font=reason_font, fill=(95, 85, 65))
                    text_y += reason_step
                row3_y = max(row3_y, text_y + 2)
        chart_summaries = self.data.get("chart_summaries") or {}
        summary = chart_summaries.get(str(mid)) or {}
        config_tags = summary.get("config_tags") or song.get("config_tags") or song.get("config") or song.get("keywords") or []
        if config_tags:
            tag_x = tx
            for tag in config_tags[:5]:
                tag_str = str(tag).strip()
                if not tag_str:
                    continue
                tag_font = self.font("cn", 17)
                tw = tag_font.getbbox(tag_str)[2] + 12
                if tag_x + tw > x + w - 10:
                    break
                self.rrect((tag_x, row3_y, tag_x + tw, row3_y + 28), 6, (220, 235, 255, 255))
                self.d.text((tag_x + 6, row3_y + 3), tag_str, font=tag_font, fill=(30, 100, 200))
                tag_x += tw + 6

    def stroke_text(self, xy: tuple, text: str, font: Any, fill: Any, stroke_color: Any = (255, 255, 255), stroke_width: int = 2) -> None:
        x, y = xy
        for dx in range(-stroke_width, stroke_width + 1):
            for dy in range(-stroke_width, stroke_width + 1):
                if dx == 0 and dy == 0:
                    continue
                self.d.text((x + dx, y + dy), text, font=font, fill=stroke_color)
        self.d.text((x, y), text, font=font, fill=fill)

    def draw_sections(self) -> int:
        evidence = self.data.get("evidence") or {}
        cy = 450
        card_h = 210
        sections = [
            ("亮点谱面", "highlights", "亮点", (46, 125, 50), (120, 200, 125, 230), True, 4, 210, False),
            ("普通点", "ordinaries", "普通", (198, 40, 40), (235, 150, 150, 230), True, 2, 210, False),
            ("单曲RA最高", "highest_song_rating", "最高RA", (232, 124, 32), (255, 200, 140, 230), False, 1, 210, False),
            ("B50重合极值", "overlap_extremes", "重合", (66, 133, 244), (150, 200, 250, 230), False, 2, 210, False),
            ("推分推荐", "push_recommendations", "推分", (232, 124, 32), (255, 210, 155, 230), False, 3, 252, True),
            ("配置特化", "config_specialized", "擅长", (30, 100, 180), (145, 195, 245, 230), False, 2, 210, False),
            ("最少游玩", "least_played", "少PC", (120, 80, 200), (185, 170, 240, 230), False, 2, 210, False),
        ]
        for title, key, label, color, bg, show_peer, max_n, section_card_h, show_reason in sections:
            if SECTIONS_ENABLED is not None and key not in SECTIONS_ENABLED:
                continue
            songs = (evidence.get(key) or self.data.get(key) or [])[:max_n]
            if not songs:
                continue
            title_font = self.font("cn", 28)
            tw = title_font.getbbox(title)[2]
            light_bg = tuple(min(c + 140, 255) for c in color[:3]) + (180,)
            self.rrect((60 - 10, cy - 4, 60 + tw + 10, cy + 36), 10, light_bg)
            self.stroke_text((60, cy - 4), title, font=title_font, fill=color)
            cy += 38
            for row_start in range(0, len(songs), 2):
                for col in range(2):
                    idx = row_start + col
                    if idx >= len(songs):
                        break
                    self.song_card(60 + col * 880, cy, 820, section_card_h, songs[idx], label, color, bg, show_peer, show_reason)
                cy += section_card_h + 15
        return cy

    def draw_analysis(self, start_y: int) -> int:
        top_y = start_y + 45
        body_font, body_lines, body_step = self.fit_text(self.analysis_overall, 1580, 999, 26, 15)
        summary_font, summary_lines, summary_step = self.fit_text(self.analysis_impression, 1580, 3, 24, 16)
        body_h = max(420, 84 + len(body_lines) * body_step + 80)
        summary_h = 0
        if self.analysis_impression:
            summary_h = max(150, 76 + len(summary_lines) * summary_step + 48)
        panel_h = body_h + summary_h + (24 if summary_h else 0)
        self._ensure_h(top_y + panel_h + 160)
        if self.analysis_title:
            at_font = self.font("cn", 34)
            atw = at_font.getbbox(self.analysis_title)[2]
            self.rrect((100, top_y + 10, 120 + atw + 30, top_y + 60), 14, (255, 255, 255, 255))
            self.stroke_text((120, top_y + 10), self.analysis_title, font=at_font, fill=(26, 115, 232))

        body_y = top_y + 74
        self.rrect((70, body_y, 1730, body_y + body_h), 14, (255, 249, 238, 230), (245, 210, 150, 255), width=3)
        self.d.text((120, body_y + 14), "正文", font=self.font("cn", 28), fill=(198, 100, 20))
        y_cur = body_y + 62
        for line in body_lines:
            self.d.text((120, y_cur), line, font=body_font, fill=(80, 65, 45))
            y_cur += body_step

        if self.analysis_impression:
            summary_y = body_y + body_h + 24
            self.rrect((70, summary_y, 1730, summary_y + summary_h), 14, (245, 248, 255, 178), (210, 225, 245, 255), width=3)
            self.d.text((120, summary_y + 14), "总结", font=self.font("cn", 26), fill=(26, 115, 232))
            y_cur = summary_y + 52
            for line in summary_lines:
                self.d.text((120, y_cur), line, font=summary_font, fill=(80, 80, 80))
                y_cur += summary_step
        return top_y + panel_h + 20

    def draw_footer(self, y: int) -> None:
        text = "Designed by HanYa@OneCatBot"
        f = self.font("en", 24)
        tw = f.getbbox(text)[2]
        self.d.text(((CANVAS_W - tw) // 2, y), text, font=f, fill=(180, 140, 80))

    def draw(self) -> Any:
        self.load_avatar()
        self.draw_header()
        songs_end = self.draw_sections()
        panel_end = self.draw_analysis(songs_end + 10)
        self._ensure_h(panel_end + 140)
        self.draw_footer(panel_end + 10)

        global _bg_image
        bg_path = self.icons / "bj.png"
        if bg_path.exists() and _bg_image is None:
            _bg_image = Image.open(bg_path).convert("RGBA")
        if _bg_image:
            scale = CANVAS_W / _bg_image.width
            new_w = int(_bg_image.width * scale)
            new_h = int(_bg_image.height * scale)
            resized = _bg_image.resize((new_w, new_h), Image.Resampling.LANCZOS)
            bg_layer = Image.new("RGBA", (CANVAS_W, self.im.height), (255, 255, 255, 255))
            bg_layer.alpha_composite(resized, (0, 0))
            bg_layer.alpha_composite(self.im, (0, 0))
            self.im = bg_layer
            self.d = ImageDraw.Draw(self.im)

        cropped = self.im.crop((0, 0, CANVAS_W, panel_end + 100))
        out_w = max(300, int(OUT_WIDTH or 900))
        out_h = int(out_w * cropped.height / cropped.width)
        return cropped.resize((out_w, out_h), Image.Resampling.LANCZOS).convert("RGB")


async def prepare_render_cache(context: dict, assets_path: str) -> None:
    """异步预下载头像和曲绘，避免渲染阶段阻塞事件循环。"""
    if not context:
        return

    assets_dir = Path(assets_path)
    cover_dir = assets_dir / "cover"
    avatar_dir = assets_dir / "avatars"
    avatar_dir.mkdir(parents=True, exist_ok=True)
    cover_dir.mkdir(parents=True, exist_ok=True)

    def normalize_sid(sid: str) -> tuple[str, str]:
        s = sid.lstrip("0")
        if len(s) == 5 and s.startswith("10"):
            s = s[2:]
        s = s.lstrip("0")
        return s, s.zfill(5)

    urls: list[tuple[str, Path]] = []
    qq = str((context.get("player") or {}).get("qq") or "")
    if qq and CACHE_AVATAR:
        avatar_path = avatar_dir / f"{qq}.png"
        if not avatar_path.exists():
            urls.append((f"http://q.qlogo.cn/headimg_dl?dst_uin={qq}&spec=640", avatar_path))

    seen: set[str] = set()
    for song in (context.get("b50") or []) if CACHE_COVER else []:
        sid = str(song.get("music_id") or song.get("musicId") or "")
        if not sid or sid in seen:
            continue
        seen.add(sid)
        norm_sid, padded_sid = normalize_sid(sid)
        cover_path = cover_dir / f"{norm_sid}.png"
        if cover_path.exists():
            continue
        urls.append((f"https://www.diving-fish.com/covers/{padded_sid}.png", cover_path))

    if not urls:
        return

    async with httpx.AsyncClient(timeout=8) as client:
        for url, path in urls:
            try:
                resp = await client.get(url)
                resp.raise_for_status()
                path.write_bytes(resp.content)
            except Exception:
                continue


def render_image(context: dict, analysis_text: str, assets_path: str) -> Any:
    """渲染分析图，失败时抛出异常。"""
    if not _PIL_OK:
        raise RuntimeError("Pillow 未安装，请执行 pip install Pillow")
    if not assets_path:
        raise RuntimeError("未配置 b50_assets_path，请在 .env 中填写 assets 目录路径")
    assets = Path(assets_path)
    font_dir = assets / "ui" / "fonts"
    if not font_dir.exists():
        raise RuntimeError(f"assets 目录下未找到字体文件夹：{font_dir}")
    player = context.get("player") or {}
    title = f"{player.get('nickname', '')} B50锐评"
    analysis_result = _parse_analysis_result(analysis_text)
    drawer = _Draw(context, title, analysis_result, assets)
    return drawer.draw()
