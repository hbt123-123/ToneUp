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


# ── PUT /api/favorites ────────────────────────────────────────────────────────

@router.put("")
def add_favorite(body: dict = Body(...), user=Depends(get_current_user)):
    """收藏题目（幂等：已收藏则保持）。"""
    bank_id = body.get("bank_id")
    question_id = body.get("question_id")
    if not bank_id:
        raise BadRequestError("bank_id is required")
    if question_id is None or not isinstance(question_id, int):
        raise BadRequestError("question_id must be an integer")
    db = _db()
    if not favorites_repo.is_favorited(db, user["id"], bank_id, question_id):
        favorites_repo.toggle_favorite(db, user["id"], bank_id, question_id)
    return envelope({"favorited": True})


# ── DELETE /api/favorites ─────────────────────────────────────────────────────

@router.delete("")
def remove_favorite(body: dict = Body(...), user=Depends(get_current_user)):
    """取消收藏（幂等：未收藏则无操作）。"""
    bank_id = body.get("bank_id")
    question_id = body.get("question_id")
    if not bank_id:
        raise BadRequestError("bank_id is required")
    if question_id is None or not isinstance(question_id, int):
        raise BadRequestError("question_id must be an integer")
    db = _db()
    if favorites_repo.is_favorited(db, user["id"], bank_id, question_id):
        favorites_repo.toggle_favorite(db, user["id"], bank_id, question_id)
    return envelope({"favorited": False})


# ── GET /api/favorites/banks ─────────────────────────────────────────────────

@router.get("/banks")
def list_favorite_banks(user=Depends(get_current_user)):
    """返回当前用户收藏的题库聚合列表。"""
    db = _db()
    banks = favorites_repo.list_favorite_banks(db, user["id"])
    return envelope({"banks": banks})
