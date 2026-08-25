"""答题两端点（需求文档 §6.4 / §7 / §8.6 / 架构裁决 B2）。

- POST /api/attempts：幂等（UNIQUE user_id+client_request_id）；
  客观题同步判分；主观题建待判分流水并入队；self_judge 兜底回填
- GET  /api/attempts/{attempt_id}：仅本人
"""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

from fastapi import APIRouter, Body, Depends, Request

from app.api.deps import get_current_user
from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry
from app.core.config import get_settings
from app.core.errors import BadRequestError, ConflictError, NotFoundError
from app.core.ratelimit import check_user_limit
from app.repositories import bank_repo, user_repo
from app.schemas.common import envelope
from app.services.cleaner import clean_markdown_text
from app.services.grader import grade_objective, review_policy

router = APIRouter(prefix="/api/attempts", tags=["attempts"])

OBJECTIVE_TYPES = {"SINGLE", "READING", "CLOZE", "ORDERING"}
SUBJECTIVE_TYPES = {"FILL_BLANK", "SOLUTION", "TRANSLATION", "ESSAY"}
VALID_MODES = {"practice", "review", "self_judge"}


def _paths():
    settings = get_settings()
    return str(settings.data_root / "user_data.db")


def _locate_question(bank_id: str, question_id: int):
    entry = get_registry().get(bank_id)
    if entry is None:
        raise NotFoundError(f"question bank '{bank_id}' not found or disabled")
    row = bank_repo.get_question(str(entry.path), question_id)
    if row is None:
        raise NotFoundError(f"question {question_id} not found")
    mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
    return entry, row, mapping.get(row["question_type_id"], "")


def _attempt_payload(user_db: str, attempt_id: int, include_answer: bool) -> dict:
    a = user_repo.get_attempt(user_db, attempt_id)
    if a is None:
        raise NotFoundError("attempt not found")
    fb = None
    if a["is_correct"] is None:
        fb = user_repo.ai_feedback_get_by_attempt(user_db, attempt_id)
    if a["is_correct"] is not None:
        grading_status = "done"
    elif fb is not None:
        grading_status = fb["status"]
    else:
        grading_status = None
    data = {
        "attempt_id": a["id"],
        "bank_id": a["bank_id"],
        "question_id": a["question_id"],
        "mode": a["mode"],
        "is_correct": a["is_correct"],
        "score": a["score"],
        "created_at": a["created_at"],
        "grading_status": grading_status,
        "feedback": (
            {"status": fb["status"], "error_reason": fb["error_reason"],
             "error_message": fb["error_message"], "tag_ids": fb["tag_ids_json"]}
            if fb else None
        ),
    }
    if include_answer and a["is_correct"] is not None:
        try:
            _, qrow, type_code = _locate_question(a["bank_id"], a["question_id"])
            enabled = get_settings().clean_markdown
            data["answer_text"] = clean_markdown_text(qrow["answer_text"], enabled)
            data["solution"] = clean_markdown_text(qrow["solution"], enabled)
            data["type_code"] = type_code
        except NotFoundError:
            pass
    return data


@router.post("")
def submit_attempt(request: Request, body: dict = Body(...), user=Depends(get_current_user)):
    """提交作答。限流 120 次/分钟/用户。"""
    settings = get_settings()
    check_user_limit("attempts", user["id"], 120, 60)

    bank_id = body.get("bank_id")
    question_id = body.get("question_id")
    answer = body.get("answer")
    time_spent = body.get("time_spent")
    mode = body.get("mode")
    client_request_id = body.get("client_request_id")

    if not bank_id or question_id is None or time_spent is None or not mode or not client_request_id:
        raise BadRequestError("bank_id/question_id/time_spent/mode/client_request_id are required")
    if isinstance(question_id, bool) or not isinstance(question_id, int):
        raise BadRequestError("question_id must be an integer")
    if mode not in VALID_MODES:
        raise BadRequestError(f"mode must be one of {sorted(VALID_MODES)}")
    if not isinstance(time_spent, int) or time_spent <= 0:
        raise BadRequestError("time_spent must be positive integer seconds")

    user_db = _paths()
    now_iso = datetime.now(timezone.utc).isoformat()

    # ── 自评兜底（B2）：不新建流水、不占幂等键 ──
    if mode == "self_judge":
        if not isinstance(answer, dict) or "self_correct" not in answer:
            raise BadRequestError('self_judge requires answer={"self_correct": bool}')
        pending = user_repo.latest_pending_attempt(
            user_db, user["id"], bank_id, int(question_id)
        )
        if pending is None:
            raise ConflictError("no pending attempt to self-judge")
        self_correct = bool(answer["self_correct"])
        _, qrow, _tc = _locate_question(bank_id, int(question_id))
        score = float(qrow["score"] or 0) if self_correct else 0.0
        applied = user_repo.update_attempt_result(
            user_db, pending["id"], int(self_correct), score
        )
        if not applied:
            raise ConflictError("attempt already judged")
        mastery = user_repo.get_mastery(user_db, user["id"], bank_id, int(question_id))
        level = mastery["confidence_level"] if mastery else 0
        new_level, next_at = review_policy(level, self_correct, settings.review_retry_hours)
        user_repo.mastery_apply_terminal(
            user_db, user["id"], bank_id, int(question_id),
            int(self_correct), next_at, new_level, now_iso,
        )
        fb = user_repo.ai_feedback_get_by_attempt(user_db, pending["id"])
        if fb is not None:
            user_repo.ai_feedback_set_status(
                user_db, fb["id"], "failed", expected_old_status=fb["status"],
                completed_at=now_iso, error_message="self_judged",
            )
        return envelope(_attempt_payload(user_db, pending["id"], include_answer=True))

    # ── 先定位题目并完成客观题判分计算：校验失败不落库、不占幂等键 ──
    entry, qrow, type_code = _locate_question(str(bank_id), int(question_id))
    objective_result = None
    if type_code in OBJECTIVE_TYPES:
        parsed = bank_repo.parse_answer(qrow["answer_text"], type_code)
        objective_result = grade_objective(type_code, answer, parsed, float(qrow["score"] or 0))
        if objective_result[0] is None:
            raise BadRequestError("answer key unparsable for this question; contact admin")
    elif type_code not in SUBJECTIVE_TYPES:
        raise BadRequestError(f"unsupported question type '{type_code}'")

    # ── 幂等插入 ──
    attempt_id, replayed = user_repo.insert_attempt(
        user_db,
        user_id=user["id"], bank_id=str(bank_id), question_id=int(question_id),
        user_answer=answer if isinstance(answer, str) else json.dumps(answer, ensure_ascii=False),
        time_spent=int(time_spent), mode=mode, client_request_id=str(client_request_id),
        created_at=now_iso,
    )

    if replayed:
        return envelope(_attempt_payload(user_db, attempt_id, include_answer=True))

    # 提交即计 total_attempts（B2 两段式第一步）
    user_repo.mastery_submit(user_db, user["id"], str(bank_id), int(question_id), now_iso)

    if type_code in OBJECTIVE_TYPES:
        is_correct, score = objective_result
        applied = user_repo.update_attempt_result(user_db, attempt_id, int(is_correct), score)
        if applied:
            level, next_at = review_policy(0, is_correct, settings.review_retry_hours)
            user_repo.mastery_apply_terminal(
                user_db, user["id"], str(bank_id), int(question_id),
                int(is_correct), next_at, level, now_iso,
            )
        enabled = settings.clean_markdown
        return envelope({
            **_attempt_payload(user_db, attempt_id, include_answer=False),
            "is_correct": is_correct,
            "score": score,
            "type_code": type_code,
            "answer_text": clean_markdown_text(qrow["answer_text"], enabled),
            "solution": clean_markdown_text(qrow["solution"], enabled),
        })

    if type_code in SUBJECTIVE_TYPES:
        feedback_id = uuid.uuid4().hex
        user_repo.ai_feedback_create(
            user_db, feedback_id, user["id"], attempt_id,
            str(bank_id), int(question_id), image_path=None, created_at=now_iso,
        )
        from app.services.grading_worker import enqueue_grading, start_worker

        start_worker()
        enqueue_grading(feedback_id)
        return envelope(_attempt_payload(user_db, attempt_id, include_answer=False))

    raise BadRequestError(f"unsupported question type '{type_code}'")


@router.get("/{attempt_id}")
def get_attempt(attempt_id: int, user=Depends(get_current_user)):
    """结果详情：correctness/score/AI 判分状态与 feedback；仅本人。"""
    user_db = _paths()
    a = user_repo.get_attempt(user_db, attempt_id)
    if a is None:
        raise NotFoundError("attempt not found")
    if a["user_id"] != user["id"]:
        from app.core.errors import ForbiddenError

        raise ForbiddenError("not your attempt")
    judged_done = a["is_correct"] is not None
    return envelope(_attempt_payload(user_db, attempt_id, include_answer=judged_done))
