"""统一题目 DTO（需求文档 §4.4）与 options 双格式归一化（§4.2）。

- 数学库 options 为 JSON 对象 {"A": "..."} -> 归一化为 [{"label","text"}] 按 label 升序
- 英语库 options 为 JSON 数组 [{"label","text"}] -> 白名单字段校验后透传
- 列表接口不携带 answer_text / solution；详情与提交结果接口允许
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field

import structlog

logger = structlog.get_logger()


@dataclass
class QuestionDTO:
    bank_id: str
    question_id: int
    collection_id: int
    year: int
    type_code: str
    number: int
    content: str
    passage: str | None = None
    options: list | None = None
    sub_questions: list | None = None
    display_order: int = 0
    answer_text: str | None = field(default=None)
    solution: str | None = field(default=None)

    def to_public_dict(self, include_answer: bool = False) -> dict:
        """序列化；列表用 include_answer=False，详情/结果用 True。"""
        d = {
            "bank_id": self.bank_id,
            "question_id": self.question_id,
            "collection_id": self.collection_id,
            "year": self.year,
            "type_code": self.type_code,
            "number": self.number,
            "content": self.content,
            "passage": self.passage,
            "options": self.options,
            "sub_questions": self.sub_questions,
            "display_order": self.display_order,
        }
        if include_answer:
            d["answer_text"] = self.answer_text
            d["solution"] = self.solution
        return d


def normalize_options(raw: str | None) -> list | None:
    """双格式归一化：数学对象 -> label 升序数组；英语数组白名单透传。"""
    if raw is None or not raw.strip():
        return None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("options_json_invalid")
        return None

    if isinstance(parsed, dict):
        items = [
            {"label": str(k).strip().upper(), "text": v}
            for k, v in parsed.items()
            if isinstance(v, (str, int, float))
        ]
        items.sort(key=lambda x: x["label"])
        return items

    if isinstance(parsed, list):
        out = []
        for item in parsed:
            if isinstance(item, dict) and "label" in item and "text" in item:
                out.append({"label": str(item["label"]), "text": item["text"]})
        return out or None

    logger.warning("options_unexpected_shape", type=type(parsed).__name__)
    return None


def normalize_sub_questions(raw: str | None) -> list | None:
    """sub_questions JSON 透传（仅校验合法性）。"""
    if raw is None or not raw.strip():
        return None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        logger.warning("sub_questions_json_invalid")
        return None
    return parsed if isinstance(parsed, list) else None
