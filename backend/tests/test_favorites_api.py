"""Tests for favorites API: PUT/DELETE/GET /api/favorites."""

import importlib.util
import pathlib

import pytest
from fastapi.testclient import TestClient

from app.core.config import get_settings
from app.core.security import create_access_token
from app.main import create_app
from app.repositories import user_repo

_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)
init_user_db = _mod.init_user_db

_mp = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "migrate_mobile_tables.py"
_mspec = importlib.util.spec_from_file_location("migrate_mobile_tables", _mp)
_mmod = importlib.util.module_from_spec(_mspec)
_mspec.loader.exec_module(_mmod)
migrate_mobile_tables = _mmod.migrate_mobile_tables


@pytest.fixture
def app_and_data(tmp_path, monkeypatch):
    import shutil
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
    app = create_app()
    client = TestClient(app)
    user_db_path = str(tmp_path / "user_data.db")
    init_user_db(user_db_path)
    migrate_mobile_tables(user_db_path)
    return client, user_db_path, tmp_path


def _auth_headers(user_db_path, username="alice"):
    user_id = user_repo.create_user(user_db_path, username, password_hash="hash-x")
    settings = get_settings()
    token = create_access_token(
        sub=user_id, role="user",
        jwt_secret=settings.jwt_secret, expires_hours=settings.jwt_expire_hours,
    )
    return {"Authorization": f"Bearer {token}"}, user_id


# ── PUT /api/favorites ────────────────────────────────────────────────────────

class TestAddFavorite:
    def test_add_favorite(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.put("/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["favorited"] is True

    def test_add_favorite_idempotent(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        client.put("/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        resp = client.put("/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["favorited"] is True
        # 验证只有一条
        resp = client.get("/api/favorites/banks", headers=h1)
        assert resp.json()["data"]["banks"][0]["favorite_count"] == 1

    def test_requires_bank_id(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.put("/api/favorites", json={"question_id": 1}, headers=h1)
        assert resp.status_code == 400

    def test_requires_question_id(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.put("/api/favorites", json={"bank_id": "math1"}, headers=h1)
        assert resp.status_code == 400


# ── DELETE /api/favorites ─────────────────────────────────────────────────────

class TestRemoveFavorite:
    def test_remove_favorite(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        client.put("/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        resp = client.request("DELETE", "/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["favorited"] is False

    def test_remove_nonexistent_idempotent(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.request("DELETE", "/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["favorited"] is False

    def test_requires_bank_id(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.request("DELETE", "/api/favorites", json={"question_id": 1}, headers=h1)
        assert resp.status_code == 400

    def test_requires_question_id(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.request("DELETE", "/api/favorites", json={"bank_id": "math1"}, headers=h1)
        assert resp.status_code == 400


# ── GET /api/favorites/banks ─────────────────────────────────────────────────

class TestListFavoriteBanks:
    def test_empty_list(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.get("/api/favorites/banks", headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["banks"] == []

    def test_aggregated_list(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        client.put("/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        client.put("/api/favorites", json={"bank_id": "math1", "question_id": 2}, headers=h1)
        client.put("/api/favorites", json={"bank_id": "english1", "question_id": 1}, headers=h1)
        resp = client.get("/api/favorites/banks", headers=h1)
        assert resp.status_code == 200
        banks = resp.json()["data"]["banks"]
        assert len(banks) == 2
        bank_map = {b["bank_id"]: b["favorite_count"] for b in banks}
        assert bank_map["math1"] == 2
        assert bank_map["english1"] == 1

    def test_only_own_favorites(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        client.put("/api/favorites", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        client.put("/api/favorites", json={"bank_id": "math1", "question_id": 2}, headers=h2)
        resp = client.get("/api/favorites/banks", headers=h1)
        assert len(resp.json()["data"]["banks"]) == 1

    def test_requires_auth(self, app_and_data):
        client, _, _ = app_and_data
        resp = client.get("/api/favorites/banks")
        assert resp.status_code == 401
