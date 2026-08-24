"""测试基座 - conftest.py

落盘后即为冻结文件，后续任务只读。

三个 fixture：
- client：TestClient 实例，带环境变量蒙层
- data_root_with_banks：复制后的数据根目录（含 manifest.json）
- user_db：初始化的临时用户数据库路径
"""

import importlib.util
import os
import shutil
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient


# ── fixture: client ──────────────────────────────────────────────────────

@pytest.fixture
def client(monkeypatch, tmp_path) -> Any:
    """提供 TestClient 实例，注入测试专用环境变量。

    环境变量覆盖：
    - JWT_SECRET = "x" * 32（满足启动校验）
    - DATA_ROOT = 临时路径
    - CORS_ALLOW_ORIGINS = ['http://allowed.dev']
    """
    # 设置环境变量
    monkeypatch.setenv("JWT_SECRET", "x" * 32)
    monkeypatch.setenv("DATA_ROOT", str(tmp_path))
    monkeypatch.setenv(
        "CORS_ALLOW_ORIGINS",
        '["http://allowed.dev"]',
    )

    # 清除 settings 缓存，确保读取最新环境变量
    from app.core.config import get_settings
    get_settings.cache_clear()

    # 创建 FastAPI 应用
    from app.main import create_app
    app = create_app()

    # 返回 TestClient
    with TestClient(app) as c:
        yield c


# ── fixture: data_root_with_banks ────────────────────────────────────────

@pytest.fixture
def data_root_with_banks(tmp_path) -> Path:
    """复制数据根目录和 manifest.json 到临时路径。

    返回值：临时 data_root 的 Path
    """
    src_dir = Path("E:/project/ToneUp/backend/data")
    dst_dir = tmp_path / "data_root" / "data"
    # 复制整个 data 目录
    if dst_dir.exists():
        shutil.rmtree(dst_dir)
    shutil.copytree(src_dir, dst_dir)
    # 复制 manifest.json 到 data_root 级别
    src_manifest = Path("E:/project/ToneUp/backend/data/manifest.json")
    dst_manifest = tmp_path / "data_root" / "manifest.json"
    if src_manifest.exists():
        shutil.copy(str(src_manifest), str(dst_manifest))
    return tmp_path / "data_root"


# ── fixture: user_db ─────────────────────────────────────────────────────

@pytest.fixture
def user_db(tmp_path) -> Path:
    """初始化临时用户数据库。

    返回：数据库文件的临时路径
    """
    # 加载 init_user_db.py 脚本
    _p = Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
    _spec = importlib.util.spec_from_file_location("init_user_db", str(_p))
    _mod = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_mod)
    init_user_db = _mod.init_user_db

    db_path = str(tmp_path / "user_data.db")
    init_user_db(db_path)
    return Path(db_path)