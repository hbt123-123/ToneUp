"""Tests for backend/app/api/sections.py — GET /api/question-banks/{bank_id}/sections."""

from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import sqlite3

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

def test_unauthorized_returns_401(app_and_data):
    """未登录访问返回 401。"""
    client, _, _ = app_and_data
    resp = client.get("/api/question-banks/math1/sections")
    assert resp.status_code == 401


def test_not_found_404(app_and_data):
    """不存在的 bank 返回 404。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.get("/api/question-banks/nonexistent/sections", headers=headers)
    assert resp.status_code == 404


def test_zhenti_returns_year_grouped(app_and_data):
    """真题按 year 分组返回 sections。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.get("/api/question-banks/math1/sections", headers=headers)
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["category"] == "真题"
    assert len(data["sections"]) > 0
    for section in data["sections"]:
        assert "year" in section
        assert "types" in section
        for t in section["types"]:
            assert all(k in t for k in ("type_code", "type_name", "total", "done", "wrong", "favorited"))


@pytest.fixture
def app_and_data_zhuanti(tmp_path, monkeypatch):
    """Set up data root with zhuanti bank."""
    src_dir = pathlib.Path(__file__).resolve().parents[1] / "data"
    for item in src_dir.iterdir():
        dst = tmp_path / item.name
        if item.is_dir():
            if dst.exists():
                shutil.rmtree(dst)
            shutil.copytree(item, dst)
        else:
            shutil.copy2(str(item), str(dst))

    # Add zhuanti bank
    manifest_path = tmp_path / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    zhuanti_db = tmp_path / "math" / "math_zhuanti.db"
    zhuanti_db.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(zhuanti_db)
    try:
        conn.execute("""CREATE TABLE IF NOT EXISTS collections (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL, year INTEGER, display_order INTEGER NOT NULL
        )""")
        conn.execute("""CREATE TABLE IF NOT EXISTS questions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            collection_id INTEGER NOT NULL, question_type_id INTEGER NOT NULL,
            number INTEGER NOT NULL, content TEXT NOT NULL,
            options TEXT, sub_questions TEXT, answer_text TEXT, solution TEXT,
            score REAL, display_order INTEGER NOT NULL
        )""")
        conn.execute("INSERT INTO collections (id, title, year, display_order) VALUES (1, '函数与极限', 2024, 1)")
        conn.execute("INSERT INTO collections (id, title, year, display_order) VALUES (2, '导数与应用', 2024, 2)")
        conn.execute("INSERT INTO collections (id, title, year, display_order) VALUES (3, '函数与极限', 2023, 1)")
        for i in range(1, 6):
            conn.execute(f"INSERT INTO questions (id, collection_id, question_type_id, number, content, display_order) VALUES ({i}, {1 if i <= 3 else 2}, 1, {i}, 'Q{i}', {i})")
        conn.execute("CREATE TABLE IF NOT EXISTS images (id INTEGER PRIMARY KEY, data BLOB, mime TEXT)")
        conn.commit()
    finally:
        conn.close()
    manifest["banks"].append({
        "id": "math_zhuanti", "subject_id": "math", "type_id": "zhuanti",
        "name": "考研数学专题", "path": "math/math_zhuanti.db",
        "schema_version": 1, "enabled": True,
    })
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    monkeypatch.setenv("DATA_ROOT", str(tmp_path))
    get_settings.cache_clear()
    reset_registry()
    app = create_app()
    client = TestClient(app)
    user_db_path = str(tmp_path / "user_data.db")
    init_user_db(user_db_path)

    return client, user_db_path, tmp_path


def test_zhuanti_returns_title_grouped(app_and_data_zhuanti):
    """专题按 title 分组返回 sections。"""
    client, user_db_path, _ = app_and_data_zhuanti
    headers = _auth_headers(user_db_path, "bob")
    resp = client.get("/api/question-banks/math_zhuanti/sections", headers=headers)
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["category"] == "专项题"
    assert len(data["sections"]) > 0
    for section in data["sections"]:
        assert "title" in section
        assert "total" in section
        assert "done" in section
        assert "wrong" in section
        assert "favorited" in section


def test_statistics_correct(app_and_data):
    """统计数据（done/wrong/favorited）正确。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.get("/api/question-banks/math1/sections", headers=headers)
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["category"] == "真题"
    for section in data["sections"]:
        for t in section["types"]:
            assert isinstance(t["total"], int)
            assert isinstance(t["done"], int)
            assert isinstance(t["wrong"], int)
            assert isinstance(t["favorited"], int)
            assert t["done"] >= 0
            assert t["wrong"] >= 0
            assert t["favorited"] >= 0


def test_disabled_bank_404(app_and_data):
    """已禁用的 bank 返回 404。"""
    client, user_db_path, data_root = app_and_data
    manifest_path = data_root / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["banks"][0]["enabled"] = False
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    reset_registry()
    get_settings.cache_clear()
    app = create_app()
    client = TestClient(app)
    headers = _auth_headers(user_db_path, "alice")
    resp = client.get("/api/question-banks/math1/sections", headers=headers)
    assert resp.status_code == 404
