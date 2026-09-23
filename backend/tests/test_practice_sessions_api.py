"""Tests for backend/app/api/practice_sessions.py — EC-01 七端点。

fixture 照 test_sections_api.py 的 app_and_data 模式：复制题库数据 +
init_user_db 建 tmp 用户库 + 直接 create_access_token 鉴权。
"""

from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import uuid

import pytest
from fastapi.testclient import TestClient

from app.core.bank_registry import get_registry, reset_registry
from app.core.config import get_settings
from app.core.security import create_access_token
from app.main import create_app
from app.repositories import bank_repo, user_repo

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
    monkeypatch.setenv("JWT_SECRET", "x" * 32)
    get_settings.cache_clear()
    reset_registry()
    app = create_app()
    client = TestClient(app)
    user_db_path = str(tmp_path / "user_data.db")
    init_user_db(user_db_path)

    return client, user_db_path, tmp_path


def _post_attempt(client, headers, bank_id, question_id, cri) -> dict:
    """走既有判分链路提交一条作答（SINGLE 客观题同步判分）。"""
    resp = client.post(
        "/api/attempts",
        headers=headers,
        json={
            "bank_id": bank_id,
            "question_id": question_id,
            "answer": "A",
            "time_spent": 30,
            "mode": "practice",
            "client_request_id": cri,
        },
    )
    assert resp.status_code == 200, resp.text
    return resp.json()["data"]


# ── 鉴权 ─────────────────────────────────────────────────────────────────────

def test_unauthorized_returns_401(app_and_data):
    client, _, _ = app_and_data
    resp = client.get("/api/practice-sessions")
    assert resp.status_code == 401


# ── 创建 + 全链路（QA happy）──────────────────────────────────────────────────

def test_create_full_lifecycle(app_and_data):
    """创建→草稿→作答→submit→重复 submit→result→list→delete 全链路 200。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")

    # 1. 创建
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 3},
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    assert data["bank_id"] == "math1"
    assert data["total_count"] == 3
    assert len(data["questions"]) == 3
    qids = [q["question_id"] for q in data["questions"]]
    assert len(set(qids)) == 3  # 题目去重
    sid = data["session_id"]

    # 2. 草稿（PUT 不限流）
    resp = client.put(
        f"/api/practice-sessions/{sid}/draft", headers=headers,
        json={"current_index": 1, "draft": {"q0": "A"}, "elapsed_seconds": 90},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["current_index"] == 1

    # 详情可读回草稿与进度
    resp = client.get(f"/api/practice-sessions/{sid}", headers=headers)
    assert resp.status_code == 200
    detail = resp.json()["data"]
    assert detail["session"]["draft"] == {"q0": "A"}
    assert detail["session"]["status"] == "active"
    assert detail["progress"] == {"answered": 0, "total": 3}

    # 3. 经既有判分链路作答 2 题
    _post_attempt(client, headers, "math1", qids[0], f"cri-{uuid.uuid4().hex}")
    _post_attempt(client, headers, "math1", qids[1], f"cri-{uuid.uuid4().hex}")

    # 4. 首次 submit
    cri = f"submit-{uuid.uuid4().hex}"
    resp = client.post(
        f"/api/practice-sessions/{sid}/submit", headers=headers,
        json={"client_request_id": cri},
    )
    assert resp.status_code == 200, resp.text
    payload = resp.json()["data"]
    assert payload["replayed"] is False
    assert payload["summary"]["total"] == 3
    assert payload["summary"]["answered"] == 2

    # 5. 重复 submit：replayed=True、summary 不变
    resp2 = client.post(
        f"/api/practice-sessions/{sid}/submit", headers=headers,
        json={"client_request_id": cri},
    )
    assert resp2.status_code == 200
    payload2 = resp2.json()["data"]
    assert payload2["replayed"] is True
    assert payload2["summary"] == payload["summary"]

    # 6. 已提交后 draft 409
    resp = client.put(
        f"/api/practice-sessions/{sid}/draft", headers=headers,
        json={"current_index": 2, "draft": {}, "elapsed_seconds": 200},
    )
    assert resp.status_code == 409

    # 7. result
    resp = client.get(f"/api/practice-sessions/{sid}/result", headers=headers)
    assert resp.status_code == 200
    result = resp.json()["data"]
    assert [i["position"] for i in result["items"]] == [0, 1, 2]
    answered_flags = {i["question_id"]: i["answered"] for i in result["items"]}
    assert answered_flags[qids[0]] is True
    assert answered_flags[qids[1]] is True
    assert answered_flags[qids[2]] is False
    assert result["summary"]["answered"] == 2

    # 8. list
    resp = client.get("/api/practice-sessions", headers=headers,
                      params={"page": 1, "page_size": 20})
    assert resp.status_code == 200
    listing = resp.json()["data"]
    assert listing["total"] >= 1
    entry = next(x for x in listing["items"] if x["id"] == sid)
    assert entry["status"] == "submitted"
    assert entry["answered"] == 2

    # 9. delete + 复查 404
    resp = client.delete(f"/api/practice-sessions/{sid}", headers=headers)
    assert resp.status_code == 200
    assert resp.json()["message"] == "deleted"
    resp = client.get(f"/api/practice-sessions/{sid}", headers=headers)
    assert resp.status_code == 404


# ── 选题语义 ─────────────────────────────────────────────────────────────────

def test_create_excludes_essay(app_and_data):
    """politics1（SINGLE/MULTI/ESSAY）：ESSAY 必须被排除在会话之外。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "politics1", "count": 5},
    )
    assert resp.status_code == 200, resp.text
    data = resp.json()["data"]
    type_codes = {q["type_code"] for q in data["questions"]}
    assert type_codes <= {"SINGLE", "MULTI"}
    assert "ESSAY" not in type_codes


def test_count_clamp_51_to_50(app_and_data):
    """count=51 服务端钳制为 50（200 而非 422）。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 51},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["data"]["total_count"] == 50


def test_count_zero_and_negative_400(app_and_data):
    """count=0 / 负数 → 400（项目统一约定：RequestValidationError → 400 信封）。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    for bad in (0, -3):
        resp = client.post(
            "/api/practice-sessions", headers=headers,
            json={"bank_id": "math1", "count": bad},
        )
        assert resp.status_code == 400


def test_invalid_type_code_400(app_and_data):
    """type_codes 含 ESSAY（白名单外交集为空）→ 400。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "politics1", "type_codes": ["ESSAY"], "count": 5},
    )
    assert resp.status_code == 400


def test_unanswered_first_and_fill_with_answered(app_and_data):
    """未答优先；候选不足时如实返回较小 total_count 且用已答题补齐。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")

    # 未答优先：预答 10 题，count=5 → 返回题与已答集无交集（未答充足）
    entry = get_registry().get("math1")
    conn = bank_repo.get_connection(str(entry.path))
    pool = [r["id"] for r in conn.execute(
        "SELECT id FROM questions LIMIT 10").fetchall()]
    for qid in pool:
        _post_attempt(client, headers, "math1", qid, f"cri-pre-{qid}")
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 5},
    )
    assert resp.status_code == 200
    picked = {q["question_id"] for q in resp.json()["data"]["questions"]}
    assert picked.isdisjoint(set(pool))

    # 补已答：限定单 collection 单题型并答完其全部题 → count=50
    # 返回 total_count == 候选数（如实较小），且全部来自已答
    single_in_c1 = [r["id"] for r in bank_repo.get_connection(str(entry.path)).execute(
        "SELECT id FROM questions WHERE collection_id = 1 AND question_type_id = 1"
    ).fetchall()]
    assert len(single_in_c1) < 50
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "collection_ids": [1],
              "type_codes": ["SINGLE"], "count": 50},
    )
    assert resp.status_code == 200
    data = resp.json()["data"]
    assert data["total_count"] == len(single_in_c1)
    assert {q["question_id"] for q in data["questions"]} == set(single_in_c1)


# ── 归属与错误语义 ───────────────────────────────────────────────────────────

def test_ownership_404(app_and_data):
    """用户 B 访问用户 A 的会话：GET/PUT draft/submit/DELETE 全 404。"""
    client, user_db_path, _ = app_and_data
    headers_a = _auth_headers(user_db_path, "alice")
    headers_b = _auth_headers(user_db_path, "bob")

    resp = client.post(
        "/api/practice-sessions", headers=headers_a,
        json={"bank_id": "math1", "count": 2},
    )
    sid = resp.json()["data"]["session_id"]

    assert client.get(f"/api/practice-sessions/{sid}",
                      headers=headers_b).status_code == 404
    assert client.put(f"/api/practice-sessions/{sid}/draft",
                      headers=headers_b,
                      json={"current_index": 0, "draft": {},
                            "elapsed_seconds": 0}).status_code == 404
    assert client.post(f"/api/practice-sessions/{sid}/submit",
                       headers=headers_b,
                       json={"client_request_id": "x-y-z"}).status_code == 404
    assert client.get(f"/api/practice-sessions/{sid}/result",
                      headers=headers_b).status_code == 404
    assert client.delete(f"/api/practice-sessions/{sid}",
                         headers=headers_b).status_code == 404
    # A 的会话未被误伤
    assert client.get(f"/api/practice-sessions/{sid}",
                      headers=headers_a).status_code == 200


def test_submit_request_id_reused_across_sessions_409(app_and_data):
    """幂等键跨会话复用：第二个会话 submit 返回 409，绝不下发首个会话的摘要。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    sid_a = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 2},
    ).json()["data"]["session_id"]
    sid_b = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 2},
    ).json()["data"]["session_id"]

    cri = f"reuse-{uuid.uuid4().hex}"
    assert client.post(
        f"/api/practice-sessions/{sid_a}/submit", headers=headers,
        json={"client_request_id": cri},
    ).status_code == 200

    resp = client.post(
        f"/api/practice-sessions/{sid_b}/submit", headers=headers,
        json={"client_request_id": cri},
    )
    assert resp.status_code == 409
    # B 仍为 active：未被误标为已提交
    detail_b = client.get(
        f"/api/practice-sessions/{sid_b}", headers=headers
    ).json()["data"]
    assert detail_b["session"]["status"] == "active"


def test_draft_payload_too_large_400(app_and_data):
    """draft 单次 body 超 64KB → 400；正常大小仍可写入。

    关键：中间件在 body 解析前按 Content-Length 拦截——用一个被截断的
    非法 JSON 超限体，若走到解析会得到 JSON 校验错误，此处应得到 too large，
    以此证明拦截发生在解析之前。
    """
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    sid = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 2},
    ).json()["data"]["session_id"]
    url = f"/api/practice-sessions/{sid}/draft"

    resp = client.put(
        url, headers=headers,
        json={"current_index": 0,
              "draft": {"q0": "x" * (64 * 1024)},
              "elapsed_seconds": 1},
    )
    assert resp.status_code == 400

    # 非法 JSON 的超限体：message 为 too large（中间件层），而非 JSON 校验摘要
    broken = b'{"current_index":0,"draft":{"q0":"' + b"x" * (64 * 1024)
    resp_broken = client.put(
        url, headers={**headers, "content-type": "application/json"},
        content=broken,
    )
    assert resp_broken.status_code == 400
    assert "too large" in resp_broken.json()["message"]

    ok = client.put(
        url, headers=headers,
        json={"current_index": 0, "draft": {"q0": "A"}, "elapsed_seconds": 1},
    )
    assert ok.status_code == 200


def test_draft_oversize_chunked_falls_back_to_route_check(app_and_data):
    """chunked（无 Content-Length）超限：中间件无法预判，路由解析后检查兜底。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    sid = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "math1", "count": 2},
    ).json()["data"]["session_id"]

    body = json.dumps({
        "current_index": 0,
        "draft": {"q0": "x" * (64 * 1024)},
        "elapsed_seconds": 1,
    }).encode("utf-8")

    def _chunks():
        yield body

    resp = client.put(
        f"/api/practice-sessions/{sid}/draft",
        headers={**headers, "content-type": "application/json"},
        content=_chunks(),
    )
    assert resp.status_code == 400
    assert "too large" in resp.json()["message"]


def test_list_pagination(app_and_data):
    """分页：total 正确、page_size=1 时 has_more 翻页。"""
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    for _ in range(2):
        resp = client.post(
            "/api/practice-sessions", headers=headers,
            json={"bank_id": "math1", "count": 2},
        )
        assert resp.status_code == 200

    resp = client.get("/api/practice-sessions", headers=headers,
                      params={"page": 1, "page_size": 1})
    data = resp.json()["data"]
    assert data["total"] == 2
    assert data["has_more"] is True
    assert len(data["items"]) == 1

    resp = client.get("/api/practice-sessions", headers=headers,
                      params={"page": 2, "page_size": 1})
    data2 = resp.json()["data"]
    assert data2["has_more"] is False
    assert data["items"][0]["id"] != data2["items"][0]["id"]


def test_unknown_bank_404(app_and_data):
    client, user_db_path, _ = app_and_data
    headers = _auth_headers(user_db_path, "alice")
    resp = client.post(
        "/api/practice-sessions", headers=headers,
        json={"bank_id": "nope", "count": 5},
    )
    assert resp.status_code == 404
