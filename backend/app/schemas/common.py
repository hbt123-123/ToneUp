"""Common schemas: envelope response wrapper and pagination helper.

- envelope(data, message, success) 返回 {"success":..., "data":..., "message":..., "request_id":...}
- page(items, total, has_more) 返回分页信息 dict
"""

from __future__ import annotations

from contextvars import ContextVar

from app.core.request_context import request_id_var


def envelope(data=None, message: str = "", success: bool = True) -> dict:
    """构建统一的 API 响应信封。

    返回的 dict 包含：
    - success: 操作是否成功
    - data: 业务数据（可选）
    - message: 提示消息（可选）
    - request_id: 当前上下文的 request_id
    """
    rid = request_id_var.get()
    return {
        "success": success,
        "data": data,
        "message": message,
        "request_id": rid,
    }


def page(items: list, total: int, has_more: bool) -> dict:
    """构建分页响应信息。

    返回的 dict 包含：
    - items: 当前页数据列表
    - total: 总记录数
    - has_more: 是否有更多页
    """
    return {
        "items": items,
        "total": total,
        "has_more": has_more,
    }