"""Request context management with contextvars and middleware.

- contextvar: request_id_var 用于在请求/响应周期中传递 request_id
- 中间件：读取 X-Request-ID 头部，不存在则生成 uuid4().hex 并 set 到 contextvar
- 响应阶段：application/json 响应将 request_id 注入 body
"""

import json
import time
import uuid as _uuid
from contextlib import asynccontextmanager
from contextvars import ContextVar
from typing import Any

import structlog
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
        started = time.perf_counter()
        response: Response = await call_next(request)

        # --- 阶段3：如果是 JSON 响应，注入 request_id 到 body ---
        if "application/json" in response.headers.get("content-type", ""):
            # 读取原 body（body_iterator 将被耗尽，任何分支都必须重建 Response）
            # M-305：bytearray 累加，避免 bytes += bytes 对大响应的 O(n²) 反复拷贝
            body_buf = bytearray()
            async for chunk in response.body_iterator:
                body_buf += chunk

            headers = dict(response.headers)
            # 重建 Response；必须剔除描述旧 body 的头（body 长度/编码可能已变）
            # M-306：content-encoding 同样描述旧 body——若透传，客户端会按
            # 压缩体解码重建后的明文 JSON 而失败
            headers.pop("content-length", None)
            headers.pop("content-encoding", None)

            body_json: Any = None
            if body_buf:
                try:
                    body_json = json.loads(bytes(body_buf))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    # 解析失败：内容原样透传（H-99：返回已耗尽 iterator 的
                    # 原 response 会让客户端收到空响应体）
                    structlog.get_logger().debug(
                        "request_id_body_inject_failed", request_id=rid
                    )

            if isinstance(body_json, dict):
                body_json.setdefault("request_id", rid)
                response = Response(
                    content=json.dumps(body_json, ensure_ascii=False),
                    status_code=response.status_code,
                    media_type="application/json",
                    headers=headers,
                )
            else:
                # 非 dict JSON（list/scalar）、解析失败或空 body：
                # 内容原样透传，request_id 保留在响应头（H-98：不得丢弃真实响应数据）
                response = Response(
                    content=bytes(body_buf),
                    status_code=response.status_code,
                    headers=headers,
                )

        # --- 阶段4：access log——每条请求一条结构化事件，request_id 由处理器注入 ---
        structlog.get_logger().info(
            "http_request",
            request_id=rid,
            method=request.method,
            path=request.url.path,
            status_code=response.status_code,
            duration_ms=round((time.perf_counter() - started) * 1000, 1),
        )

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
