"""Middleware contract tests — ≥5 cases for request_id, CORS, error envelope."""

import json

import pytest
from structlog.testing import capture_logs

from fastapi.testclient import TestClient


# ── ①：X-Request-ID 头部透传 ──────────────────────────────────────────────

def test_request_id_with_header(client):
    """带 X-Request-ID 头部请求，返回的 request_id 应等于头部值。"""
    resp = client.get("/api/health", headers={"X-Request-ID": "abc123"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["request_id"] == "abc123"


# ── ②：无头部自动生成 32 位 hex 字符串 ───────────────────────────────────

def test_request_id_without_header_is_32hex(client):
    """不带头部，request_id 应为 32 位小写 hex 字符串（uuid4().hex）。"""
    resp = client.get("/api/health")
    assert resp.status_code == 200
    body = resp.json()
    rid = body["request_id"]
    assert len(rid) == 32
    assert all(c in "0123456789abcdef" for c in rid)


# ── ③：404 错误路径携带 request_id 且 success=False ──────────────────────

def test_not_found_has_request_id_and_success_false(client):
    """GET /api/nope → 404 且 body["success"] is False 且 body 含 request_id 键。"""
    resp = client.get("/api/nope")
    assert resp.status_code == 404
    body = resp.json()
    assert body["success"] is False
    assert "request_id" in body


# ── ④：CORS 头部校验 ──────────────────────────────────────────────────────

def test_cors_evil_origin_rejected(client):
    """Origin:http://evil.com 响应无 access-control-allow-origin 头。"""
    resp = client.get(
        "/api/health",
        headers={"Origin": "http://evil.com"},
    )
    assert resp.status_code == 200
    headers = resp.headers
    assert "access-control-allow-origin" not in headers


def test_cors_allowed_origin_present(client):
    """Origin:http://allowed.dev 有 access-control-allow-origin 头。"""
    resp = client.get(
        "/api/health",
        headers={"Origin": "http://allowed.dev"},
    )
    assert resp.status_code == 200
    headers = resp.headers
    assert "access-control-allow-origin" in headers


# ── ⑤：structlog 捕获验证 request_id 注入 ──────────────────────────────────

def test_structlog_injects_request_id(client):
    """使用 capture_logs 包住 /api/health 请求，事件中含有相同 request_id 键。"""
    with capture_logs() as events:
        client.get("/api/health", headers={"X-Request-ID": "test-rid-12345"})
    # 至少有一条事件包含 request_id 键
    assert any("request_id" in ev for ev in events)
    # 且该 request_id 等于请求头中的值
    rid = next(
        (ev["request_id"] for ev in events if "request_id" in ev),
        None,
    )
    assert rid == "test-rid-12345"