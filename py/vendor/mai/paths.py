"""路径解析：全部相对项目根目录，支持用环境变量覆盖。"""
from __future__ import annotations

import os
from pathlib import Path

# 项目根目录 = 本文件所在目录(tai) -> vendor -> 项目根
PROJECT_ROOT = Path(__file__).resolve().parents[2]

DATA_DIR = Path(os.environ.get("B50_DATA_DIR") or (PROJECT_ROOT / "data"))
PACKAGE_ASSETS_DIR = Path(os.environ.get("B50_ASSETS_PATH") or (PROJECT_ROOT / "assets"))
STATIC_DIR = PACKAGE_ASSETS_DIR
FONT_CN = PACKAGE_ASSETS_DIR / "ui" / "fonts" / "ResourceHanRoundedCN.otf"
FONT_EN = PACKAGE_ASSETS_DIR / "ui" / "fonts" / "Torus SemiBold.otf"
