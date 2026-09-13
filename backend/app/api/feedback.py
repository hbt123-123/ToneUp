"""反馈两端点（需求文档）：用户提交 + 管理端查询/状态更新。"""
from __future__ import annotations

import sqlite3
from datetime import datetime, timezone, timedelta

from fastapi import APIRouter, Body, Depends, Query

from app.api.deps import get_current_user, require_admin
from app.core.config import get_settings
from app.core.errors import BadRequestError, ForbiddenError, NotFoundError
from app.repositories import feedback_repo
from app.schemas.common import envelope
from app.repositories.user_repo import user_connection

router = APIRouter(prefix="/api", tags=["feedback"])

VALID_CATEGORIES = {"答案有误", "解析有误", "题干有误", "图片显示异常", "其他"}
VALID_STATUSES = {"待处理", "已确认", "已忽略"}
_MAX_CONTENT_LEN = 500
_RATE_LIMIT = 10
_RATE_WINDOW_HOURS = 1


def _user_db() -> str:
    return str(get_settings().data_root / "user_data.db")


@router.post("/question-feedback")
def create_feedback(body: dict = Body(...), user=Depends(get_current_user)):
    """用户提交题目反馈，限流 10 条/小时/用户。"""
    bank_id = body.get("bank_id")
    question_id = body.get("question_id")
    category = body.get("category")
    content = body.get("content")
    image_id = body.get("image_id")

    if not bank_id:
        raise BadRequestError("bank_id is required")
    if question_id is None or not isinstance(question_id, int):
        raise BadRequestError("question_id must be an integer")
    if category not in VALID_CATEGORIES:
        raise BadRequestError(f"category must be one of {sorted(VALID_CATEGORIES)}")
    if not isinstance(content, str) or not content:
        raise BadRequestError("content is required")
    if len(content) > _MAX_CONTENT_LEN:
        raise BadRequestError(f"content must be <= {_MAX_CONTENT_LEN} chars")

    db = _user_db()

    # 限流：最近 1 小时内该用户的反馈数
    cutoff = (datetime.now(timezone.utc) - timedelta(hours=_RATE_WINDOW_HOURS)).isoformat()
    with user_connection(db) as conn:
        count = conn.execute(
            "SELECT COUNT(*) FROM question_feedback WHERE user_id = ? AND created_at >= ?",
            (user["id"], cutoff),
        ).fetchone()[0]
    if count >= _RATE_LIMIT:
        from app.core.errors import RateLimitError
        raise RateLimitError(
            f"最多{_RATE_LIMIT}条反馈/小时，请稍后再试",
            retry_after=3600,
        )

    now_iso = datetime.now(timezone.utc).isoformat()
    feedback_id = feedback_repo.create_feedback(
        db, user["id"], bank_id, question_id, category, content, image_id, now_iso,
    )
    return envelope({"feedback_id": feedback_id, "status": "待处理"})


@router.get("/admin/question-feedback")
def list_admin_feedback(
    bank_id: str | None = Query(None),
    question_id: int | None = Query(None),
    status: str | None = Query(None),
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
    user=Depends(require_admin),
):
    """管理员查看反馈列表。"""
    if status is not None and status not in VALID_STATUSES:
        raise BadRequestError(f"status must be one of {sorted(VALID_STATUSES)}")

    db = _user_db()
    rows, total = feedback_repo.list_feedback(
        db, bank_id=bank_id, question_id=question_id, status=status,
        page=page, page_size=page_size,
    )
    items = [
        {
            "feedback_id": r["id"],
            "user_id": r["user_id"],
            "bank_id": r["bank_id"],
            "question_id": r["question_id"],
            "category": r["category"],
            "content": r["content"],
            "image_id": r["image_id"],
            "status": r["status"],
            "created_at": r["created_at"],
        }
        for r in rows
    ]
    return envelope({"items": items, "total": total})


@router.put("/admin/question-feedback/status")
def update_feedback_status(body: dict = Body(...), user=Depends(require_admin)):
    """管理员更新反馈状态。"""
    feedback_id = body.get("feedback_id")
    new_status = body.get("status")

    if not feedback_id:
        raise BadRequestError("feedback_id is required")
    if new_status not in VALID_STATUSES:
        raise BadRequestError(f"status must be one of {sorted(VALID_STATUSES)}")

    db = _user_db()
    with user_connection(db) as conn:
        row = conn.execute(
            "SELECT id FROM question_feedback WHERE id = ?", (feedback_id,)
        ).fetchone()
        if row is None:
            raise NotFoundError("feedback not found")
        conn.execute(
            "UPDATE question_feedback SET status = ? WHERE id = ?",
            (new_status, feedback_id),
        )
    return envelope({"feedback_id": feedback_id, "status": new_status})
