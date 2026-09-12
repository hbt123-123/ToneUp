"""Tests for extended notes API: shared, visibility, likes, PUT/DELETE."""

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


def _create_note(client, headers, bank_id="bank-a", question_id=1, note_text="test note", visibility="public"):
    """Helper: PUT 创建笔记并返回响应。"""
    resp = client.put(
        f"/api/questions/{question_id}/notes",
        json={"bank_id": bank_id, "note_text": note_text, "visibility": visibility},
        headers=headers,
    )
    return resp


def _get_note_id(client, headers, bank_id="bank-a", question_id=1):
    """Helper: 获取笔记 ID。"""
    resp = client.get(
        f"/api/questions/{question_id}/notes",
        params={"bank_id": bank_id, "scope": "mine"},
        headers=headers,
    )
    items = resp.json()["data"]["items"]
    if items:
        return items[0]["note_id"]
    return None


# ── GET scope=public ─────────────────────────────────────────────────────────

class TestGetPublicNotes:
    def test_returns_only_public_notes(self, app_and_data):
        client, db, _ = app_and_data
        h1, uid1 = _auth_headers(db, "alice")
        h2, uid2 = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=10, note_text="public note", visibility="public")
        _create_note(client, h2, question_id=10, note_text="private note", visibility="private")
        resp = client.get("/api/questions/10/notes", params={"bank_id": "bank-a", "scope": "public"}, headers=h1)
        assert resp.status_code == 200
        data = resp.json()["data"]
        assert data["total"] == 1
        assert data["items"][0]["note_text"] == "public note"

    def test_ordered_by_like_count_desc(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=20, note_text="less liked")
        _create_note(client, h2, question_id=20, note_text="more liked")
        resp = client.get("/api/questions/20/notes", params={"bank_id": "bank-a", "scope": "public"}, headers=h1)
        items = resp.json()["data"]["items"]
        more_liked_note = [i for i in items if i["note_text"] == "more liked"][0]
        more_liked_id = more_liked_note["note_id"]
        client.post(f"/api/notes/{more_liked_id}/like", headers=h1)
        resp = client.get("/api/questions/20/notes", params={"bank_id": "bank-a", "scope": "public"}, headers=h1)
        items = resp.json()["data"]["items"]
        assert items[0]["note_text"] == "more liked"
        assert items[0]["like_count"] == 1

    def test_includes_is_liked_by_me(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=30, note_text="my note")
        resp = client.get("/api/questions/30/notes", params={"bank_id": "bank-a", "scope": "public"}, headers=h1)
        assert resp.json()["data"]["items"][0]["is_liked_by_me"] is False
        note_id = resp.json()["data"]["items"][0]["note_id"]
        client.post(f"/api/notes/{note_id}/like", headers=h1)
        resp = client.get("/api/questions/30/notes", params={"bank_id": "bank-a", "scope": "public"}, headers=h1)
        assert resp.json()["data"]["items"][0]["is_liked_by_me"] is True


# ── GET scope=mine ───────────────────────────────────────────────────────────

class TestGetMineNotes:
    def test_returns_only_own_notes(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=40, note_text="alice note")
        _create_note(client, h2, question_id=40, note_text="bob note")
        resp = client.get("/api/questions/40/notes", params={"bank_id": "bank-a", "scope": "mine"}, headers=h1)
        assert resp.status_code == 200
        items = resp.json()["data"]["items"]
        assert len(items) == 1
        assert items[0]["note_text"] == "alice note"

    def test_empty_when_no_notes(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        resp = client.get("/api/questions/999/notes", params={"bank_id": "bank-a", "scope": "mine"}, headers=h1)
        assert resp.json()["data"]["items"] == []
        assert resp.json()["data"]["total"] == 0


# ── PUT with visibility ──────────────────────────────────────────────────────

class TestPutNoteVisibility:
    def test_creates_private_note(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        resp = _create_note(client, h1, question_id=50, visibility="private")
        assert resp.status_code == 200
        resp = client.get(
            "/api/questions/50/notes",
            params={"bank_id": "bank-a", "scope": "mine"},
            headers=h1,
        )
        assert resp.json()["data"]["items"][0]["visibility"] == "private"

    def test_private_note_not_in_public(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=60, visibility="private")
        resp = client.get(
            "/api/questions/60/notes",
            params={"bank_id": "bank-a", "scope": "public"},
            headers=h1,
        )
        assert resp.json()["data"]["total"] == 0

    def test_note_text_exceeds_1000_returns_400(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        resp = _create_note(client, h1, question_id=70, note_text="x" * 1001)
        assert resp.status_code == 400


# ── PUT /api/notes/{note_id} ─────────────────────────────────────────────────

class TestUpdateNote:
    def test_owner_can_modify(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=80, note_text="original")
        note_id = _get_note_id(client, h1, question_id=80)
        resp = client.put(f"/api/notes/{note_id}", json={"note_text": "updated"}, headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["updated"] is True
        # 验证修改生效
        resp = client.get("/api/questions/80/notes", params={"bank_id": "bank-a", "scope": "mine"}, headers=h1)
        assert resp.json()["data"]["items"][0]["note_text"] == "updated"

    def test_non_owner_forbidden(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=90, note_text="alice's note")
        note_id = _get_note_id(client, h1, question_id=90)
        resp = client.put(f"/api/notes/{note_id}", json={"note_text": "hacked"}, headers=h2)
        assert resp.status_code == 403

    def test_not_found(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        resp = client.put("/api/notes/99999", json={"note_text": "x"}, headers=h1)
        assert resp.status_code == 404

    def test_update_text_exceeds_1000_returns_400(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=91)
        note_id = _get_note_id(client, h1, question_id=91)
        resp = client.put(f"/api/notes/{note_id}", json={"note_text": "y" * 1001}, headers=h1)
        assert resp.status_code == 400

    def test_update_visibility(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=92, visibility="public")
        note_id = _get_note_id(client, h1, question_id=92)
        resp = client.put(f"/api/notes/{note_id}", json={"visibility": "private"}, headers=h1)
        assert resp.status_code == 200
        resp = client.get("/api/questions/92/notes", params={"bank_id": "bank-a", "scope": "mine"}, headers=h1)
        assert resp.json()["data"]["items"][0]["visibility"] == "private"


# ── DELETE /api/notes/{note_id} ───────────────────────────────────────────────

class TestDeleteNote:
    def test_owner_can_delete(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=100)
        note_id = _get_note_id(client, h1, question_id=100)
        resp = client.delete(f"/api/notes/{note_id}", headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["deleted"] is True
        # 验证已删除
        resp = client.get("/api/questions/100/notes", params={"bank_id": "bank-a", "scope": "mine"}, headers=h1)
        assert resp.json()["data"]["items"] == []

    def test_non_owner_forbidden(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=110)
        note_id = _get_note_id(client, h1, question_id=110)
        resp = client.delete(f"/api/notes/{note_id}", headers=h2)
        assert resp.status_code == 403

    def test_not_found(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        resp = client.delete("/api/notes/99999", headers=h1)
        assert resp.status_code == 404


# ── POST /api/notes/{note_id}/like ────────────────────────────────────────────

class TestLikeNote:
    def test_like_increments_count(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=120, note_text="liked note")
        note_id = _get_note_id(client, h1, question_id=120)
        resp = client.post(f"/api/notes/{note_id}/like", headers=h2)
        assert resp.status_code == 200
        assert resp.json()["data"]["liked"] is True
        assert resp.json()["data"]["like_count"] == 1

    def test_like_duplicate_returns_409(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=130)
        note_id = _get_note_id(client, h1, question_id=130)
        client.post(f"/api/notes/{note_id}/like", headers=h1)
        resp = client.post(f"/api/notes/{note_id}/like", headers=h1)
        assert resp.status_code == 409

    def test_like_not_found(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        resp = client.post("/api/notes/99999/like", headers=h1)
        assert resp.status_code == 404

    def test_multiple_users_like(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=140)
        note_id = _get_note_id(client, h1, question_id=140)
        client.post(f"/api/notes/{note_id}/like", headers=h1)
        resp = client.post(f"/api/notes/{note_id}/like", headers=h2)
        assert resp.json()["data"]["like_count"] == 2


# ── DELETE /api/notes/{note_id}/like ──────────────────────────────────────────

class TestUnlikeNote:
    def test_unlike_decrements_count(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        h2, _ = _auth_headers(db, "bob")
        _create_note(client, h1, question_id=150)
        note_id = _get_note_id(client, h1, question_id=150)
        client.post(f"/api/notes/{note_id}/like", headers=h1)
        client.post(f"/api/notes/{note_id}/like", headers=h2)
        resp = client.delete(f"/api/notes/{note_id}/like", headers=h1)
        assert resp.status_code == 200
        assert resp.json()["data"]["liked"] is False
        assert resp.json()["data"]["like_count"] == 1

    def test_unlike_not_liked_returns_404(self, app_and_data):
        client, db, _ = app_and_data
        h1, _ = _auth_headers(db, "alice")
        _create_note(client, h1, question_id=160)
        note_id = _get_note_id(client, h1, question_id=160)
        resp = client.delete(f"/api/notes/{note_id}/like", headers=h1)
        assert resp.status_code == 404
