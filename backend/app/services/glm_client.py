"""智谱 GLM 客户端（httpx 直连 OpenAI 兼容端点）。

- 模型名仅来自配置（GLM_VISION_MODEL / GLM_TEXT_MODEL），禁止硬编码
- 网络错误/超时/非 200 -> GlmError；API key 为空 -> GlmUnavailable
- 测试一律用 httpx.MockTransport 拦截，永不触网
"""
from __future__ import annotations

import base64

import httpx
import structlog

from app.core.config import get_settings

logger = structlog.get_logger()

CHAT_COMPLETIONS_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
TIMEOUT_SECONDS = 60.0


class GlmError(Exception):
    """GLM 调用失败（网络/超时/非 200/响应缺字段）。"""


class GlmUnavailable(Exception):
    """未配置 ZHIPU_API_KEY，AI 判分整体不可用（走 §8.6 降级）。"""


def _headers() -> dict:
    settings = get_settings()
    if not settings.zhipu_api_key:
        raise GlmUnavailable("ZHIPU_API_KEY not configured")
    return {
        "Authorization": f"Bearer {settings.zhipu_api_key}",
        "Content-Type": "application/json",
    }


def build_text_payload(prompt: str) -> dict:
    """纯文本判分 payload。"""
    return {
        "model": get_settings().glm_text_model,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.1,
    }


def build_vision_payload(prompt: str, image_bytes: bytes, mime: str = "image/jpeg") -> dict:
    """含图片输入的判分 payload（base64 data URL）。"""
    b64 = base64.b64encode(image_bytes).decode()
    return {
        "model": get_settings().glm_vision_model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{b64}"}},
                    {"type": "text", "text": prompt},
                ],
            }
        ],
        "temperature": 0.1,
    }


def chat(payload: dict, client: httpx.Client | None = None) -> str:
    """同步调用 chat/completions，返回首个 choice 的 message.content。

    生产环境由 worker 线程调用（B1：同步 IO 不进事件循环）；
    测试传入 client=httpx.MockTransport(...) 实例。
    """
    headers = _headers()
    try:
        if client is not None:
            resp = client.post(CHAT_COMPLETIONS_URL, json=payload, headers=headers)
        else:
            with httpx.Client(timeout=TIMEOUT_SECONDS) as own:
                resp = own.post(CHAT_COMPLETIONS_URL, json=payload, headers=headers)
    except httpx.HTTPError as exc:
        logger.error("glm_request_failed", error=str(exc))
        raise GlmError(f"GLM request failed: {exc}") from exc

    if resp.status_code != 200:
        logger.error("glm_non_200", status=resp.status_code, body=resp.text[:200])
        raise GlmError(f"GLM returned {resp.status_code}")

    try:
        content = resp.json()["choices"][0]["message"]["content"]
    except (KeyError, IndexError, ValueError) as exc:
        logger.error("glm_bad_response_shape")
        raise GlmError(f"GLM bad response shape: {exc}") from exc

    if not isinstance(content, str):
        raise GlmError("GLM content is not a string")
    return content
