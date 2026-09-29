# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-29
**Commit:** b62f5d0
**Branch:** main

## OVERVIEW

ToneUp 是考研刷题系统，三端 monorepo（无统一 workspace 管理）：PC 前端（Vue 3 + Vite + TS）、backend 后端（FastAPI + Python 3.10 + uv）、Android 客户端（Kotlin 2.0 + Jetpack Compose + Hilt）。线上域名 `https://tu.lztfirefly.top`（Cloudflare Tunnel）。

## STRUCTURE

```
E:\project\ToneUp\
├── PC\                # Vue 3.5 + Vite 6 + Pinia + Naive UI + PWA
├── backend\           # FastAPI + Pydantic v2 + structlog + SQLite
├── Android\           # Kotlin 2.0 + Compose M3 + Hilt + Retrofit
├── examcrafts_study\  # ⚠ 第三方应用逆向参考材料（gitignore，非项目代码）
├── scripts\           # 空目录
├── skills\            # 空目录
├── 开发需求文档.md      # 全局需求文档（中文命名）
└── 数据库结构文档.md    # 全局数据库文档
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| 全局需求/数据库设计 | `开发需求文档.md`, `数据库结构文档.md` | 中文命名，权威 spec |
| 各端需求 spec | `PC端目标需求文档.md`, `后端目标需求文档.md`, `安卓端目标需求文档.md` | 每端一份 |
| 后端部署/恢复 | `backend/docs/deploy-checklist.md`, `backend/docs/restore-drill.md` | systemd + Nginx 裸机方案 |
| 前端题型系统 | `PC/docs/question-types.md` | 题型渲染注册表 |
| 前端 token 审计 | `PC/docs/token-audit.md` | 设计 token 不得重复定义 |
| Android 完整文档 | `Android/README.md` | 极详尽，含验收清单 |
| 后端运维脚本 | `backend/scripts/` | 迁移/备份/初始化/压测 |

## CODE MAP

### 后端入口链
```
uvicorn app.main:app → create_app()
├─ lifespan: validate_startup → bank_registry.load(data_root) → grading_worker.start_worker
├─ 中间件（顺序敏感）: RateLimit → DraftBodyLimit → request_id → CORS(最外层)
└─ include_router × 17 → app/api/{auth,catalog,question_banks,images,attempts,notes,favorites,reviews,stats,ai_feedback,admin,wrong_questions,practice_sessions,backgrounds,sections,feedback}
```

### 前端入口链
```
index.html → src/main.ts
├─ validateRegistry() → Pinia → 401 拦截接线 → router → mount
└─ router/index.ts: 11 条懒加载路由 + auth guard + chunk-error 自愈
```

### Android 入口链
```
ToneUpApp.kt (@HiltAndroidApp) → WebView 池预热 + RendererRegistry fail-fast 校验
MainActivity.kt (单 Activity) → Compose Navigation → ui/feature/*
```

## CONVENTIONS

- **无 lint 工具链**：无 ESLint/Prettier/Ruff/EditorConfig。质量靠 vue-tsc 严格编译 + Kotlin official code style
- **M-xxx / H-xxx / EC-xx 编号注释体系**：所有修复/决策带编号引用需求文档，新改动应沿用
- **三端字段名逐字对齐**：不得另造字段名
- **配置文件自带"为什么"注释**：维护时不得删除
- **密钥零入库**：.env*、*.pem/*.key/*.jks 全部 gitignore
- **数据不入库**：backend/data/*.db、backups/、uploads/ 不提交

## ANTI-PATTERNS (THIS PROJECT)

- 禁止明文密码与自定义哈希（Argon2id 强制）
- 禁止客户端拼接/提交服务器文件路径
- 禁止 AI/模型返回内容进入 SQL 或 HTML 渲染
- 禁止硬编码密钥/模型名/题型映射
- 禁止在 Composable 内直接持有仓库引用
- 禁止预加载超出练习范围的批量资源
- 禁止一次性 SELECT 整表载入
- 禁止嵌套三元（审查规则）
- 禁止 root 直连服务器、禁止 CORS `*`

## UNIQUE STYLES

- 中文命名文档体系（需求/数据库/目标需求文档）
- 测试文件顶部用中文 docstring 引用需求条款与缺陷追踪 ID
- 契约测试优先：后端有端点全集断言，Android 有 *ContractTest
- PC dev 代理默认指向线上后端（非本地）— 本地调试需手动改回

## COMMANDS

```bash
# PC 前端（PC/ 目录）
npm run dev          # 开发 :5173
npm run build        # vue-tsc + vite build
npm run typecheck    # 仅类型检查

# 后端（backend/ 目录）
uv sync              # 安装依赖
uv run uvicorn app.main:app --reload --port 8000
uv run pytest        # 测试

# Android（Android/ 目录）
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## NOTES

- `backend/app/_/` 等 7 处 `_` 目录是误创建的空壳（内含空 `_init__.py`），可安全删除
- `examcrafts_study/` 是第三方应用构建产物 + 逆向报告，非项目代码
- `.omo/`、`.codegraph/`、`.sisyphus/`、`.claude/` 是 AI 工具目录，非应用代码
- 无 CI/CD、无 Docker — 部署为 systemd + Nginx 裸机方案
- `backend/data/` 含运行时 SQLite（题库 + 用户库），勿提交/误删
