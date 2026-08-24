"""目录端点（需求文档 §6.2）：数据源为 manifest，不查库。"""
from __future__ import annotations

from fastapi import APIRouter

from app.core.bank_registry import get_registry
from app.schemas.common import envelope

router = APIRouter(prefix="/api", tags=["catalog"])


@router.get("/catalog")
def catalog():
    """subjects/types 树及各题库摘要（id、name、enabled）。"""
    registry = get_registry()
    subjects = registry.subjects_raw or []
    banks = [
        {"id": e.id, "subject_id": e.subject_id, "type_id": e.type_id,
         "name": e.name, "enabled": e.enabled}
        for e in registry.entries.values()
    ]
    return envelope({"subjects": subjects, "banks": banks})
