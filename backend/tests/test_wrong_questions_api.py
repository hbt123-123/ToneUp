"""Tests for DELETE /api/wrong-questions/{id} verification."""

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


class TestDeleteWrongQuestion:
    def test_delete_own_question(self, app_and_data):
        """创建用户、创建错题、DELETE 返回 200。"""
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        add = client.post("/api/wrong-questions", json={"bank_id": "math1", "question_id": 1}, headers=h1)
        assert add.status_code == 200
        wid = add.json()["data"]["id"]
        resp = client.delete(f"/api/wrong-questions/{wid}", headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["deleted"] is True

    def test_delete_other_user_forbidden(self, app_and_data):
        """删除他人错题返回 403。"""
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        add = client.post("/api/wrong-questions", json={"bank_id": "math1", "question_id": 2}, headers=h1)
        wid = add.json()["data"]["id"]
        resp = client.delete(f"/api/wrong-questions/{wid}", headers=h2)
        assert resp.status_code == 403

    def test_delete_nonexistent(self, app_and_data):
        """删除不存在的 id 返回 404。"""
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db)
        resp = client.delete("/api/wrong-questions/99999", headers=h1)
        assert resp.status_code == 404
