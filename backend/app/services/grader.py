"""客观题自动判分（需求文档 §7）与间隔重复调度（§9.1）。

判分规则：
- SINGLE/READING：标签全等比对
- CLOZE：逐空比对，score = 答对空数/总空数 × 题目分值；is_correct 仅全对
- ORDERING：顺序数组逐位比对，完全一致才 is_correct

复习规则（固定间隔，FSRS 字段中立预留）：
- 答错 -> confidence_level=0，next_review_at = now + REVIEW_RETRY_HOURS
- 答对 -> level+1 封顶 5；间隔表 {1:1d, 2:3d, 3:7d, 4:14d, 5:30d}；level=5 毕业 next_review_at=NULL
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

REVIEW_INTERVAL_DAYS = {1: 1, 2: 3, 3: 7, 4: 14, 5: 30}
MAX_CONFIDENCE_LEVEL = 5


def grade_objective(
    type_code: str,
    user_answer,
    answer_struct,
    question_score: float,
) -> tuple[bool | None, float | None]:
    """客观题判分。返回 (is_correct, score)。

    answer_struct 为 bank_repo.parse_answer 的输出；
    解析失败（None）时返回 (None, None)，由调用方按异常路径处理。
    user_answer 形态：
      SINGLE/READING: "A"
      CLOZE: ["B","D",...]
      ORDERING: ["3","1","2",...] 或 "312"
    """
    if answer_struct is None:
        return None, None

    if type_code in ("SINGLE", "READING"):
        if not isinstance(user_answer, str):
            return False, 0.0
        correct = user_answer.strip().upper() == str(answer_struct).strip().upper()
        return correct, (question_score if correct else 0.0)

    if type_code == "CLOZE":
        if isinstance(user_answer, str):
            user_list = [ch for ch in user_answer.strip() if ch.strip()]
        elif isinstance(user_answer, list):
            user_list = [str(x).strip().upper() for x in user_answer]
        else:
            return False, 0.0
        total = len(answer_struct)
        if total == 0:
            return None, None
        correct_count = sum(
            1 for i, ans in enumerate(answer_struct)
            if i < len(user_list) and user_list[i].upper() == str(ans).strip().upper()
        )
        score = round(question_score * correct_count / total, 4)
        all_correct = correct_count == total and len(user_list) == total
        return all_correct, score

    if type_code == "ORDERING":
        if isinstance(user_answer, str):
            user_list = [ch for ch in user_answer.strip() if ch.strip()]
        elif isinstance(user_answer, list):
            user_list = [str(x).strip() for x in user_answer]
        else:
            return False, 0.0
        expected = [str(x).strip() for x in answer_struct]
        all_correct = user_list == expected
        return all_correct, (question_score if all_correct else 0.0)

    return None, None


def review_policy(
    current_level: int,
    is_correct: bool,
    retry_hours: int = 4,
    now: datetime | None = None,
) -> tuple[int, str | None]:
    """根据作答结果推进 confidence_level 与 next_review_at。返回 (new_level, next_review_at ISO)。"""
    if now is None:
        now = datetime.now(timezone.utc)

    if not is_correct:
        next_at = now + timedelta(hours=retry_hours)
        return 0, next_at.isoformat()

    new_level = min(current_level + 1, MAX_CONFIDENCE_LEVEL)
    if new_level >= MAX_CONFIDENCE_LEVEL:
        return new_level, None
    interval_days = REVIEW_INTERVAL_DAYS[new_level]
    next_at = now + timedelta(days=interval_days)
    return new_level, next_at.isoformat()
