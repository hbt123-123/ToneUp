"""Tests for backend/app/api/feedback.py — feedback endpoints."""

from __future__ import annotations

import importlib.util
import pathlib
import shutil
import sqlite3

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

VALID_CATEGORY = "答案有误"
FEEDBACK_BODY = {
    "bank_id": "math1",
    "question_id": 1,
    "category": VALID_CATEGORY,
    "content": "答案应该是C而不是B",
}


def _auth_headers(user_db_path, username="alice", role="user"):
    user_id = user_repo.create_user(user_db_path, username, password_hash="hash-x")
    if role != "user":
        with sqlite3.connect(user_db_path) as conn:
            conn.execute("UPDATE users SET role = ? WHERE id = ?", (role, user_id))
            conn.commit()
    settings = get_settings()
    token = create_access_token(
        sub=user_id, role=role,
        jwt_secret=settings.jwt_secret, expires_hours=settings.jwt_expire_hours,
    )
    return {"Authorization": f"Bearer {token}"}, user_id


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
    app = create_app()
    client = TestClient(app)
    user_db_path = str(tmp_path / "user_data.db")
    init_user_db(user_db_path)
    migrate_mobile_tables(user_db_path)
    return client, user_db_path, tmp_path


# ── POST /api/question-feedback ──────────────────────────────

class TestCreateFeedback:
    def test_unauthorized_401(self, app_and_data):
        client, _, _ = app_and_data
        resp = client.post("/api/question-feedback", json=FEEDBACK_BODY)
        assert resp.status_code == 401

    def test_success(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        resp = client.post("/api/question-feedback", json=FEEDBACK_BODY, headers=headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        data = body["data"]
        assert "feedback_id" in data
        assert data["status"] == "待处理"

    def test_missing_bank_id_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        bad = {**FEEDBACK_BODY, "bank_id": ""}
        resp = client.post("/api/question-feedback", json=bad, headers=headers)
        assert resp.status_code == 400

    def test_invalid_question_id_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        bad = {**FEEDBACK_BODY, "question_id": "abc"}
        resp = client.post("/api/question-feedback", json=bad, headers=headers)
        assert resp.status_code == 400

    def test_invalid_category_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        bad = {**FEEDBACK_BODY, "category": "无效分类"}
        resp = client.post("/api/question-feedback", json=bad, headers=headers)
        assert resp.status_code == 400

    def test_content_too_long_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        bad = {**FEEDBACK_BODY, "content": "x" * 501}
        resp = client.post("/api/question-feedback", json=bad, headers=headers)
        assert resp.status_code == 400

    def test_empty_content_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        bad = {**FEEDBACK_BODY, "content": ""}
        resp = client.post("/api/question-feedback", json=bad, headers=headers)
        assert resp.status_code == 400

    def test_optional_image_id(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path)
        body = {**FEEDBACK_BODY, "image_id": "img-123"}
        resp = client.post("/api/question-feedback", json=body, headers=headers)
        assert resp.status_code == 200

    def test_rate_limit_10_per_hour(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path, username="rate_user")
        for i in range(10):
            body = {**FEEDBACK_BODY, "content": f"反馈{i}"}
            resp = client.post("/api/question-feedback", json=body, headers=headers)
            assert resp.status_code == 200, f"第{i+1}条应成功"
        # 第11条应被限流
        body = {**FEEDBACK_BODY, "content": "第11条"}
        resp = client.post("/api/question-feedback", json=body, headers=headers)
        assert resp.status_code == 429


# ── GET /api/admin/question-feedback ──────────────────────────

class TestListAdminFeedback:
    def test_non_admin_403(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path, role="user")
        resp = client.get("/api/admin/question-feedback", headers=headers)
        assert resp.status_code == 403

    def test_unauthorized_401(self, app_and_data):
        client, _, _ = app_and_data
        resp = client.get("/api/admin/question-feedback")
        assert resp.status_code == 401

    def test_success_empty(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path, username="admin1", role="admin")
        resp = client.get("/api/admin/question-feedback", headers=headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["success"] is True
        assert body["data"]["items"] == []
        assert body["data"]["total"] == 0

    def test_with_data(self, app_and_data):
        client, user_db_path, _ = app_and_data
        user_headers, _ = _auth_headers(user_db_path, username="bob")
        # 先提交一条反馈
        client.post("/api/question-feedback", json=FEEDBACK_BODY, headers=user_headers)
        # 管理员查询
        admin_headers, _ = _auth_headers(user_db_path, username="admin2", role="admin")
        resp = client.get("/api/admin/question-feedback", headers=admin_headers)
        assert resp.status_code == 200
        assert resp.json()["data"]["total"] == 1

    def test_filter_by_status(self, app_and_data):
        client, user_db_path, _ = app_and_data
        user_headers, _ = _auth_headers(user_db_path, username="carol")
        client.post("/api/question-feedback", json=FEEDBACK_BODY, headers=user_headers)
        admin_headers, _ = _auth_headers(user_db_path, username="admin3", role="admin")
        resp = client.get(
            "/api/admin/question-feedback?status=已确认", headers=admin_headers
        )
        assert resp.status_code == 200
        assert resp.json()["data"]["total"] == 0

    def test_invalid_status_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        admin_headers, _ = _auth_headers(user_db_path, username="admin4", role="admin")
        resp = client.get(
            "/api/admin/question-feedback?status=非法状态", headers=admin_headers
        )
        assert resp.status_code == 400

    def test_pagination(self, app_and_data):
        client, user_db_path, _ = app_and_data
        user_headers, _ = _auth_headers(user_db_path, username="dave")
        for i in range(5):
            body = {**FEEDBACK_BODY, "content": f"分页反馈{i}"}
            client.post("/api/question-feedback", json=body, headers=user_headers)
        admin_headers, _ = _auth_headers(user_db_path, username="admin5", role="admin")
        resp = client.get(
            "/api/admin/question-feedback?page=1&page_size=2", headers=admin_headers
        )
        assert resp.status_code == 200
        data = resp.json()["data"]
        assert len(data["items"]) == 2
        assert data["total"] == 5


# ── PUT /api/admin/question-feedback/status ───────────────────

class TestUpdateFeedbackStatus:
    def test_non_admin_403(self, app_and_data):
        client, user_db_path, _ = app_and_data
        headers, _ = _auth_headers(user_db_path, role="user")
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"feedback_id": "fake", "status": "已确认"},
            headers=headers,
        )
        assert resp.status_code == 403

    def test_unauthorized_401(self, app_and_data):
        client, _, _ = app_and_data
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"feedback_id": "fake", "status": "已确认"},
        )
        assert resp.status_code == 401

    def test_invalid_status_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        admin_headers, _ = _auth_headers(user_db_path, username="admin6", role="admin")
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"feedback_id": "fake-id", "status": "非法状态"},
            headers=admin_headers,
        )
        assert resp.status_code == 400

    def test_not_found_404(self, app_and_data):
        client, user_db_path, _ = app_and_data
        admin_headers, _ = _auth_headers(user_db_path, username="admin7", role="admin")
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"feedback_id": "nonexistent-id", "status": "已确认"},
            headers=admin_headers,
        )
        assert resp.status_code == 404

    def test_success_update(self, app_and_data):
        client, user_db_path, _ = app_and_data
        user_headers, _ = _auth_headers(user_db_path, username="eve")
        # 创建反馈
        resp = client.post(
            "/api/question-feedback", json=FEEDBACK_BODY, headers=user_headers
        )
        feedback_id = resp.json()["data"]["feedback_id"]
        # 管理员更新状态
        admin_headers, _ = _auth_headers(user_db_path, username="admin8", role="admin")
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"feedback_id": feedback_id, "status": "已确认"},
            headers=admin_headers,
        )
        assert resp.status_code == 200
        assert resp.json()["data"]["status"] == "已确认"

    def test_update_to_ignored(self, app_and_data):
        client, user_db_path, _ = app_and_data
        user_headers, _ = _auth_headers(user_db_path, username="frank")
        resp = client.post(
            "/api/question-feedback", json=FEEDBACK_BODY, headers=user_headers
        )
        feedback_id = resp.json()["data"]["feedback_id"]
        admin_headers, _ = _auth_headers(user_db_path, username="admin9", role="admin")
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"feedback_id": feedback_id, "status": "已忽略"},
            headers=admin_headers,
        )
        assert resp.status_code == 200
        assert resp.json()["data"]["status"] == "已忽略"

    def test_missing_feedback_id_400(self, app_and_data):
        client, user_db_path, _ = app_and_data
        admin_headers, _ = _auth_headers(user_db_path, username="admin10", role="admin")
        resp = client.put(
            "/api/admin/question-feedback/status",
            json={"status": "已确认"},
            headers=admin_headers,
        )
        assert resp.status_code == 400
