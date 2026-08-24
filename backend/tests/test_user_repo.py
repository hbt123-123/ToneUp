"""Tests for backend/app/repositories/user_repo.py。

使用 importlib 加载 scripts/init_user_db.py 建 tmp 库（T4 五表 DDL），
每个测试独立临时数据库；并发用例验证 uq_attempt_request 幂等基石。
"""

import importlib.util
import pathlib
import sqlite3
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.repositories import user_repo
from app.repositories.user_repo import UserExistsError

# ── via importlib (not package import)，符合项目规范 ──────────────────────────
_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db

NOW = "2026-08-24T12:00:00+00:00"


# ── fixtures / helpers ───────────────────────────────────────────────────────
@pytest.fixture()
def db(tmp_path) -> str:
    """每个测试一个独立 tmp 用户库。"""
    db_path = str(tmp_path / "user_data.db")
    init_user_db(db_path)
    return db_path


def _create_user(db_path: str, username: str = "alice") -> int:
    return user_repo.create_user(db_path, username, password_hash="hash-x")


def _count(db_path: str, sql: str, params: tuple = ()) -> int:
    conn = sqlite3.connect(db_path)
    try:
        return int(conn.execute(sql, params).fetchone()[0])
    finally:
        conn.close()


# ── Test 1: create_user + 按名/按 id 查询往返 ────────────────────────────────
def test_create_user_roundtrip_by_username_and_id(db):
    """创建后按 username 与按 id 均能查回同一行。"""
    uid = _create_user(db, "bob")

    by_name = user_repo.get_user_by_username(db, "bob")
    by_id = user_repo.get_user_by_id(db, uid)
    assert by_name is not None and by_id is not None
    assert by_name["id"] == uid
    assert by_id["username"] == "bob"
    assert by_id["password_hash"] == "hash-x"
    assert user_repo.get_user_by_username(db, "nobody") is None
    assert user_repo.get_user_by_id(db, 99999) is None


# ── Test 2: 重复 username 抛 UserExistsError ────────────────────────────────
def test_create_user_duplicate_username_raises_user_exists_error(db):
    """同 username 二次插入转译为 UserExistsError，而非裸 IntegrityError。"""
    _create_user(db, "carol")
    with pytest.raises(UserExistsError):
        _create_user(db, "carol")


# ── Test 3: insert_attempt 幂等：首插 (id,False)，重放 (同id,True) 且单行 ────
def test_insert_attempt_duplicate_key_returns_existing_id(db):
    """同 (user_id, client_request_id) 重放返回既有 id 且不产生第二行。"""
    uid = _create_user(db)

    new_id, dup_new = user_repo.insert_attempt(
        db, uid, "bank-math", 7, "42", 30, "practice", "req-001", NOW
    )
    assert dup_new is False

    again_id, dup_again = user_repo.insert_attempt(
        db, uid, "bank-math", 7, "43", 25, "practice", "req-001", NOW
    )
    assert again_id == new_id
    assert dup_again is True
    assert _count(db, "SELECT COUNT(*) FROM practice_records") == 1


# ── Test 4: update_attempt_result NULL 守卫：首次 True，二次 False ───────────
def test_update_attempt_result_first_true_second_false(db):
    """首次判定生效；二次判定被 is_correct IS NULL 守卫拒绝且不覆盖原值。"""
    uid = _create_user(db)
    aid, _ = user_repo.insert_attempt(
        db, uid, "bank-math", 7, "42", 30, "practice", "req-002", NOW
    )

    assert user_repo.update_attempt_result(db, aid, True, 95.0) is True
    assert user_repo.update_attempt_result(db, aid, False, 10.0) is False

    row = user_repo.get_attempt(db, aid)
    assert row["is_correct"] == 1
    assert row["score"] == pytest.approx(95.0)


# ── Test 5: mastery_submit 两次 total_attempts==2 ────────────────────────────
def test_mastery_submit_twice_total_attempts_is_two(db):
    """提交两次同一题：upsert 后 total_attempts 累计为 2。"""
    uid = _create_user(db)
    user_repo.mastery_submit(db, uid, "bank-math", 7, NOW)
    user_repo.mastery_submit(db, uid, "bank-math", 7, NOW)

    conn = sqlite3.connect(db)
    try:
        row = conn.execute(
            "SELECT total_attempts, last_practice_at FROM user_mastery "
            "WHERE user_id=? AND bank_id=? AND question_id=?",
            (uid, "bank-math", 7),
        ).fetchone()
    finally:
        conn.close()
    assert row[0] == 2
    assert row[1] == NOW


# ── Test 6: mastery_apply_terminal correct_attempts 按 is_correct 增量 ──────
def test_mastery_apply_terminal_increments_by_is_correct(db):
    """is_correct=True 增 1、False 增 0，并写入置信度与下次复习时间。"""
    uid = _create_user(db)
    user_repo.mastery_submit(db, uid, "bank-math", 7, NOW)
    user_repo.mastery_submit(db, uid, "bank-math", 8, NOW)

    user_repo.mastery_apply_terminal(
        db, uid, "bank-math", 7, True, "2026-09-01T00:00:00+00:00", 3, NOW
    )
    user_repo.mastery_apply_terminal(
        db, uid, "bank-math", 8, False, "2026-08-26T00:00:00+00:00", 1, NOW
    )

    conn = sqlite3.connect(db)
    try:
        rows = dict(
            ((r[0], r[1]), r[2])
            for r in conn.execute(
                "SELECT question_id, correct_attempts, confidence_level FROM user_mastery "
                "WHERE user_id=? AND bank_id=?",
                (uid, "bank-math"),
            ).fetchall()
        )
        next_q7 = conn.execute(
            "SELECT next_review_at FROM user_mastery WHERE user_id=? AND bank_id=? AND question_id=7",
            (uid, "bank-math"),
        ).fetchone()[0]
    finally:
        conn.close()
    assert rows[(7, 1)] == 3   # 对题：correct=1, confidence=3
    assert rows[(8, 0)] == 1   # 错题：correct=0, confidence=1
    assert next_q7 == "2026-09-01T00:00:00+00:00"


# ── Test 7: notes_upsert 两次单行且文本为最新 ────────────────────────────────
def test_notes_upsert_twice_keeps_single_row_with_latest_text(db):
    """同键 upsert 两次：仍单行，note_text 为最新值。"""
    uid = _create_user(db)
    user_repo.notes_upsert(db, uid, "bank-math", 7, "v1", NOW)
    user_repo.notes_upsert(db, uid, "bank-math", 7, "v2", NOW)

    row = user_repo.notes_get(db, uid, "bank-math", 7)
    assert row is not None
    assert row["note_text"] == "v2"
    assert row["updated_at"] == NOW
    assert _count(db, "SELECT COUNT(*) FROM user_notes") == 1
    assert user_repo.notes_get(db, uid, "bank-math", 99) is None


# ── Test 8: ai_feedback 状态机守卫 ───────────────────────────────────────────
def test_ai_feedback_status_transitions_and_guard(db):
    """queued→processing→succeeded 均生效；旧状态不符的迁移被拒且状态不变。"""
    uid = _create_user(db)
    aid, _ = user_repo.insert_attempt(
        db, uid, "bank-math", 7, "42", 30, "practice", "req-003", NOW
    )
    fid = "fb-0001"
    user_repo.ai_feedback_create(db, fid, uid, aid, "bank-math", 7, "/img/a.png", NOW)

    created = user_repo.ai_feedback_get(db, fid)
    assert created["status"] == "queued"

    assert user_repo.ai_feedback_set_status(db, fid, "processing", "queued") is True
    done_at = "2026-08-24T12:01:00+00:00"
    assert user_repo.ai_feedback_set_status(
        db, fid, "succeeded", "processing",
        completed_at=done_at, tag_ids_json="[1,2]", is_correct=True,
    ) is True

    final = user_repo.ai_feedback_get(db, fid)
    assert final["status"] == "succeeded"
    assert final["completed_at"] == done_at
    assert final["tag_ids_json"] == "[1,2]"
    assert final["is_correct"] == 1

    # 守卫：期望旧状态 processing 已不成立，迁移被拒
    assert user_repo.ai_feedback_set_status(db, fid, "queued", "processing") is False
    assert user_repo.ai_feedback_get(db, fid)["status"] == "succeeded"


# ── Test 9: 8 线程并发同 key 插入：恰一行、恰一个赢家 ────────────────────────
def test_concurrent_insert_attempt_single_row_single_winner(db):
    """ThreadPoolExecutor(8) 并发同 (user_id, client_request_id)：恰好一行，
    恰一个 worker 得 (id, False)，其余全部幂等命中 (同id, True)。"""
    uid = _create_user(db)
    key = "req-concurrent"

    def worker(i: int) -> tuple[int, bool]:
        return user_repo.insert_attempt(
            db, uid, "bank-math", 7, f"ans-{i}", 10, "practice", key, NOW
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(worker, range(8)))

    ids = {r[0] for r in results}
    assert len(ids) == 1, f"所有 worker 应返回同一 id，实际 {ids}"
    winners = [r for r in results if r[1] is False]
    assert len(winners) == 1, f"应恰有一个插入者，实际 {len(winners)}"
    assert _count(
        db,
        "SELECT COUNT(*) FROM practice_records WHERE user_id=? AND client_request_id=?",
        (uid, key),
    ) == 1
