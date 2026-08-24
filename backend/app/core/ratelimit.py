"""进程内滑动窗口限流器（需求文档 §10.3）。

- 单进程部署（Uvicorn workers=1），内存计数即可
- client_ip() 单处实现：uvicorn --proxy-headers 场景下按受信代理跳数取 XFF；
  IPv6 ::ffff: 映射归一
- 超限抛 RateLimitError（429 信封 + Retry-After 头由异常处理器透传）
"""
from __future__ import annotations

import time
from collections import defaultdict, deque

from fastapi import Request

from app.core.errors import RateLimitError
from app.core.request_context import get_request_id
import structlog

logger = structlog.get_logger()

_windows: dict[str, deque[float]] = defaultdict(deque)


def _normalize_ip(ip: str) -> str:
    """IPv4-mapped IPv6 归一：::ffff:1.2.3.4 -> 1.2.3.4。"""
    ip = ip.strip()
    if ip.startswith("::ffff:") and "." in ip:
        return ip[len("::ffff:"):]
    return ip


def client_ip(request: Request, trusted_proxy_count: int = 0) -> str:
    """取客户端 IP：直连用 RemoteAddr；反代按受信跳数从 XFF 右侧跳过受信代理。"""
    if trusted_proxy_count > 0:
        xff = request.headers.get("x-forwarded-for")
        if xff:
            hops = [h.strip() for h in xff.split(",") if h.strip()]
            idx = len(hops) - 1 - trusted_proxy_count
            if idx >= 0:
                return _normalize_ip(hops[idx])
    return _normalize_ip(request.client.host if request.client else "unknown")


def allow(key: str, limit: int, window_seconds: int) -> int:
    """滑动窗口判定。返回 retry_after 秒数；0 表示放行。"""
    now = time.monotonic()
    q = _windows[key]
    while q and q[0] <= now - window_seconds:
        q.popleft()
    if len(q) >= limit:
        retry_after = max(1, int(window_seconds - (now - q[0])) + 1)
        logger.warning(
            "rate_limited",
            key_kind=key.split("|", 1)[0],
            retry_after=retry_after,
            request_id=get_request_id(),
        )
        return retry_after
    q.append(now)
    return 0


def rate_limit_dep(rule_name: str, limit: int, window_seconds: int, dimension: str = "ip"):
    """FastAPI 依赖工厂。dimension: ip | user | ip_username。

    user / ip_username 维度需要请求上下文中的用户信息，
    由 api 层在依赖链后段二次调用 allow() 完成；本工厂覆盖 ip 维度。
    """

    def _dep(request: Request) -> None:
        from app.core.config import get_settings

        settings = get_settings()
        if dimension == "ip":
            key = f"ip:{rule_name}:{client_ip(request, settings.trusted_proxy_count)}"
            retry_after = allow(key, limit, window_seconds)
            if retry_after:
                raise RateLimitError(retry_after=retry_after)

    return _dep


def check_user_limit(rule_name: str, user_id: int, limit: int, window_seconds: int) -> None:
    """用户维度限流（路由体内显式调用）。超限直接抛 RateLimitError。"""
    key = f"user:{rule_name}:{user_id}"
    retry_after = allow(key, limit, window_seconds)
    if retry_after:
        raise RateLimitError(retry_after=retry_after)


def check_login_limit(rule_name: str, ip: str, username: str, limit: int, window_seconds: int) -> None:
    """登录维度：归一化 IP + 用户名（trim 后原文）组合键。"""
    key = f"ip_username:{rule_name}:{ip}:{username.strip()}"
    retry_after = allow(key, limit, window_seconds)
    if retry_after:
        raise RateLimitError(retry_after=retry_after)
