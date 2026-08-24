# generated/

此目录用于存放由后端 OpenAPI 契约生成的 TypeScript 类型（`openapi-typescript` 产物），**禁止手改**。

后端（FastAPI）尚未提供 `/openapi.json` 之前，`schema.ts` 为依据《后端目标需求文档》V1.0 第 6 章
手工维护的契约快照。后端可用后执行：

```bash
npx openapi-typescript http://127.0.0.1:8000/openapi.json -o src/api/generated/schema.gen.ts
```

并以生成文件为准替换 `schema.ts` 中对应类型，业务层只做派生与收窄。
