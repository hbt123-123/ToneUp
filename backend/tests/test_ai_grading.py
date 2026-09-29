"""Tests for backend/app/services/ai_grading.py 的 JSON 转义修复（M-540）。

背景（缺陷追踪：线上 ai_feedback 记录 5a85ef02acb34d38bcf8ef4de51a5df8 failed，
error = "grading failed after retries: Invalid \\escape"）：GLM 在 JSON 字符串里
原样输出 LaTeX——\\s/\\d 等非法转义令 raw_decode 直接失败；\\frac/\\times/\\beta
是"合法"转义但解析成控制符静默损坏。本文件覆盖 repair_json_escapes 状态机
各分支与 validate_model_output 的修复重试路径（M-540），引用需求 §8 AI 判分。
"""

import pytest

from app.services.ai_grading import repair_json_escapes, validate_model_output


# ── repair_json_escapes：非法转义字面化 ──────────────────────────────────

def test_repair_literalizes_illegal_escapes():
    """\\s、\\d 等非法转义把反斜杠字面化（线上 Invalid \\escape 报错的直接根因）。"""
    assert repair_json_escapes(r'"\sqrt{2}"') == r'"\\sqrt{2}"'
    assert repair_json_escapes(r'"\d{x}"') == r'"\\d{x}"'


def test_repair_literalizes_latex_commands_after_bft():
    """\\frac/\\times/\\beta：合法转义但语义错误，紧跟 ASCII 字母时字面化保住命令。"""
    assert repair_json_escapes(r'"\frac{1}{2}"') == r'"\\frac{1}{2}"'
    assert repair_json_escapes(r'"3\times4"') == r'"3\\times4"'
    assert repair_json_escapes(r'"\beta"') == r'"\\beta"'


def test_repair_literalizes_bare_u_without_hex():
    """\\u 后无 4 位 hex（如 LaTeX \\unit）按非法转义字面化。"""
    assert repair_json_escapes(r'"\unit"') == r'"\\unit"'


# ── repair_json_escapes：合法转义保留 ────────────────────────────────────

def test_repair_keeps_valid_escapes():
    """引号/反斜杠/斜杠/换行/回车等合法转义原样保留。"""
    raw = r'"a\"b\\c\/d\ne\rf"'
    assert repair_json_escapes(raw) == raw


def test_repair_keeps_unicode_escape():
    """\\u + 4 位 hex 原样保留。"""
    raw = r'"\u00d7\u4e2d"'
    assert repair_json_escapes(raw) == raw


def test_repair_keeps_bft_without_ascii_letter():
    """\\b/\\f/\\t 后跟非 ASCII 字母（数字/中文/引号）按真实控制符保留。"""
    assert repair_json_escapes(r'"a\t1"') == r'"a\t1"'
    assert repair_json_escapes(r'"中\t文"') == r'"中\t文"'
    assert repair_json_escapes(r'"end\t"') == r'"end\t"'


def test_repair_keeps_newline_before_letter():
    """\\n/\\r 不参与字面化（M-540 决策）：合法换行远比 \\neq 类命令常见。"""
    raw = r'"第一行\nreturn 值"'
    assert repair_json_escapes(raw) == raw


def test_repair_tracks_string_boundaries():
    """字符串边界正确跟踪：结构区不改写；转义引号不误判字符串结束。"""
    assert repair_json_escapes('{"a": "x", "b": 1}') == '{"a": "x", "b": 1}'
    raw = r'{"k": "say \"hi\" \\ done", "n": 2}'
    assert repair_json_escapes(raw) == raw


# ── validate_model_output：修复重试路径 ──────────────────────────────────

def test_validate_repairs_latex_output():
    """端到端复现线上故障：pretty-printed JSON 含裸 \\sqrt/\\frac/\\times 应解析成功。"""
    raw = (
        '{\n'
        '  "is_correct": false,\n'
        '  "score": 0,\n'
        '  "error_reason": "正确答案 \\sqrt{2}，过程 \\frac{1}{2} \\times 3 计算有误",\n'
        '  "tag_ids": [3]\n'
        '}'
    )
    result = validate_model_output(raw)
    assert result["is_correct"] is False
    assert result["score"] == 0
    assert result["tag_ids"] == [3]
    # 修复后 LaTeX 以原文本形式存活（单反斜杠）
    assert result["error_reason"] == "正确答案 \\sqrt{2}，过程 \\frac{1}{2} \\times 3 计算有误"


def test_validate_clean_output_first_try():
    """无转义问题的常规输出走首次解析路径，行为不变。"""
    raw = '{"is_correct": true, "score": 5, "error_reason": "回答正确，步骤完整", "tag_ids": [1, 2]}'
    result = validate_model_output(raw)
    assert result["is_correct"] is True
    assert result["tag_ids"] == [1, 2]


def test_validate_double_backslash_passthrough():
    """GLM 已正确转义（\\\\sqrt）时首次解析即成功，内容为字面 \\sqrt，不被二次改写。"""
    raw = r'{"is_correct": true, "error_reason": "答案 \\sqrt{2} 正确"}'
    result = validate_model_output(raw)
    assert result["error_reason"] == "答案 \\sqrt{2} 正确"


def test_validate_repair_does_not_bypass_schema():
    """修复路径成功解析后仍执行 Schema 校验：is_correct 非 bool 拒绝。"""
    raw = r'{"is_correct": "yes", "error_reason": "\sqrt"}'
    with pytest.raises(ValueError):
        validate_model_output(raw)


def test_validate_repair_does_not_bypass_tag_ids_check():
    """修复路径后 tag_ids 元素校验仍生效（bool 混入拒绝，M-322 口径）。"""
    raw = r'{"is_correct": true, "error_reason": "\d", "tag_ids": [true]}'
    with pytest.raises(ValueError):
        validate_model_output(raw)


def test_validate_unrepairable_raises():
    """不可修复的损坏（未闭合字符串）仍抛 ValueError；无对象体抛 ValueError。"""
    with pytest.raises(ValueError):
        validate_model_output('{"is_correct": true, "error_reason": "未闭合')
    with pytest.raises(ValueError):
        validate_model_output("no braces here")
