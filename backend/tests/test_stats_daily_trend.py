"""Tests for GET /api/stats/daily-trend — EC-03 近 N 天作答趋势（任务 10）。

fixture 照 test_practice_sessions_api.py 的 app_and_data 模式；
作答记录直接 SQL 插入 practice_records（可控 created_at）。
"""

from __future__ import annotations

import importlib.util
import pathlib
import shutil
import sqlite3
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app.core.bank_registry import get_registry, reset_registry
from app.core.config import get_settings
from app.core.security import create_access_token
from app.main import create_app
from app.repositories import user_repo

_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db


def _auth_headers(user_db_path, username="alice") -> dict:
    user_id = user_repo.create_user(user_db_path, username, password_hash="hash-x")
    settings = get_settings()
    token = create_access_token(
        sub=user_id, role="user",
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
    monkeypatch.setenv("JWT_SECRET", "x" * 32)
    get_settings.cache_clear()
    reset_registry()
    app = create_app()
    client = TestClient(app)
    user_db_path = str(tmp_path / "user_data.db")
    init_user_db(user_db_path)

    return client, user_db_path, tmp_path


def _insert_record(db: str, user_id: int, bank_id: str, question_id: int,
                   is_correct: int | None, created_at: str) -> None:
    conn = sqlite3.connect(db)
    try:
        with conn:
            conn.execute(
                "INSERT INTO practice_records (user_id, bank_id, question_id, user_answer, "
                "is_correct, score, time_spent, mode, client_request_id, created_at) "
                "VALUES (?, ?, ?, 'A', ?, 1.0, 30, 'practice', ?, ?)",
                (user_id, bank_id, question_id, is_correct,
                 f"cri-{question_id}-{created_at}", created_at),
            )
    finally:
        conn.close()


def _utc_today() -> str:
    return datetime.now(timezone.utc).date().isoformat()


def _utc_days_ago(n: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=n)).isoformat()


# ── 鉴权与参数校验 ───────────────────────────────────────────────────────────

def test_unauthorized_returns_401(app_and_data):
    client, _, _ = app_and_data
    resp = client.get("/api/stats/daily-trend")
    assert resp.status_code == 401


def test_days_out_of_range_returns_400(app_and_data):
    """QA failure：days=0 / days=61 → 400。

    注：项目全局把 RequestValidationError 统一映射为 400 信封
    （app/core/errors.py:94 既有设计，overview 的 from/to 同此），
    计划原文写 422 系假设值，落地遵循既有范式 400。
    """
    client, user_db_path, _ = app_and_data
    headers, _ = _auth_headers(user_db_path)
    assert client.get("/api/stats/daily-trend", headers=headers, params={"days": 0}).status_code == 400
    assert client.get("/api/stats/daily-trend", headers=headers, params={"days": 61}).status_code == 400


# ── 聚合口径（QA happy）──────────────────────────────────────────────────────

def test_three_day_aggregation_with_gap_fill(app_and_data):
    """跨 3 天记录 → 3 点聚合正确 + 窗口内空天补零。"""
    client, user_db_path, _ = app_and_data
    headers, user_id = _auth_headers(user_db_path)

    today = _utc_today()
    yesterday = (datetime.now(timezone.utc) - timedelta(days=1)).date().isoformat()
    # today: 2 条记录，同 (bank,question) 去重 → 1 题，最新判分为错
    _insert_record(user_db_path, user_id, "b1", 101, 1, f"{today}T08:00:00+00:00")
    _insert_record(user_db_path, user_id, "b1", 101, 0, f"{today}T10:00:00+00:00")
    # yesterday: 2 题不同题 → 2 attempts，1 对 1 错
    _insert_record(user_db_path, user_id, "b1", 102, 1, f"{yesterday}T09:00:00+00:00")
    _insert_record(user_db_path, user_id, "b2", 103, 0, f"{yesterday}T09:30:00+00:00")
    # 3 天前：窗口外，不计入（days=3）
    three_days_ago = (datetime.now(timezone.utc) - timedelta(days=3)).date().isoformat()
    _insert_record(user_db_path, user_id, "b1", 104, 1, f"{three_days_ago}T09:00:00+00:00")

    resp = client.get("/api/stats/daily-trend", headers=headers, params={"days": 3})
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["days"] == 3
    points = data["points"]
    assert len(points) == 3
    # days=3 → 窗口 = [today-2, today-1, today]；3 天前的记录在窗口外，不计入
    window_start = (datetime.now(timezone.utc) - timedelta(days=2)).date().isoformat()
    assert [p["date"] for p in points] == [window_start, yesterday, today]

    # 窗口首日无记录 → 补零（3 天前的记录被排除）
    assert points[0] == {"date": window_start, "attempts": 0, "correct_rate": 0.0}
    # 昨天：2 attempts，1/2
    assert points[1]["attempts"] == 2
    assert points[1]["correct_rate"] == 0.5
    # 今天：同题去重取最新判分（错）→ 1 attempt, 0.0
    assert points[2]["attempts"] == 1
    assert points[2]["correct_rate"] == 0.0


def test_empty_user_returns_all_zero_points(app_and_data):
    client, user_db_path, _ = app_and_data
    headers, _ = _auth_headers(user_db_path)
    resp = client.get("/api/stats/daily-trend", headers=headers, params={"days": 7})
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["days"] == 7
    assert len(data["points"]) == 7
    assert all(p["attempts"] == 0 and p["correct_rate"] == 0.0 for p in data["points"])


def test_default_days_is_14(app_and_data):
    client, user_db_path, _ = app_and_data
    headers, _ = _auth_headers(user_db_path)
    resp = client.get("/api/stats/daily-trend", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["data"]["days"] == 14
    assert len(resp.json()["data"]["points"]) == 14
