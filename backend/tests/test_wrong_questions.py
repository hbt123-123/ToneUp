"""Tests for backend/app/api/wrong_questions.py — CRUD + sync endpoints."""

import importlib.util
import json
import pathlib
import sqlite3

import pytest

from app.core.bank_registry import reset_registry
from app.core.config import get_settings
from app.core.security import create_access_token
from app.repositories import user_repo

# ── via importlib (not package import)，符合项目规范 ──────────────────────────
_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db


@pytest.fixture
def user_db_path(tmp_path) -> str:
    """在 client 的 DATA_ROOT 下初始化用户库（client fixture 不自动建表）。"""
    db_path = str(tmp_path / "user_data.db")
    init_user_db(db_path)
    return db_path


def _auth_headers(user_db_path: str, username: str = "alice") -> dict:
    """直接建用户并铸造 token，返回 Authorization 头（绕过注册限流）。"""
    user_id = user_repo.create_user(user_db_path, username, password_hash="hash-x")
    settings = get_settings()
    token = create_access_token(
        sub=user_id, role="user",
        jwt_secret=settings.jwt_secret, expires_hours=settings.jwt_expire_hours,
    )
    return {"Authorization": f"Bearer {token}"}


# M-293 校验生效后 add/sync 要求 (bank_id, question_id) 真实存在，
# 故注入一个最小合法题库（collections/questions/images 齐全 + 必需列），
# 并把本文件用到的三个虚构 bank_id 登记进 manifest、重置 registry 单例
_BANK_DDL = """
CREATE TABLE collections (id INTEGER PRIMARY KEY, year TEXT);
CREATE TABLE questions (
    id INTEGER PRIMARY KEY,
    collection_id INTEGER NOT NULL,
    question_type_id INTEGER,
    number TEXT,
    content TEXT,
    options TEXT,
    sub_questions TEXT,
    answer_text TEXT,
    solution TEXT,
    score REAL,
    display_order INTEGER NOT NULL
);
CREATE TABLE images (id INTEGER PRIMARY KEY, mime TEXT);
INSERT INTO collections (id, year) VALUES (1, '2026');
"""


@pytest.fixture
def bank_env(client, tmp_path):
    """提供含 bank-math/bank-a/bank-b 的合法 manifest 与最小题库。"""
    con = sqlite3.connect(tmp_path / "bank-test.db")
    try:
        con.executescript(_BANK_DDL)
        for qid in range(8):  # 覆盖测试用到的 question_id 0..7
            con.execute(
                "INSERT INTO questions (id, collection_id, display_order) VALUES (?, 1, ?)",
                (qid, qid),
            )
        con.commit()
    finally:
        con.close()
    banks = [
        {"id": bid, "subject_id": "math", "type_id": "zhenti", "name": bid,
         "path": "bank-test.db", "schema_version": 1, "enabled": True}
        for bid in ("bank-math", "bank-a", "bank-b")
    ]
    manifest = {"subjects": [{"id": "math", "types": [{"id": "zhenti"}]}], "banks": banks}
    tmp_path.joinpath("manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False), encoding="utf-8"
    )
    reset_registry()
    yield
    reset_registry()  # 防止单例指向已删 tmp_path 污染后续测试


# ── GET /api/wrong-questions ────────────────────────────────────────────────

def test_list_empty(client, user_db_path):
    """无错题时返回空列表、total=0、has_more=False。"""
    headers = _auth_headers(user_db_path)
    resp = client.get("/api/wrong-questions", headers=headers)
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["items"] == []
    assert data["total"] == 0
    assert data["has_more"] is False


def test_list_requires_auth(client, user_db_path):
    """无 token 一律 401。"""
    resp = client.get("/api/wrong-questions")
    assert resp.status_code == 401


def test_list_after_add(client, user_db_path, bank_env):
    """添加后列表能查到该题，字段完整。"""
    headers = _auth_headers(user_db_path)
    client.post(
        "/api/wrong-questions",
        json={"bank_id": "bank-math", "question_id": 7},
        headers=headers,
    )
    resp = client.get("/api/wrong-questions", headers=headers)
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total"] == 1
    item = data["items"][0]
    assert item["bank_id"] == "bank-math"
    assert item["question_id"] == 7
    assert item["attempt_count"] == 1
    assert item["last_wrong_at"] is not None
    assert item["tags"] == []
    assert item["created_at"] is not None


def test_list_filter_by_bank(client, user_db_path, bank_env):
    """按 bank_id 过滤只返回该库错题。"""
    headers = _auth_headers(user_db_path)
    client.post("/api/wrong-questions", json={"bank_id": "bank-a", "question_id": 1}, headers=headers)
    client.post("/api/wrong-questions", json={"bank_id": "bank-b", "question_id": 2}, headers=headers)
    resp = client.get("/api/wrong-questions", params={"bank_id": "bank-a"}, headers=headers)
    data = resp.json()["data"]
    assert data["total"] == 1
    assert data["items"][0]["bank_id"] == "bank-a"


def test_list_pagination(client, user_db_path, bank_env):
    """分页：page_size=1 时 has_more=True，第二页取到剩余。"""
    headers = _auth_headers(user_db_path)
    for i in range(3):
        client.post("/api/wrong-questions", json={"bank_id": "bank-a", "question_id": i}, headers=headers)
    resp = client.get("/api/wrong-questions", params={"page": 1, "page_size": 1}, headers=headers)
    data = resp.json()["data"]
    assert data["total"] == 3
    assert len(data["items"]) == 1
    assert data["has_more"] is True
    resp2 = client.get("/api/wrong-questions", params={"page": 3, "page_size": 1}, headers=headers)
    data2 = resp2.json()["data"]
    assert len(data2["items"]) == 1
    assert data2["has_more"] is False


# ── POST /api/wrong-questions ───────────────────────────────────────────────

def test_add_new_question(client, user_db_path, bank_env):
    """新增错题 attempt_count=1。"""
    headers = _auth_headers(user_db_path)
    resp = client.post(
        "/api/wrong-questions",
        json={"bank_id": "bank-math", "question_id": 7},
        headers=headers,
    )
    assert resp.status_code == 200
    item = resp.json()["data"]
    assert item["attempt_count"] == 1


def test_add_existing_increments(client, user_db_path, bank_env):
    """重复添加同一题 attempt_count 递增且 last_wrong_at 刷新。"""
    headers = _auth_headers(user_db_path)
    client.post("/api/wrong-questions", json={"bank_id": "bank-math", "question_id": 7}, headers=headers)
    resp = client.post("/api/wrong-questions", json={"bank_id": "bank-math", "question_id": 7}, headers=headers)
    item = resp.json()["data"]
    assert item["attempt_count"] == 2


def test_add_requires_fields(client, user_db_path):
    """缺 bank_id 或 question_id 返回 400。"""
    headers = _auth_headers(user_db_path)
    resp = client.post("/api/wrong-questions", json={"bank_id": "bank-math"}, headers=headers)
    assert resp.status_code == 400
    resp2 = client.post("/api/wrong-questions", json={"question_id": 7}, headers=headers)
    assert resp2.status_code == 400


# ── DELETE /api/wrong-questions/{id} ────────────────────────────────────────

def test_delete_own_question(client, user_db_path, bank_env):
    """删除本人错题成功。"""
    headers = _auth_headers(user_db_path)
    add = client.post("/api/wrong-questions", json={"bank_id": "bank-math", "question_id": 7}, headers=headers)
    wid = add.json()["data"]["id"]
    resp = client.delete(f"/api/wrong-questions/{wid}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["data"]["deleted"] is True
    # 删除后列表为空
    lst = client.get("/api/wrong-questions", headers=headers).json()["data"]
    assert lst["total"] == 0


def test_delete_other_users_question_forbidden(client, user_db_path, bank_env):
    """删除他人错题返回 404（M-294 防枚举：与不存在不可区分）。"""
    headers_a = _auth_headers(user_db_path, "alice")
    add = client.post("/api/wrong-questions", json={"bank_id": "bank-math", "question_id": 7}, headers=headers_a)
    wid = add.json()["data"]["id"]
    headers_b = _auth_headers(user_db_path, "bob")
    resp = client.delete(f"/api/wrong-questions/{wid}", headers=headers_b)
    assert resp.status_code == 404


def test_delete_nonexistent_not_found(client, user_db_path):
    """删除不存在的 id 返回 404。"""
    headers = _auth_headers(user_db_path)
    resp = client.delete("/api/wrong-questions/99999", headers=headers)
    assert resp.status_code == 404


# ── POST /api/wrong-questions/sync ──────────────────────────────────────────

def test_sync_inserts_new(client, user_db_path, bank_env):
    """sync 批量插入新错题。"""
    headers = _auth_headers(user_db_path)
    resp = client.post(
        "/api/wrong-questions/sync",
        json={"items": [
            {"bank_id": "bank-a", "question_id": 1, "wrong_count": 3, "last_practice_at": "2026-08-01T00:00:00+00:00"},
            {"bank_id": "bank-a", "question_id": 2, "wrong_count": 1, "last_practice_at": "2026-08-02T00:00:00+00:00"},
        ]},
        headers=headers,
    )
    assert resp.status_code == 200
    assert resp.json()["data"]["synced"] == 2
    lst = client.get("/api/wrong-questions", headers=headers).json()["data"]
    assert lst["total"] == 2


def test_sync_conflict_takes_max(client, user_db_path, bank_env):
    """冲突时 attempt_count 取 MAX，last_wrong_at 取 MAX。"""
    headers = _auth_headers(user_db_path)
    client.post("/api/wrong-questions", json={"bank_id": "bank-a", "question_id": 1}, headers=headers)
    # 已有 attempt_count=1；sync 带 wrong_count=5 应取 5
    # last_practice_at 晚于现有 last_wrong_at（now），MAX 应取 sync 值
    resp = client.post(
        "/api/wrong-questions/sync",
        json={"items": [
            {"bank_id": "bank-a", "question_id": 1, "wrong_count": 5, "last_practice_at": "2099-09-01T00:00:00+00:00"},
        ]},
        headers=headers,
    )
    assert resp.status_code == 200
    lst = client.get("/api/wrong-questions", headers=headers).json()["data"]
    assert lst["total"] == 1
    item = lst["items"][0]
    assert item["attempt_count"] == 5
    assert item["last_wrong_at"] == "2099-09-01T00:00:00+00:00"


def test_sync_requires_items_list(client, user_db_path):
    """items 非列表返回 400。"""
    headers = _auth_headers(user_db_path)
    resp = client.post("/api/wrong-questions/sync", json={"items": "nope"}, headers=headers)
    assert resp.status_code == 400
