"""题库三端点（需求文档 §6.2 / §4.4）。

- GET  /api/question-banks/{bank_id}                     详情+年份区间+题型分布
- GET  /api/question-banks/{bank_id}/questions           分页列表（不含答案）
- GET  /api/question-banks/{bank_id}/questions/{qid}     完整详情（含答案与解析）
"""
from __future__ import annotations

import sqlite3

from fastapi import APIRouter, Query

from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry
from app.core.errors import BadRequestError, NotFoundError
from app.repositories import bank_repo
from app.schemas.common import envelope, page
from app.schemas.question_dto import QuestionDTO, normalize_options, normalize_sub_questions
from app.services.cleaner import clean_markdown_text
from app.services.image_refs import rewrite_image_refs
from app.core.config import get_settings

router = APIRouter(prefix="/api/question-banks", tags=["question-banks"])


def _entry_or_404(bank_id: str):
    entry = get_registry().get(bank_id)
    if entry is None:
        raise NotFoundError(f"question bank '{bank_id}' not found or disabled")
    return entry


def _build_dto(entry, row, include_answer: bool) -> dict:
    settings = get_settings()
    enabled = settings.clean_markdown
    mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
    type_code = mapping.get(row["question_type_id"], "")

    passage = None
    try:
        pid = row["passage_id"]
        if pid is not None:
            prow = bank_repo.get_passage(str(entry.path), pid)
            if prow is not None:
                passage = clean_markdown_text(prow["content"], enabled)
    except (IndexError, KeyError):
        passage = None

    dto = QuestionDTO(
        bank_id=entry.id,
        question_id=row["id"],
        collection_id=row["collection_id"],
        year=row["year"],
        type_code=type_code,
        number=row["number"],
        content=rewrite_image_refs(
            clean_markdown_text(row["content"], enabled), entry.id, True
        ),
        passage=passage,
        options=normalize_options(row["options"]),
        sub_questions=normalize_sub_questions(row["sub_questions"]),
        display_order=row["display_order"],
    )
    if include_answer:
        dto.answer_text = clean_markdown_text(row["answer_text"], enabled)
        dto.solution = clean_markdown_text(row["solution"], enabled)
    return dto.to_public_dict(include_answer=include_answer)


@router.get("/{bank_id}")
def bank_detail(bank_id: str):
    """题库详情：meta + 可用年份区间 + 题型分布。"""
    entry = _entry_or_404(bank_id)
    conn = bank_repo.get_connection(str(entry.path))
    yr = conn.execute("SELECT MIN(year), MAX(year) FROM collections").fetchone()
    dist_rows = conn.execute(
        "SELECT question_type_id, COUNT(*) AS cnt FROM questions GROUP BY question_type_id ORDER BY question_type_id"
    ).fetchall()
    mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
    distribution = [
        {"type_code": mapping.get(r["question_type_id"], f"UNKNOWN_{r['question_type_id']}"),
         "count": r["cnt"]}
        for r in dist_rows
    ]
    return envelope({
        "bank_id": entry.id,
        "name": entry.name,
        "subject_id": entry.subject_id,
        "type_id": entry.type_id,
        "enabled": entry.enabled,
        "year_min": yr[0],
        "year_max": yr[1],
        "type_distribution": distribution,
    })


@router.get("/{bank_id}/questions")
def list_questions(
    bank_id: str,
    year: int | None = None,
    type_code: str | None = None,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
):
    """分页题目列表；不含 answer_text/solution；passage 默认返回。"""
    entry = _entry_or_404(bank_id)
    type_id = None
    if type_code is not None:
        reverse = {v: k for k, v in QUESTION_TYPE_MAPPING.get(entry.subject_id, {}).items()}
        if type_code not in reverse:
            raise BadRequestError(f"invalid type_code '{type_code}' for bank '{bank_id}'")
        type_id = reverse[type_code]

    rows, total = bank_repo.list_questions(
        str(entry.path), question_type_id=type_id, year=year, page=page, page_size=page_size
    )
    items = [_build_dto(entry, r, include_answer=False) for r in rows]
    has_more = page * page_size < total
    return envelope(page(items, total, has_more))


@router.get("/{bank_id}/questions/{question_id}")
def question_detail(bank_id: str, question_id: int):
    """完整详情：含 passage、归一化 options、answer_text、solution。"""
    entry = _entry_or_404(bank_id)
    row = bank_repo.get_question(str(entry.path), question_id)
    if row is None:
        raise NotFoundError(f"question {question_id} not found")
    return envelope(_build_dto(entry, row, include_answer=True))
