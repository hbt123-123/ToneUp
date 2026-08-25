"""Structured logging setup with structlog — JSON output + request_id injection.

- setup_logging() 配置 structlog，输出 JSON 到 stdout
- 包含 TimeStamper(fmt="iso") 与 add_log_level processor
- inject_request_id 从 contextvar 读取 request_id 并注入事件字典
- 模块导入即调用 setup_logging()
"""

import sys

import structlog

from app.core.request_context import request_id_var


def setup_logging() -> None:
    """Configure structlog for JSON output with request_id injection.

    - Processor chain: TimeStamper (ISO format) → add_log_level → JSONRenderer
    - inject_request_id 从 request_id contextvar 读取值并写入事件
    - 最终输出格式由 JSONRenderer 控制
    """
    structlog.configure(
        processors=[
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.add_log_level,
            inject_request_id,
            structlog.processors.JSONRenderer(),
        ],
        logger_factory=structlog.stdlib.LoggerFactory(),
        wrapper_class=structlog.stdlib.BoundLogger,
    )


def inject_request_id(
    logger, method_name, event_dict: dict
) -> dict:
    """将当前 request_id 从 contextvar 注入事件字典。

    如果 contextvar 中有值则写入 event_dict["request_id"]，
    否则写入 "request_id": None，确保日志始终有该键。
    """
    rid = request_id_var.get()
    event_dict["request_id"] = rid
    return event_dict


# 模块导入即调用 setup_logging()
setup_logging()

# 模块级 logger（供 main.py 等直接 from app.core.logging import logger 使用）
logger = structlog.get_logger()