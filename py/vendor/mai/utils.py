from __future__ import annotations

import re
from typing import Any


def _safe_int(value: Any, default: int = 0) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _safe_float(value: Any, default: float = 0.0) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _strip_emoji(text: str) -> str:
    return re.sub(r"[\U00010000-\U0010ffff]", "", str(text or ""))


def _chart_level_name(level_index: int) -> str:
    return {0: "BAS", 1: "ADV", 2: "EXP", 3: "MAS", 4: "ReM"}.get(_safe_int(level_index, -1), "")


def _chart_level_short(level_index: int) -> str:
    return {0: "B", 1: "A", 2: "E", 3: "M", 4: "R"}.get(_safe_int(level_index, -1), "")
