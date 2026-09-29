# 后端知识库

## OVERVIEW

FastAPI + Pydantic v2 + structlog + SQLite 后端，Python 3.10（uv 管理依赖），端口 8000。

## STRUCTURE

```
backend/app\
├── api\           # 17 个路由模块 + deps.py（DI）
├── core\          # config/security/ratelimit/bank_registry/errors/logging/request_context
├── repositories\  # 6 个仓储层（SQLite 直连）
├── schemas\       # Pydantic v2 schema
├── services\      # glm_client/ai_grading/grader/grading_worker/image_refs/cleaner
└── main.py        # FastAPI 应用工厂 create_app() + 模块级 app
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| 启动逻辑 | `app/main.py` | lifespan: validate_startup → bank_registry.load → grading_worker.start |
| 中间件链 | `app/main.py` | 顺序敏感: RateLimit → DraftBodyLimit → request_id → CORS |
| 配置 | `app/core/config.py` | pydantic-settings，JWT_SECRET >= 32 字符 fail-fast |
| 题库注册 | `app/core/bank_registry.py` | 路径不得逃逸 data_root |
| AI 判分 | `app/services/glm_client.py` → `ai_grading.py` → `grader.py` → `grading_worker.py` | 模型名仅来自配置 |
| 运维脚本 | `scripts/` | init_user_db/backup/validate_banks/migrate_*/load_test_rss |
| 部署 | `docs/deploy-checklist.md` | systemd + Nginx 裸机方案 |

## CONVENTIONS

- **分层约束**：api 层不直接触碰 sqlite3；repositories 层不做业务判断；services 层可组合仓储
- **无 lint/format 配置**：pyproject.toml 仅含 pytest 配置
- **pytest**：testpaths=["tests"]，pythonpath=["."]
- **conftest.py 冻结文件**：3 个 fixture（client / data_root_with_banks / user_db），只读
- **测试命名**：`test_<模块>_<方面>.py`，API 层 `_api` 后缀，仓库层 `_repo` 后缀
- **手工签 JWT**：测试用 `create_access_token` 而非 mock auth
- **get_settings.cache_clear()**：每次测试 setup/teardown 必须调用防缓存污染

## ANTI-PATTERNS

- 禁止 api 层直接 import sqlite3
- 禁止硬编码模型名/密钥/并发配置
- 禁止 AI 返回内容进入 SQL 或模板渲染
- 禁止记录密码/token/图片内容到日志
- 禁止一次性 SELECT 整表载入
- 禁止路径逃逸 data_root
- 禁止解析失败静默放水
