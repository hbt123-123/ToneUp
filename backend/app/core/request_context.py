"""Request context management with contextvars and middleware.

- contextvar: request_id_var 用于在请求/响应周期中传递 request_id
- 中间件：读取 X-Request-ID 头部，不存在则生成 uuid4().hex 并 set 到 contextvar
- 响应阶段：application/json 响应将 request_id 注入 body
"""

import uuid as _uuid
from contextlib import asynccontextmanager
from contextvars import ContextVar
from typing import Any

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.types import ASGIApp


# ── contextvar ──────────────────────────────────────────────────────────
request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)


# ── middleware helpers ──────────────────────────────────────────────────

def get_request_id() -> str:
    """Return current request_id from contextvar, generating one if absent."""
    rid = request_id_var.get()
    if rid is None:
        rid = _uuid.uuid4().hex
        request_id_var.set(rid)
    return rid


def set_request_id(rid: str) -> None:
    """Explicitly set the request_id contextvar."""
    request_id_var.set(rid)


# ── FastAPI middleware ──────────────────────────────────────────────────

class RequestIDMiddleware(BaseHTTPMiddleware):
    """FastAPI/Starlette middleware that:

    1. 请求阶段：读取 X-Request-ID 头部；若不存在则生成 uuid4().hex 并写入 contextvar
    2. 响应阶段：application/json 响应将 request_id 注入 body
    """

    async def dispatch(self, request: Request, call_next) -> Response:
        # --- 阶段1：读取/生成 request_id 并写入 contextvar（两个分支都要 set） ---
        rid = request.headers.get("X-Request-ID")
        if rid is None:
            rid = _uuid.uuid4().hex
        set_request_id(rid)

        # --- 阶段2：调用下一层 ---
        response: Response = await call_next(request)

        # --- 阶段3：如果是 JSON 响应，注入 request_id 到 body ---
        if "application/json" in response.headers.get("content-type", ""):
            # 读取原 body
            original_body = b""
            async for chunk in response.body_iterator:
                original_body += chunk

            try:
                body_json = json.loads(original_body) if original_body else {}
                if isinstance(body_json, dict):
                    body_json.setdefault("request_id", rid)
                else:
                    body_json = {"request_id": rid}
                # 重建 Response；必须剔除旧 content-length（body 长度已变）
                headers = dict(response.headers)
                headers.pop("content-length", None)
                response = Response(
                    content=json.dumps(body_json, ensure_ascii=False),
                    status_code=response.status_code,
                    media_type="application/json",
                    headers=headers,
                )
            except Exception:
                # 解析失败则原样返回（头里仍带 X-Request-ID）
                pass

        return response


# ──便捷函数：快速为 app 添加中间件 ──────────────────────────────────────

def add_request_id_middleware(app: FastAPI) -> None:
    """将 RequestIDMiddleware 加入到 FastAPI 应用。"""
    app.add_middleware(RequestIDMiddleware)


# ──便捷函数：从请求对象获取当前 request_id（处理器内部使用） ──────────

def get_request_id_from_request(request: Request) -> str:
    """从请求头 X-Request-ID 读取，若不存在则生成并返回。"""
    rid = request.headers.get("X-Request-ID")
    if rid is None:
        rid = _uuid.uuid4().hex
    return rid