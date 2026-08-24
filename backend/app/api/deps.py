"""FastAPI 依赖：认证用户解析与管理员守卫（需求文档 §6.1 §6.9 / D4）。"""
from __future__ import annotations

from fastapi import Depends, Request

from app.core.config import get_settings
from app.core.errors import ForbiddenError, UnauthorizedError
from app.core.security import decode_token
from app.repositories import user_repo


def _user_db_path() -> str:
    return str(get_settings().data_root / "user_data.db")


def get_current_user(request: Request):
    """解析 Authorization: Bearer <token> 并加载用户；失败一律 401。"""
    auth = request.headers.get("authorization", "")
    if not auth.lower().startswith("bearer "):
        raise UnauthorizedError("missing bearer token")
    token = auth[7:].strip()
    settings = get_settings()
    try:
        payload = decode_token(token, settings.jwt_secret)
    except Exception as exc:
        raise UnauthorizedError("invalid or expired token") from exc
    try:
        user_id = int(payload.get("sub", ""))
    except (TypeError, ValueError) as exc:
        raise UnauthorizedError("invalid token subject") from exc
    user = user_repo.get_user_by_id(_user_db_path(), user_id)
    if user is None:
        raise UnauthorizedError("user not found")
    return user


def require_admin(user=Depends(get_current_user)):
    """管理员守卫：role != admin 一律 403。"""
    if user["role"] != "admin":
        raise ForbiddenError("admin role required")
    return user
