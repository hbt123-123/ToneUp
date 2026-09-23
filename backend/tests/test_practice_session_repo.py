"""Tests for backend/app/repositories/practice_session_repo.py。

使用 importlib 加载 scripts/init_user_db.py 建 tmp 库（含 EC-01 两表 DDL），
每个测试独立临时数据库；并发用例验证条件更新幂等 submit。
"""

import importlib.util
import pathlib
import sqlite3
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.repositories import practice_session_repo as repo
from app.repositories import user_repo

# ── via importlib (not package import)，符合项目规范 ──────────────────────────
_p = pathlib.Path(__file__).resolve().parents[1] / "scripts" / "init_user_db.py"
_spec = importlib.util.spec_from_file_location("init_user_db", _p)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

init_user_db = _mod.init_user_db


# ── fixtures / helpers ───────────────────────────────────────────────────────
@pytest.fixture()
def db(tmp_path) -> str:
    """每个测试一个独立 tmp 用户库。"""
    db_path = str(tmp_path / "user_data.db")
    init_user_db(db_path)
    return db_path


def _create_user(db_path: str, username: str = "alice") -> int:
    return user_repo.create_user(db_path, username, password_hash="hash-x")


def _make_session(db_path: str, uid: int, qids=None, bank_id="bank-math") -> int:
    if qids is None:
        qids = [1, 2, 3]
    return repo.create_session(db_path, uid, bank_id, "数学冲刺", qids, len(qids))


def _attempt(db_path: str, uid: int, bank_id: str, qid: int,
             is_correct: bool, cri: str) -> None:
    """插入一条已判分的练习记录（insert + 终态回填）。"""
    aid, _ = user_repo.insert_attempt(
        db_path, uid, bank_id, qid, "ans", 30, "practice", cri, "2026-09-23T00:00:00+00:00"
    )
    assert user_repo.update_attempt_result(db_path, aid, is_correct, 1.0)


def _fetch(db_path: str, sql: str, params: tuple = ()):
    """独立只读连接（显式 busy_timeout，防并发场景 SQLITE_BUSY）。"""
    conn = sqlite3.connect(db_path, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    try:
        return conn.execute(sql, params).fetchall()
    finally:
        conn.close()


# ── Test 1: 创建 → 查询往返（归属校验 + items 序列） ─────────────────────────
def test_create_and_get_roundtrip(db):
    uid = _create_user(db)
    sid = _make_session(db, uid, [11, 22, 33])

    row = repo.get_session(db, uid, sid)
    assert row is not None
    assert row["bank_id"] == "bank-math"
    assert row["title"] == "数学冲刺"
    assert row["status"] == "active"
    assert row["total_count"] == 3
    assert row["current_index"] == 0
    assert row["draft_json"] == "{}"

    items = repo.get_items(db, sid)
    assert [r["question_id"] for r in items] == [11, 22, 33]
    assert [r["position"] for r in items] == [0, 1, 2]


# ── Test 2: 归属校验——非本人查询返回 None ────────────────────────────────────
def test_get_session_ownership(db):
    uid_a = _create_user(db, "alice")
    uid_b = _create_user(db, "bob")
    sid = _make_session(db, uid_a)

    assert repo.get_session(db, uid_a, sid) is not None
    assert repo.get_session(db, uid_b, sid) is None  # 非本人
    assert repo.get_session(db, uid_a, 99999) is None  # 不存在


# ── Test 3: 草稿更新往返 + 已提交会话拒绝（返回 None） ───────────────────────
def test_update_draft_roundtrip_and_reject_after_submit(db):
    uid = _create_user(db)
    sid = _make_session(db, uid)

    result = repo.update_draft(db, uid, sid, 2, {"q0": "A", "q1": "C"}, 120)
    assert result is not None
    assert result["current_index"] == 2

    row = repo.get_session(db, uid, sid)
    assert row["current_index"] == 2
    assert row["elapsed_seconds"] == 120

    # 交卷后 draft 更新必须被拒
    repo.submit_session(db, uid, sid, "req-submit-1")
    assert repo.update_draft(db, uid, sid, 3, {"q2": "B"}, 200) is None


# ── Test 4: 首次 submit → 重放 submit：同摘要、replayed=True、单行不变 ───────
def test_submit_then_replay_same_summary(db):
    uid = _create_user(db)
    sid = _make_session(db, uid, [1, 2, 3])
    repo.update_draft(db, uid, sid, 1, {"q0": "A"}, 60)
    _attempt(db, uid, "bank-math", 1, True, "cri-a1")
    _attempt(db, uid, "bank-math", 2, False, "cri-a2")
    _attempt(db, uid, "bank-math", 2, True, "cri-a3")  # 同题二次作答，去重后仍 2 题

    summary, replayed = repo.submit_session(db, uid, sid, "req-submit-1")
    assert replayed is False
    # 口径 = submit 时点 practice_records 现状：曾答对即计入 correct
    assert summary == {
        "total": 3,
        "answered": 2,  # q1、q2 去重
        "correct": 2,   # q1 对；q2 先错后对，曾答对亦计入
        "accuracy_rate": 1.0,
        "elapsed_seconds": 60,
    }

    rows_before = _fetch(db, "SELECT * FROM practice_session WHERE id = ?", (sid,))

    # 同 request_id 重放
    summary2, replayed2 = repo.submit_session(db, uid, sid, "req-submit-1")
    assert replayed2 is True
    assert summary2 == summary

    # 不同 request_id 重放（会话已 submitted，条件更新 rowcount==0）
    summary3, replayed3 = repo.submit_session(db, uid, sid, "req-submit-2")
    assert replayed3 is True
    assert summary3 == summary

    rows_after = _fetch(db, "SELECT * FROM practice_session WHERE id = ?", (sid,))
    assert len(rows_after) == 1  # 单行不变
    assert rows_after[0]["status"] == "submitted"
    assert rows_after[0]["client_request_id"] == "req-submit-1"
    # 重放不得改动行内容（updated_at 除外，逐字段比对业务列）
    before, after = rows_before[0], rows_after[0]
    for col in ("id", "user_id", "bank_id", "title", "status",
                "total_count", "current_index", "draft_json", "elapsed_seconds"):
        assert before[col] == after[col]

    # practice_records 不因重放增多
    n = _fetch(db, "SELECT COUNT(*) FROM practice_records")[0][0]
    assert n == 3


# ── Test 5: submit 会话不存在/非本人 → (None, True) ──────────────────────────
def test_submit_unknown_or_foreign_session(db):
    uid_a = _create_user(db, "alice")
    uid_b = _create_user(db, "bob")
    sid = _make_session(db, uid_a)

    assert repo.submit_session(db, uid_a, 99999, "req-x") == (None, True)
    summary, replayed = repo.submit_session(db, uid_b, sid, "req-y")
    assert summary is None and replayed is True
    # 未被误提交
    assert repo.get_session(db, uid_a, sid)["status"] == "active"


# ── Test 6: 8 线程并发对同一会话同 request_id submit ─────────────────────────
def test_concurrent_submit_same_session_idempotent(db):
    uid = _create_user(db)
    sid = _make_session(db, uid, [1, 2, 3])
    _attempt(db, uid, "bank-math", 1, True, "cri-c1")
    _attempt(db, uid, "bank-math", 3, False, "cri-c2")
    rows_before = _fetch(db, "SELECT COUNT(*) FROM practice_records")[0][0]

    def _submit(_: int):
        return repo.submit_session(db, uid, sid, "req-conc-1")

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(_submit, range(8)))

    # 恰一次首次提交，其余全部重放；summary 完全一致
    assert sum(1 for _, r in results if r is False) == 1
    assert sum(1 for _, r in results if r is True) == 7
    summaries = [s for s, _ in results]
    assert all(s == summaries[0] for s in summaries)
    assert summaries[0]["answered"] == 2
    assert summaries[0]["correct"] == 1

    # 最终状态：单行 submitted，practice_records 无重复计分
    rows = _fetch(db, "SELECT status FROM practice_session WHERE id = ?", (sid,))
    assert len(rows) == 1 and rows[0][0] == "submitted"
    rows_after = _fetch(db, "SELECT COUNT(*) FROM practice_records")[0][0]
    assert rows_after == rows_before


# ── Test 7: 同一 client_request_id 提交两个不同会话 → IntegrityError 分支 ────
def test_submit_same_request_id_two_sessions_integrity_branch(db):
    uid = _create_user(db)
    sid_a = _make_session(db, uid, [1, 2])
    sid_b = _make_session(db, uid, [3, 4])

    summary_a, replayed_a = repo.submit_session(db, uid, sid_a, "req-dup")
    assert replayed_a is False

    # 会话 B 用同一 request_id：UNIQUE(user_id, client_request_id) 冲突，
    # 照 insert_attempt 范式回查既有行 → replayed=True，不抛异常
    summary_b, replayed_b = repo.submit_session(db, uid, sid_b, "req-dup")
    assert replayed_b is True
    assert summary_b is not None

    # 恰一次成功：B 仍为 active（其 UPDATE 被回滚），A 已 submitted
    assert repo.get_session(db, uid, sid_a)["status"] == "submitted"
    assert repo.get_session(db, uid, sid_b)["status"] == "active"


# ── Test 8: list_sessions 分页 + answered 去重统计 ───────────────────────────
def test_list_sessions_pagination_and_answered(db):
    uid = _create_user(db)
    sid1 = _make_session(db, uid, [1, 2, 3])
    sid2 = _make_session(db, uid, [4, 5], bank_id="bank-eng")

    _attempt(db, uid, "bank-math", 1, True, "cri-e1")
    _attempt(db, uid, "bank-math", 2, True, "cri-e2")
    _attempt(db, uid, "bank-math", 2, False, "cri-e3")  # 同题重复作答

    items, total = repo.list_sessions(db, uid, page=1, page_size=1)
    assert total == 2
    assert len(items) == 1

    items2, _ = repo.list_sessions(db, uid, page=2, page_size=1)
    ids = {items[0]["id"], items2[0]["id"]}
    assert ids == {sid1, sid2}

    answered_by_id = {items[0]["id"]: items[0]["answered"],
                      items2[0]["id"]: items2[0]["answered"]}
    assert answered_by_id[sid1] == 2  # 三题答两题（q2 重复作答去重）
    assert answered_by_id[sid2] == 0


# ── Test 9: delete_session 显式删 items + 归属校验 ───────────────────────────
def test_delete_session(db):
    uid_a = _create_user(db, "alice")
    uid_b = _create_user(db, "bob")
    sid = _make_session(db, uid_a, [1, 2, 3])

    assert repo.delete_session(db, uid_b, sid) is False  # 非本人
    assert repo.delete_session(db, uid_a, 99999) is False  # 不存在

    assert repo.delete_session(db, uid_a, sid) is True
    assert repo.get_session(db, uid_a, sid) is None
    assert repo.get_items(db, sid) == []
    assert repo.delete_session(db, uid_a, sid) is False  # 幂等（已不存在）
