"""Application errors and exception handlers.

- AppError: 基类，封装 status_code, message, code
- 子类：BadRequestError(400)/UnauthorizedError(401)/ForbiddenError(403)/NotFoundError(404)/ConflictError(409)/RateLimitError(429，额外属性 retry_after:int)
- register_exception_handlers(app)：注册全局异常处理器
"""

from __future__ import annotations

import typing as t

from fastapi import FastAPI, Request
from starlette.exceptions import HTTPException as StarletteHTTPException
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.schemas.common import envelope


# ── AppError 基类 ──────────────────────────────────────────────────────

class AppError(Exception):
    """自定义应用异常基类。

    Attributes:
        status_code: HTTP 状态码
        message: 返回给客户端的消息
        code: 业务错误码（可选）
    """

    def __init__(self, status_code: int, message: str, code: str = "") -> None:
        self.status_code = status_code
        self.message = message
        self.code = code
        super().__init__(message)


# ── 具体错误子类 ──────────────────────────────────────────────────────


class BadRequestError(AppError):
    """400 Bad Request。"""

    def __init__(self, message: str = "Bad request", code: str = "") -> None:
        super().__init__(status_code=400, message=message, code=code)


class UnauthorizedError(AppError):
    """401 Unauthorized。"""

    def __init__(self, message: str = "Unauthorized", code: str = "") -> None:
        super().__init__(status_code=401, message=message, code=code)


class ForbiddenError(AppError):
    """403 Forbidden。"""

    def __init__(self, message: str = "Forbidden", code: str = "") -> None:
        super().__init__(status_code=403, message=message, code=code)


class NotFoundError(AppError):
    """404 Not Found。"""

    def __init__(self, message: str = "Not found", code: str = "") -> None:
        super().__init__(status_code=404, message=message, code=code)


class ConflictError(AppError):
    """409 Conflict。"""

    def __init__(self, message: str = "Conflict", code: str = "") -> None:
        super().__init__(status_code=409, message=message, code=code)


class RateLimitError(AppError):
    """429 Too Many Requests。额外属性 retry_after:int（秒）。"""

    def __init__(
        self, message: str = "Rate limited", code: str = "", retry_after: int = 60
    ) -> None:
        super().__init__(status_code=429, message=message, code=code)
        self.retry_after = retry_after


# ── 异常处理器注册 ──────────────────────────────────────────────────────

def register_exception_handlers(app: FastAPI) -> None:
    """注册全局异常处理器。

    处理优先级：
    1. AppError → JSONResponse(envelope(message=message, success=False), status_code=status_code)
    2. starlette HTTPException → 同信封(detail 作 message，handler 的 headers 透传给响应头)
    3. RequestValidationError → 400 信封（摘要进 message）
    """

    @app.exception_handler(AppError)
    async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
        """AppError 统一走信封格式；429 附带 Retry-After。"""
        env = envelope(message=exc.message, success=False)
        headers = {}
        if isinstance(exc, RateLimitError):
            headers["Retry-After"] = str(exc.retry_after)
        return JSONResponse(content=env, status_code=exc.status_code, headers=headers)

    @app.exception_handler(StarletteHTTPException)
    async def starlette_http_exception_handler(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        """starlette HTTPException 映射为信封格式，detail 作为 message。"""
        env = envelope(message=exc.detail, success=False)
        headers = dict(exc.headers) if exc.headers else {}
        return JSONResponse(content=env, status_code=exc.status_code, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        """FastAPI 请求校验错误 → 400 信封，使用错误摘要。"""
        errors = exc.errors()
        if errors:
            msg = errors[0].get("msg", str(errors))
            summary = msg if isinstance(msg, str) else str(msg)
        else:
            summary = "Validation error"
        env = envelope(message=summary, success=False)
        return JSONResponse(content=env, status_code=400)
