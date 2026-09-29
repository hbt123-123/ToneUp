# PC 前端知识库

## OVERVIEW

Vue 3.5 + Vite 6 + TypeScript 5.7 + Pinia + Naive UI + PWA 前端，Node >= 20，纯 ESM。

## STRUCTURE

```
PC/src\
├── api\          # endpoints.ts, http.ts, token.ts, generated\schema.ts(生成代码)
├── components\   # question-renderers(13) / layout(6) / common(4) / charts(3)
├── views\        # 11 个页面（Login/Home/Catalog/Practice/ReviewToday/WrongBook/Notes/Stats/Admin/AiFeedback/NotFound）
├── stores\       # Pinia: auth/catalog/practice/review/stats/ui/wrongbook
├── composables\  # 3 个组合式函数
├── router\       # index.ts: 11 条懒加载路由 + auth guard
├── utils\        # 8 个工具模块
├── styles\       # 5 套主题 CSS
└── constants\    # questionTypes.ts（题型定义唯一来源）
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| 题型渲染注册 | `components/question-renderers/registry.ts` | 启动时 validateRegistry() 与后端契约对齐 |
| API 客户端 | `api/endpoints.ts`, `api/http.ts` | 401 拦截 → logout → /login?redirect= |
| 路由守卫 | `router/index.ts` | 会话恢复 + chunk 404 整页刷新自愈 |
| 主题系统 | `styles/*.css` + `public/background/` | 6 套主题（sky/xilian/Firefly/starrypurple/mintfresh/warmbeige） |
| 生成代码 | `api/generated/schema.ts` | OpenAPI 类型，勿手改 |

## CONVENTIONS

- **无 lint 工具链**：质量门 = `npm run build`（vue-tsc --noEmit 强制）
- **tsconfig 超严格**：strict + noUnusedLocals + noUncheckedIndexedAccess + verbatimModuleSyntax（import 必须写 `import type`）
- **路径别名**：`@/*` → `src/*`
- **dev 代理陷阱**：默认代理到线上后端 `https://tu.lztfirefly.top`，本地调试需改回 `http://127.0.0.1:8000`
- **PWA**：generateSW 零手写 SW，仅预缓存静态外壳，业务数据不离线
- **生产关闭 sourcemap**，manualChunks 分包 richtext-vendor / ui-vendor

## ANTI-PATTERNS

- 禁止在 `questionTypes.ts` 以外硬编码题型定义
- 禁止嵌套三元（审查规则，用查找表取代）
- 禁止重复定义设计 token
- 禁止水合时置 `loaded=true`（只提供首屏占位）
- 禁止恢复导航跳过 await
