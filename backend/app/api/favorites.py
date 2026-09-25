"""收藏端点（需求文档 FR-FAV）：添加/移除/聚合。"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends

from app.api.deps import get_current_user
from app.core.config import get_settings
from app.core.errors import BadRequestError
from app.repositories import favorites_repo
from app.schemas.common import envelope

router = APIRouter(prefix="/api/favorites", tags=["favorites"])


def _db() -> str:
    return str(get_settings().data_root / "user_data.db")


def _validate_fav_body(body: dict) -> tuple[str, int]:
    """PUT/DELETE 共享入参校验（M-279）：返回 (bank_id, question_id)。

    - bank_id 必须为非空字符串（原实现 `not bank_id` 会放过数字 0/列表等假值外的非串类型）
    - question_id 必须为整数且非 bool（bool 是 int 子类，True 会通过 isinstance 检查）
    """
    bank_id = body.get("bank_id")
    question_id = body.get("question_id")
    if not isinstance(bank_id, str) or not bank_id.strip():
        raise BadRequestError("bank_id is required")
    if isinstance(question_id, bool) or not isinstance(question_id, int):
        raise BadRequestError("question_id must be an integer")
    return bank_id, question_id


# ── PUT /api/favorites ────────────────────────────────────────────────────────

@router.put("")
def add_favorite(body: dict = Body(...), user=Depends(get_current_user)):
    """收藏题目（幂等：已收藏则保持）。"""
    bank_id, question_id = _validate_fav_body(body)
    db = _db()
    favorites_repo.add_favorite(db, user["id"], bank_id, question_id)
    return envelope({"favorited": True})


# ── DELETE /api/favorites ─────────────────────────────────────────────────────

@router.delete("")
def remove_favorite(body: dict = Body(...), user=Depends(get_current_user)):
    """取消收藏（幂等：未收藏则无操作）。"""
    bank_id, question_id = _validate_fav_body(body)
    db = _db()
    favorites_repo.remove_favorite(db, user["id"], bank_id, question_id)
    return envelope({"favorited": False})


# ── GET /api/favorites/banks ─────────────────────────────────────────────────

@router.get("/banks")
def list_favorite_banks(user=Depends(get_current_user)):
    """返回当前用户收藏的题库聚合列表。"""
    db = _db()
    banks = favorites_repo.list_favorite_banks(db, user["id"])
    return envelope({"banks": banks})
