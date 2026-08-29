"""端点契约核对（需求文档 §6.1-6.9 全集）+ OpenAPI 断言。"""
from __future__ import annotations

EXPECTED_ENDPOINTS = {
    ("POST", "/api/auth/register"),
    ("POST", "/api/auth/login"),
    ("GET", "/api/auth/me"),
    ("GET", "/api/catalog"),
    ("GET", "/api/question-banks/{bank_id}"),
    ("GET", "/api/question-banks/{bank_id}/questions"),
    ("GET", "/api/question-banks/{bank_id}/questions/{question_id}"),
    ("GET", "/api/images/{image_id}"),
    ("POST", "/api/attempts"),
    ("GET", "/api/attempts/{attempt_id}"),
    ("GET", "/api/reviews/today"),
    ("POST", "/api/reviews/{question_id}/skip"),
    ("GET", "/api/stats/overview"),
    ("GET", "/api/stats/weaknesses"),
    ("GET", "/api/questions/{question_id}/notes"),
    ("PUT", "/api/questions/{question_id}/notes"),
    ("POST", "/api/ai/feedback"),
    ("GET", "/api/ai/feedback/{feedback_id}"),
    ("POST", "/api/admin/catalog/reload"),
    ("GET", "/api/admin/health"),
    ("GET", "/api/admin/health/{task_id}"),
    ("GET", "/api/wrong-questions"),
    ("POST", "/api/wrong-questions"),
    ("DELETE", "/api/wrong-questions/{wrong_id}"),
    ("POST", "/api/wrong-questions/sync"),
    ("POST", "/api/backgrounds/upload"),
    ("GET", "/api/backgrounds/{filename}"),
}


def test_openapi_contains_all_contract_endpoints(client):
    schema = client.get("/openapi.json").json()
    actual = set()
    for path, methods in schema["paths"].items():
        for method in methods:
            if method in ("get", "post", "put", "delete", "patch"):
                actual.add((method.upper(), path))
    missing = EXPECTED_ENDPOINTS - actual
    extra = {a for a in actual if a not in EXPECTED_ENDPOINTS}
    assert not missing, f"missing endpoints: {sorted(missing)}"
    assert not extra, f"unexpected endpoints: {sorted(extra)}"
    assert len(EXPECTED_ENDPOINTS) == 27
