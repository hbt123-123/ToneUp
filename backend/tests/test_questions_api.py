"""Tests for backend/app/api/question_banks.py — GET /api/question-banks/{bank_id}/questions。

回归背景：M-284 批量预载曾在 _build_dto 的 try-except 防护之外裸访问 r["passage_id"]，
而数学/政治库的 questions 表无该列（仅英语库有）→ IndexError → 500。
本文件补上列表端点的功能测试盲区。
"""

from __future__ import annotations

import importlib.util
import pathlib
import shutil

import pytest
from fastapi.testclient import TestClient

from app.core.bank_registry import reset_registry
from app.core.config import get_settings
from app.core.security import create_access_token
from app.main import create_app
from app.repositories import user_repo

_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db


def _auth_headers(user_db_path, username="alice"):
    user_id = user_repo.create_user(user_db_path, username, password_hash="hash-x")
    settings = get_settings()
    token = create_access_token(
        sub=user_id, role="user",
        jwt_secret=settings.jwt_secret, expires_hours=settings.jwt_expire_hours,
    )
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def app_and_data(tmp_path, monkeypatch):
    src_dir = pathlib.Path(__file__).resolve().parents[1] / "data"
    for item in src_dir.iterdir():
        dst = tmp_path / item.name
        if item.is_dir():
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(item, dst)
        else:
            shutil.copy2(str(item), str(dst))

    monkeypatch.setenv("DATA_ROOT", str(tmp_path))
    get_settings.cache_clear()
    reset_registry()
    app = create_app()
    client = TestClient(app)
    user_db_path = str(tmp_path / "user_data.db")
    init_user_db(user_db_path)

    return client, user_db_path, tmp_path


# ── Tests ──────────────────────────────────────────────────

def test_list_questions_bank_without_passage_column_returns_200(app_and_data):
    """回归：数学库 questions 表无 passage_id 列（真实 schema），列表端点不得 500。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path)
    resp = client.get("/api/question-banks/math1/questions", headers=headers)
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total"] > 0
    assert len(data["items"]) > 0
    # 无 passage 列的库：passage 恒为 None，不抛 IndexError
    for item in data["items"]:
        assert item["passage"] is None


def test_list_questions_politics_bank_returns_200(app_and_data):
    """回归：政治库（无 passage_id 列、无 passages 表）列表端点不 500。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "bob")
    resp = client.get("/api/question-banks/politics1/questions", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["data"]["total"] > 0


def test_list_questions_english_bank_with_passages(app_and_data):
    """英语库（有 passage_id 列）列表路径保持正常，不因防御逻辑回归。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "carol")
    resp = client.get("/api/question-banks/english1/questions", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["data"]["total"] > 0
