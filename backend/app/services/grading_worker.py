"""主观题后台判分 worker（需求文档 §8.4 / 架构裁决 B1、B2）。

- 守护线程消费 queue.Queue，并发度 GRADING_CONCURRENCY 默认 1
- 任务持久化于 ai_feedback 表；内存队列仅为句柄，重启不丢任务：
  启动例程 = 全量入队 status='queued' 行 + 无条件复位 processing 行再入队
- 写回守卫：update_attempt_result 的 is_correct IS NULL 条件——
  自评兜底已回填的 attempt，AI 迟到结果直接丢弃，绝不覆盖
"""
from __future__ import annotations

import json
import queue
import threading
from datetime import datetime, timezone
from pathlib import Path

import structlog

from app.core.config import get_settings
from app.repositories import bank_repo, tags_repo, user_repo
from app.services import ai_grading, glm_client

logger = structlog.get_logger()

_queue: "queue.Queue[str | None]" = queue.Queue()
_worker_started = False
_SENTINEL = None


def enqueue_grading(feedback_id: str) -> None:
    """把待判分任务放入内存队列。"""
    _queue.put(feedback_id)


def start_worker() -> None:
    """启动守护线程（幂等）。"""
    global _worker_started
    if _worker_started:
        return
    concurrency = max(1, get_settings().grading_concurrency)
    for i in range(concurrency):
        t = threading.Thread(target=_loop, name=f"grading-worker-{i}", daemon=True)
        t.start()
    _worker_started = True
    logger.info("grading_workers_started", count=concurrency)


def stop_worker() -> None:
    """优雅停机：投递哨兵取消消费循环；in-flight 行保持 processing 由下次启动恢复。"""
    concurrency = max(1, get_settings().grading_concurrency)
    for _ in range(concurrency):
        _queue.put(_SENTINEL)


def recover_pending(user_db_path: str) -> int:
    """启动恢复：queued 全量入队；processing 行无条件复位为 queued 再入队。返回入队数。

    仅在 lifespan 启动阶段运行一次，此时 worker 无可消费任务、外部流量
    未进入（单进程独占假设）：仍处于 processing 的行必然属于已崩溃的
    旧进程，可安全复位——不按 created_at 判 stale，避免短间隔重启导致
    任务永久滞留，也避免把正在处理的行误复位造成并发双跑。
    """
    import sqlite3

    conn = sqlite3.connect(user_db_path)
    try:
        conn.execute(
            "UPDATE ai_feedback SET status='queued' "
            "WHERE status='processing' AND completed_at IS NULL"
        )
        pending = [r[0] for r in conn.execute(
            "SELECT id FROM ai_feedback WHERE status='queued'"
        ).fetchall()]
        conn.commit()
    finally:
        conn.close()
    for fid in pending:
        enqueue_grading(fid)
    if pending:
        logger.info("grading_recovery_enqueued", count=len(pending))
    return len(pending)


def _load_question_context(bank_id: str, question_id: int):
    """经 registry 白名单定位题库并读取题干/答案/解析/分值/type_code。"""
    from app.core.bank_registry import QUESTION_TYPE_MAPPING, get_registry

    entry = get_registry().get(bank_id)
    if entry is None:
        return None
    row = bank_repo.get_question(str(entry.path), question_id)
    if row is None:
        return None
    mapping = QUESTION_TYPE_MAPPING.get(entry.subject_id, {})
    return {
        "content": row["content"],
        "answer_text": row["answer_text"],
        "solution": row["solution"],
        "score": row["score"],
        "subject": entry.subject_id,
        "type_code": mapping.get(row["question_type_id"], ""),
    }


def _process_one(feedback_id: str, user_db_path: str, tags_db_path: str) -> None:
    settings = get_settings()
    fb = user_repo.ai_feedback_get(user_db_path, feedback_id)
    if fb is None or fb["status"] != "queued":
        return

    if not user_repo.ai_feedback_set_status(
        user_db_path, feedback_id, "processing", expected_old_status="queued"
    ):
        return

    try:
        ctx = _load_question_context(fb["bank_id"], fb["question_id"])
        if ctx is None:
            raise glm_client.GlmError(f"question {fb['question_id']} not found in bank {fb['bank_id']}")

        attempt = user_repo.get_attempt(user_db_path, fb["attempt_id"])
        if attempt is None:
            raise glm_client.GlmError(f"attempt {fb['attempt_id']} not found")
        user_answer = attempt["user_answer"]

        tags_list = tags_repo.list_tags_by_subject(tags_db_path, ctx["subject"])
        prompt = ai_grading.build_prompt(
            type_code=ctx["type_code"],
            content=ctx["content"],
            answer_text=ctx["answer_text"],
            solution=ctx["solution"],
            user_answer=user_answer,
            tags_list=tags_list,
            question_score=ctx["score"],
        )
        image_path = fb["image_path"]
        image_bytes = None
        if image_path:
            try:
                image_bytes = Path(image_path).read_bytes()
            except OSError as exc:
                raise glm_client.GlmError(f"image file missing: {exc}") from exc

        result = ai_grading.grade_with_retry(prompt, image_bytes=image_bytes)

        valid_tag_ids = ai_grading.filter_tag_ids(tags_db_path, ctx["subject"], result.get("tag_ids"))
        if not valid_tag_ids and result.get("tag_ids"):
            logger.info("tag_ids_filtered_empty", feedback_id=feedback_id)

        is_correct = bool(result["is_correct"])
        raw_score = result.get("score")
        cap = float(ctx["score"]) if ctx["score"] is not None else None
        score = None
        if isinstance(raw_score, (int, float)) and cap is not None:
            score = max(0.0, min(float(raw_score), cap))

        applied = user_repo.update_attempt_result(
            user_db_path, fb["attempt_id"], int(is_correct), score
        )
        if applied:
            level, next_at = _advance_review(user_db_path, fb, is_correct)
            user_repo.mastery_apply_terminal(
                user_db_path,
                fb["user_id"], fb["bank_id"], fb["question_id"],
                int(is_correct), next_at, level,
                datetime.now(timezone.utc).isoformat(),
            )
        else:
            logger.info("ai_result_discarded_attempt_judged", feedback_id=feedback_id)

        now = datetime.now(timezone.utc).isoformat()
        user_repo.ai_feedback_set_status(
            user_db_path, feedback_id, "succeeded", expected_old_status="processing",
            completed_at=now,
            error_reason=result.get("error_reason"),
            tag_ids_json=json.dumps(valid_tag_ids),
            raw_response=json.dumps(result, ensure_ascii=False),
            is_correct=int(is_correct),
        )
    except glm_client.GlmUnavailable as exc:
        logger.warning("glm_unavailable_feedback_failed", feedback_id=feedback_id)
        user_repo.ai_feedback_set_status(
            user_db_path, feedback_id, "failed", expected_old_status="processing",
            completed_at=datetime.now(timezone.utc).isoformat(),
            error_message=str(exc),
        )
    except Exception as exc:
        logger.error("grading_failed", feedback_id=feedback_id, error=str(exc))
        user_repo.ai_feedback_set_status(
            user_db_path, feedback_id, "failed", expected_old_status="processing",
            completed_at=datetime.now(timezone.utc).isoformat(),
            error_message=str(exc)[:500],
        )


def _advance_review(user_db_path: str, fb, is_correct: bool) -> tuple[int, str | None]:
    from app.services.grader import review_policy

    mastery = user_repo.get_mastery(user_db_path, fb["user_id"], fb["bank_id"], fb["question_id"])
    current_level = mastery["confidence_level"] if mastery else 0
    return review_policy(current_level, is_correct, get_settings().review_retry_hours)


_MAX_TASK_RETRIES = 3
_retry_counts: dict[str, int] = {}


def _mark_failed_best_effort(feedback_id: str, exc: Exception) -> None:
    """重试耗尽后把仍停留在 queued 的行显式置为 failed；DB 不可用时仅记日志。"""
    try:
        settings = get_settings()
        user_repo.ai_feedback_set_status(
            str(settings.data_root / "user_data.db"),
            feedback_id,
            "failed",
            expected_old_status="queued",
            completed_at=datetime.now(timezone.utc).isoformat(),
            error_message=str(exc)[:500],
        )
    except Exception:
        logger.error("worker_loop_mark_failed_error", feedback_id=feedback_id)


def _loop() -> None:
    while True:
        item = _queue.get()
        if item is _SENTINEL:
            break
        try:
            settings = get_settings()
            _process_one(
                item,
                str(settings.data_root / "user_data.db"),
                str(settings.data_root / "knowledge_tags.db"),
            )
            _retry_counts.pop(item, None)
        except Exception as exc:
            attempts = _retry_counts.get(item, 0) + 1
            _retry_counts[item] = attempts
            logger.error(
                "worker_loop_error", feedback_id=item, attempt=attempts, error=str(exc)
            )
            if attempts < _MAX_TASK_RETRIES:
                # 出队后、状态翻转前失败：重新入队，避免任务静默滞留
                _queue.put(item)
            else:
                _retry_counts.pop(item, None)
                _mark_failed_best_effort(item, exc)
        finally:
            _queue.task_done()
