"""进程内滑动窗口限流器（需求文档 §10.3）。

- 单进程部署（Uvicorn workers=1），内存计数即可
- client_ip() 单处实现：uvicorn --proxy-headers 场景下按受信代理跳数取 XFF；
  IPv6 ::ffff: 映射归一
- 超限抛 RateLimitError（429 信封 + Retry-After 头由异常处理器透传）
"""
from __future__ import annotations

import json
import threading
import time
from collections import defaultdict, deque

from fastapi import Request

from app.core.errors import RateLimitError
from app.core.config import get_settings
from app.core.request_context import get_request_id
import structlog

logger = structlog.get_logger()

_windows: dict[str, deque[float]] = defaultdict(deque)
# M-302：键数超过上限即清扫最后活动超过 1 小时的键（各规则窗口均远小于
# 1 小时，长期无访问的键下次到来时必然滑空重建，删除无副作用）
_MAX_WINDOWS = 10_000
_STALE_SECONDS = 3600.0


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


_allow_lock = threading.Lock()


def allow(key: str, limit: int, window_seconds: int) -> int:
    """滑动窗口判定。返回 retry_after 秒数；0 表示放行。

    同步依赖（线程池）与异步中间件并发调用，pop/重建与追加必须互斥，
    否则并发下会互相覆盖窗口导致限流计数丢失。
    """
    now = time.monotonic()
    retry_after = 0
    with _allow_lock:
        # M-302：键数超阈值时清扫长期无访问的键——原实现只在键被再次查询
        # 且窗口滑空时回收，被 spoofed/NAT IP 打过一次就永久驻留
        if len(_windows) > _MAX_WINDOWS:
            stale = [
                k for k, q in _windows.items()
                if not q or q[-1] <= now - _STALE_SECONDS
            ]
            for k in stale:
                _windows.pop(k, None)
        q = _windows.get(key)
        if q is None:
            q = deque()
        while q and q[0] <= now - window_seconds:
            q.popleft()
        if not q:
            # 窗口滑空即回收键，防止 _windows 随 ip/user 组合无界增长（内存泄漏）
            _windows.pop(key, None)
        # M-303：limit<=0 视为整窗全拒。原实现依赖 len(q)>=limit 分支，
        # limit==0 时窗口滑空后取 q[0] 会 IndexError
        if limit <= 0:
            retry_after = max(1, window_seconds)
        elif len(q) >= limit:
            retry_after = max(1, int(window_seconds - (now - q[0])) + 1)
        else:
            q.append(now)
            _windows[key] = q
    if retry_after:
        logger.warning(
            "rate_limited",
            key_kind=key.split(":", 1)[0],  # 键以 ':' 分隔（H-97：'｜' 切分永远取不到 kind）
            retry_after=retry_after,
            request_id=get_request_id(),
        )
    return retry_after


def rate_limit_dep(rule_name: str, limit: int, window_seconds: int, dimension: str = "ip"):
    """FastAPI 依赖工厂。dimension: ip | user | ip_username。

    user / ip_username 维度需要请求上下文中的用户信息，
    由 api 层在依赖链后段二次调用 allow() 完成；本工厂覆盖 ip 维度。
    """

    def _dep(request: Request) -> None:
        # M-304：user / ip_username 维度需请求上下文中的用户信息，由路由体内
        # 显式调用 check_user_limit / check_login_limit；此处静默放行会让
        # 误配的端点完全不限流，必须 fail-fast
        if dimension != "ip":
            raise RuntimeError(
                f"rate_limit_dep: dimension={dimension!r} 不受支持；"
                "请在路由体内调用 check_user_limit / check_login_limit"
            )
        from app.core.config import get_settings

        settings = get_settings()
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


class DefaultRateLimitMiddleware:
    """§10.3 兜底档：其余接口默认 240 次/分钟/IP。

    注册顺序要求：先于 RequestIdMiddleware add（Starlette 后注册者为外层），
    使本中间件位于 request_id 上下文之内，429 响应向外穿过时被注入 request_id。
    自带专属限流的端点在 SKIP 集合中跳过。
    """

    SKIP = {
        ("POST", "/api/auth/register"),
        ("POST", "/api/auth/login"),
        ("POST", "/api/attempts"),
        ("POST", "/api/ai/feedback"),
    }
    LIMIT = 240
    WINDOW_SECONDS = 60

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        path = scope["path"]
        method = scope["method"]
        if not path.startswith("/api") or (method, path) in self.SKIP:
            await self.app(scope, receive, send)
            return

        from starlette.requests import Request

        request = Request(scope)
        settings = get_settings()
        ip = client_ip(request, settings.trusted_proxy_count)
        retry_after = allow(f"ip:default:{ip}", self.LIMIT, self.WINDOW_SECONDS)
        if retry_after == 0:
            await self.app(scope, receive, send)
            return

        from fastapi.responses import JSONResponse

        from app.schemas.common import envelope

        response = JSONResponse(
            content=envelope(message="too many requests", success=False),
            status_code=429,
            headers={"Retry-After": str(retry_after)},
        )
        rid = get_request_id()
        if rid:
            body = json.loads(response.body)
            body["request_id"] = rid
            response = JSONResponse(
                content=body, status_code=429,
                headers={"Retry-After": str(retry_after)},
            )
        await response(scope, receive, send)
