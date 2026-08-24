"""主观题 AI 判分服务（需求文档 §8）。

- Prompt：System 约束（只能从给定标签选择、严禁捏造、输出 JSON）+ 按题型评分维度
- 输出 JSON Schema 校验失败自动重试一次，再失败置 failed
- tag_ids 过滤：不存在或不属于当前学科的剔除；空存 [] 不阻塞判分
- FILL_BLANK 确定性优先：LaTeX 归一化比对高置信时直接出结果，不调 AI
"""
from __future__ import annotations

import json
import re

import structlog

from app.repositories import tags_repo
from app.services import glm_client

logger = structlog.get_logger()

RESULT_SCHEMA_KEYS = {"is_correct": bool, "error_reason": str, "tag_ids": list, "score": (int, float)}

_DIMENSIONS = {
    "FILL_BLANK": "评分维度：最终结果正确性、关键过程。输出 score 与 is_correct 与 error_reason。",
    "SOLUTION": "评分维度：结果正确、步骤完整性、逻辑严谨、书写规范。按步骤分解给分，输出 score。",
    "TRANSLATION": "评分维度：忠实度、通顺度、重点词句处理。输出 score 与中文点评(error_reason)。",
    "ESSAY": "评分维度：切题程度、结构组织、语言质量。输出 score 与中文点评(error_reason)。",
}


def build_prompt(
    type_code: str,
    content: str,
    answer_text: str | None,
    solution: str | None,
    user_answer: str,
    tags_list: list[dict],
    question_score: float | None,
) -> str:
    """组装约束 Prompt（System + User 合一文本，JSON Schema 要求内嵌）。"""
    tag_lines = (
        "\n".join(f"{t['id']}. {t['tag_name']}" for t in tags_list)
        if tags_list
        else "（当前学科暂无标签，tag_ids 返回空数组即可）"
    )
    score_line = f"满分 {question_score} 分。" if question_score else ""
    dimension = _DIMENSIONS.get(type_code, "判断作答正确性并给出中文说明。")
    return (
        "你是一位考研辅导专家。请根据题目、标准答案和用户作答，判断对错并分析。\n"
        f"必须从下方提供的标签列表中选择 1-3 个最匹配的知识点标签 id，输出合法 JSON。\n"
        "严禁捏造标签列表之外的内容。\n\n"
        "[JSON 格式要求]\n"
        '{"is_correct": true/false, "score": 数字, "error_reason": "中文原因/点评", "tag_ids": [整数]}\n\n'
        f"[{dimension}] {score_line}\n\n"
        f"[可用标签列表]\n{tag_lines}\n\n"
        f"[题目]\n{content}\n\n"
        f"[标准答案]\n{answer_text or '（无）'}\n\n"
        f"[官方解析]\n{solution or '（无）'}\n\n"
        f"[用户作答]\n{user_answer}"
    )


def validate_model_output(raw: str) -> dict:
    """从模型输出提取并校验 JSON；不合法抛 ValueError。"""
    m = re.search(r"\{.*\}", raw, re.DOTALL)
    if not m:
        raise ValueError("no JSON object in model output")
    data = json.loads(m.group(0))
    if not isinstance(data, dict) or not isinstance(data.get("is_correct"), bool):
        raise ValueError("is_correct boolean missing")
    if "score" in data and not isinstance(data["score"], RESULT_SCHEMA_KEYS["score"]):
        raise ValueError("score must be number")
    if "tag_ids" in data and not isinstance(data["tag_ids"], list):
        raise ValueError("tag_ids must be array")
    return data


def normalize_latex(text: str) -> str:
    """FILL_BLANK 确定性比对用的归一化：去空白/排版命令/全半角映射。"""
    s = text.strip()
    s = re.sub(r"\\left|\\right", "", s)
    s = re.sub(r"\\[a-zA-Z]+", "", s)
    s = re.sub(r"[{}$\\^_]", "", s)
    s = re.sub(r"\s+", "", s)
    table = str.maketrans("０１２３４５６７８９（）＋－＝×÷，．", "0123456789()+-=*/,.")
    return s.translate(table)


def try_deterministic_fill_blank(user_answer: str, reference: str) -> bool | None:
    """确定性归一化比对。高置信一致 True / 不一致 False；无法判定 None。"""
    u, r = normalize_latex(user_answer), normalize_latex(reference)
    if not u or not r:
        return None
    if u == r:
        logger.info("fill_blank_deterministic_hit", verdict=True)
        return True
    if len(u) <= 24 and len(r) <= 24 and u != r:
        logger.info("fill_blank_deterministic_hit", verdict=False)
        return False
    return None


def filter_tag_ids(tags_db_path: str, subject: str, tag_ids) -> list[int]:
    """tag_ids 存在性与学科归属过滤；非法输入返回 []。"""
    if not isinstance(tag_ids, list):
        return []
    ints = [t for t in tag_ids if isinstance(t, int)]
    return tags_repo.filter_valid_tag_ids(tags_db_path, subject, ints)


def grade_with_retry(
    prompt: str,
    image_bytes: bytes | None = None,
    mime: str = "image/jpeg",
    max_attempts: int = 2,
    http_client=None,
) -> dict:
    """调用 GLM 并做 Schema 校验；失败自动重试一次，再失败抛 GlmError。

    返回 validate_model_output 的 dict。
    """
    last_error: Exception | None = None
    for attempt in range(1, max_attempts + 1):
        payload = (
            glm_client.build_vision_payload(prompt, image_bytes, mime)
            if image_bytes is not None
            else glm_client.build_text_payload(prompt)
        )
        raw = glm_client.chat(payload, client=http_client)
        try:
            return validate_model_output(raw)
        except (ValueError, json.JSONDecodeError) as exc:
            last_error = exc
            logger.warning("glm_output_schema_retry", attempt=attempt, error=str(exc))
    raise glm_client.GlmError(f"schema validation failed after retries: {last_error}")
