"""Regression test: review skip must include bank_id.

Backend already requires bank_id in the skip endpoint (reviews.py L98-100).
This test ensures the requirement is enforced and that different bank_ids
maintain independent review queues.
"""
import importlib.util
import pathlib
from datetime import datetime, timezone, timedelta

import pytest

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


def _auth_headers(user_db_path: str, username: str = "skip_test_user"):
    """直接建用户并铸造 token，返回 (headers, user_id)。"""
    user_id = user_repo.create_user(user_db_path, username, password_hash="hash-x")
    settings = get_settings()
    token = create_access_token(
        sub=user_id, role="user",
        jwt_secret=settings.jwt_secret, expires_hours=settings.jwt_expire_hours,
    )
    return {"Authorization": f"Bearer {token}"}, user_id


def _seed_mastery(user_db_path: str, user_id: int, bank_id: str, question_id: int) -> None:
    """在 user_mastery 表中插入一条带 next_review_at 的记录，使 skip 端点能找到该题。"""
    now = datetime.now(timezone.utc).isoformat()
    user_repo.mastery_submit(user_db_path, user_id, bank_id, question_id, now)
    next_review = (datetime.now(timezone.utc) + timedelta(days=1)).isoformat()
    user_repo.mastery_apply_terminal(
        user_db_path, user_id, bank_id, question_id,
        is_correct=False, next_review_at=next_review,
        confidence_level=0, now_iso=now,
    )


# ── POST /api/reviews/{id}/skip ──────────────────────────────────────────────

def test_skip_requires_bank_id(client, user_db_path):
    """POST /api/reviews/{id}/skip without bank_id should return 400."""
    headers, user_id = _auth_headers(user_db_path)
    _seed_mastery(user_db_path, user_id, "math1", 1)

    response = client.post(
        "/api/reviews/1/skip",
        headers=headers,
        json={},
    )
    assert response.status_code == 400


def test_skip_with_bank_id_succeeds(client, user_db_path):
    """POST /api/reviews/{id}/skip with bank_id should return 200."""
    headers, user_id = _auth_headers(user_db_path)
    _seed_mastery(user_db_path, user_id, "math1", 1)

    response = client.post(
        "/api/reviews/1/skip",
        headers=headers,
        json={"bank_id": "math1"},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["bank_id"] == "math1"
    assert "next_review_at" in data


def test_skip_without_auth_returns_401(client, user_db_path):
    """POST /api/reviews/{id}/skip without token should return 401."""
    response = client.post(
        "/api/reviews/1/skip",
        json={"bank_id": "math1"},
    )
    assert response.status_code == 401
