"""认证三端点（需求文档 §6.1 / §10.1 / §10.3）。"""
from __future__ import annotations

import re
from datetime import datetime, timezone

from fastapi import APIRouter, Body, Depends, Request

from app.api.deps import get_current_user
from app.core.config import get_settings
from app.core.errors import BadRequestError, UnauthorizedError
from app.core.ratelimit import check_login_limit, client_ip, rate_limit_dep
from app.core.security import create_access_token, hash_password, verify_password
from app.repositories import user_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/auth", tags=["auth"])

_USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,32}$")

register_rate_limit = rate_limit_dep("register", 5, 3600, "ip")


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


@router.post("/register")
def register(
    request: Request,
    body: dict = Body(...),
    _: None = Depends(register_rate_limit),
):
    """注册：用户名 3-32 位 [A-Za-z0-9_]；密码 ≥8；重名 400（限流 5 次/小时/IP）。"""
    username = (body.get("username") or "").strip()
    password = body.get("password") or ""
    if not _USERNAME_RE.match(username):
        raise BadRequestError("username must be 3-32 chars of letters/digits/underscore")
    if len(password) < 8:
        raise BadRequestError("password must be at least 8 characters")

    settings = get_settings()
    try:
        user_id = user_repo.create_user(_user_db(), username, hash_password(password))
    except user_repo.UserExistsError as exc:
        raise BadRequestError("username already exists") from exc
    return envelope({"user_id": user_id, "username": username}, message="registered")


@router.post("/login")
def login(request: Request, body: dict = Body(...)):
    """登录：失败统一 400 不区分原因（限流 10 次/分钟/IP+用户名）。"""
    username = (body.get("username") or "").strip()
    password = body.get("password") or ""
    settings = get_settings()
    ip = client_ip(request, settings.trusted_proxy_count)
    check_login_limit("login", ip, username or "-", 10, 60)

    user = user_repo.get_user_by_username(_user_db(), username) if username else None
    if user is None or not verify_password(password, user["password_hash"]):
        raise UnauthorizedError("invalid credentials")

    token = create_access_token(
        sub=user["id"], role=user["role"],
        jwt_secret=settings.jwt_secret, expires_hours=settings.jwt_expire_hours,
    )
    return envelope({
        "access_token": token,
        "token_type": "bearer",
        "expires_in": settings.jwt_expire_hours * 3600,
        "user_id": user["id"],
        "role": user["role"],
    })


@router.get("/me")
def me(user=Depends(get_current_user)):
    """当前用户信息。"""
    return envelope({"user_id": user["id"], "username": user["username"], "role": user["role"]})
