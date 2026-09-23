# ExamCrafts 借鉴需求文档

> 版本：V1.0（2026-09-22）
> 来源：对 examcrafts.com（Vue 3 SPA + Django REST 后端）前端构建产物的逆向研究。研究材料（结构还原报告、55 条路由表、46 个 API 端点清单、美化后源码）见仓库 `examcrafts_study/` 目录，其中 `REPORT.md` 为总览。
> 性质：产品机制与架构借鉴需求，非接口契约。所有新增接口命名以《开发需求文档.md》§9.1（D-05 命名权威）约束为准，本文档端点均为草案，落地前须回写 `backend/后端目标需求文档.md`。
> 编号规则：`EC-xx`，与安卓专项 `F1~F11`（§10）、PC 端 `FR-XXX`、决策编号 `D-xx` 互不冲突。

---

## 0. 总原则

1. **借鉴机制，不照抄实现**：ExamCrafts 为 Django 体系且视图层按学科复制，只提炼协议与产品形态。
2. **反面教材红线**：ExamCrafts 存在 `CETExamView` / `TEMExamView` 等十几对按学科复制的平行组件。ToneUp 任何新功能不得按学科（数学一/二、英语一/二）复制视图或组件，必须复用统一 DTO（§9.3）与题型 renderer 注册表。
3. **不引入其技术栈**：不迁移 Django / DaisyUI / EdgeOne，仅借鉴已在 ToneUp 技术选型内的能力。

---

## 1. P1 需求（对应既有待办，下一轮直接排期）

### EC-01 专题练习 Session 生命周期（消解 F2"选题规则未定"）

**背景与来源**：ExamCrafts 专题模式提供完整 session 协议（证据：`examcrafts_study/pretty/cetApi-jjMa8qCT.js`、`REPORT.md` §三）。ToneUp 的 F2 仅剩"自选范围/数量"规则未定，本需求把选题从"前端一次性拉题"升级为"服务端 session"，同时解决安卓端跨设备断点续做。

**需求描述**：

1. 新增练习会话资源（草案命名 `practice-sessions`，kebab-case 复数对齐 `/api/question-banks/` 风格）：

| 端点（草案） | 方法 | 说明 |
| :--- | :--- | :--- |
| `/api/practice-sessions` | POST | 创建会话：`bank_id` + 选题条件（collection/年份/题型 `type_code` 列表）+ 数量上限 |
| `/api/practice-sessions/{sid}` | GET | 恢复会话：返回题目顺序、已答进度、剩余题目 |
| `/api/practice-sessions/{sid}/draft` | PUT | 服务端草稿：当前题号、未提交作答、累计用时 |
| `/api/practice-sessions/{sid}/submit` | POST | 交卷，返回本轮判分小结 |
| `/api/practice-sessions/{sid}/result` | GET | 本轮结果详情（对错、正确率、耗时） |
| `/api/practice-sessions` | GET | 会话历史列表（分页，`data.items` / `total` / `has_more`） |
| `/api/practice-sessions/{sid}` | DELETE | 删除会话 |

2. 选题规则首版拍板建议：按 `collection_id`（专题/章节）+ `type_code`（题型）筛选，数量上限默认 20、最大 50；年份与难度筛选留扩展字段不做 UI。
3. **幂等与一致性**：`submit` 携带 `client_request_id`，服务端按 §9.4 幂等；草稿写入节流（PC 10s / 安卓切题时）。
4. **草稿优先级**：恢复时以服务端草稿为准；断网回退本地 DataStore/localStorage 队列（§9.4，不改变现有离线行为）。
5. session 内题目复用统一题目 DTO 与既有判分链路，**不新增判分逻辑**；作文/AI 题不进入本期 session 范围。

**改动端**：后端（新增表 `practice_session` + `practice_session_item`，存储题目 ID 序列与草稿 JSON）、Android（F2 选题入口 + 继续）、PC（可选入口，后置）。

**验收标准**：

- [ ] 创建→作答中断→换端恢复→交卷→历史查看全链路可用；
- [ ] 重复 submit 幂等，不重复计分；
- [ ] 会话内不产生错题本/掌握度以外的副作用；
- [ ] OpenAPI 契约生成后回写 `backend/后端目标需求文档.md` 并更新 §12 台账 F2 状态。

**关联**：F2、§9.4、§10.5。

---

### EC-02 背题模式（消解 F7"答案显示开关"）

**背景与来源**：ExamCrafts 将"答题页 / 答案页 / 解析页"拆为独立路由视图（`exam/:id`、`/answer`、`/analysis`），背题场景本质是"只读视图复用作答组件"。ToneUp 不拆路由（维持 §2.3 单页切 tab），改造状态机。

**需求描述**：

1. 安卓端 `PracticeStateMachine` 增加**背题模式（recite）**：会话级开关，从专题列表入口进入（对应 F7 的"答案"开关）。
2. 背题模式下：进入即展示正确答案 + 官方解析；renderer 复用现有 9 个组件，作答交互禁用；**不调用 `POST /api/attempts`**，不产生错题本、掌握度、复习排期副作用。
3. 计时器隐藏；可跳题（答题卡复用）。
4. PC 端同步提供该模式（practice store 增加 `mode: 'practice' | 'recite'`），入口与交互两端一致。
5. 模式标识写入会话上下文（仅本地），退出即失效，不持久化为服务端状态。

**改动端**：Android（F7 收尾）、PC（practice store + renderer 只读态）。

**验收标准**：

- [ ] 背题模式下零 `/api/attempts` 请求、零错题本/掌握度变更；
- [ ] 10 种 `type_code` 在只读态渲染无交互残留；
- [ ] 普通模式行为完全不变（回归 §10.4 既有验收项）。

**关联**：F7、§10.3 #18。

---

### EC-03 统计图表组件化（支撑 §11.1 与安卓产品首页）

**背景与来源**：ExamCrafts 的 `ScoreHistoryChart` / `TopicScoreHistoryChart` 是纯展示组件，仅接收分数历史数组，与业务零耦合（证据：`examcrafts_study/pretty/ScoreHistoryChart-BpME2Vpz.js`）。§11.1 已认定"图表最容易失控、单独一轮做"。

**需求描述**：

1. 定义图表数据契约（纯数组输入，不发起请求）：`DailyTrendSeries`（日期、作答量、正确率）、`TopicProgressItem`（专题名、已做/总数）、`ScoreHistoryPoint`（会话序号、得分率）。
2. PC 端：图表组件迁入 `PC/src/components/charts/`，props 仅数据 + 尺寸，业务数据由 stats store 组装后传入。
3. Android 端：首页图表落地（§11.1 遗留），用 Compose Canvas 自绘或 Vico，同样只吃数据契约；首版三张卡：本周作答量趋势、正确率趋势、专题进度条列表。
4. 图表失败态：数据为空显示占位文案，不崩溃、不无限加载。

**改动端**：PC（组件整理）、Android（首页图表）、后端（无新增，复用 `/api/stats/*`）。

**验收标准**：

- [ ] 图表组件无 store 依赖、无网络请求，可用纯 mock 数据驱动；
- [ ] 安卓首页三卡在 2 周数据 / 0 数据两种情况下渲染正确。

**关联**：§11.1、待办台账 #6。

---

## 2. P2 需求（低成本，随版本顺带）

### EC-04 题型 renderer 懒加载

- ExamCrafts 题型组件独立 chunk 按需加载；ToneUp 的 `question-renderers/registry.ts` 目前静态 import 全量打包。
- 改造：registry 内 10 个 renderer 改 `defineAsyncComponent`；KaTeX 相关依赖仅随 `SOLUTION` / `CLOZE` / `ESSAY` 渲染路径加载。
- 验收：`npm run build` 后 KaTeX 不在首屏主 chunk 中；registry 启动校验（`validateRegistry`）行为不变。

### EC-05 catalog 持久化缓存

- ExamCrafts 有独立 `examListCache` 模块（localStorage 级）；ToneUp catalog store 为会话级内存缓存。
- 改造：`GET /api/catalog` 结果按 `bank_id` + 版本号持久化到 localStorage（PC）/ DataStore（Android），启动先渲染缓存再后台刷新。
- 验收：弱网/断网下进入选题页可用缓存数据；刷新后题目数据仍以服务端为准。

### EC-06 PC 端 PWA

- 引入 `vite-plugin-pwa`：manifest + Service Worker 预缓存静态资源，实现"可安装 + 静态壳离线"。
- 明确边界：业务数据不做离线（与 §9.4 一致）；SW 仅缓存 `assets/` 静态资源。
- 验收：Lighthouse PWA 项通过；发版后 SW 更新生效（旧缓存自动清理）。

### EC-07 Nginx 静态缓存与 HSTS 对齐

- 参照 ExamCrafts 生产配置：`assets/` 长缓存（immutable + 内容 hash 命名）、`index.html` 不缓存、开启 HSTS 与 HTTP/2。
- 落点：服务器 8.130.23.56 的 nginx 配置；属运维项，不改代码。

### EC-08 学习氛围数据（可选，暂缓）

- ExamCrafts 有 `common/heartbeat` + `common/online` 在线统计。ToneUp 单用户为主，本期不实施；若未来多用户开放，再评估"今日在线/打卡"小端点。

---

## 3. 技术储备（本期不实施）

### EC-09 题库加密下发（信封方案）

- ExamCrafts `decryptionV2` 采用 AES-GCM 信封 `{payload, iv, key_id}`，密钥按 `key_id` 动态获取，另有字段噪声混淆（`_meta/_data_/_noise_` 前缀 + 语义重映射表）（证据：`examcrafts_study/pretty/decryptionV2-DSZfZEyZ.js`、`fieldMapping-BIM-p-AE.js`）。
- 与 ToneUp Android 现有 `AnswerCodec` / `EnvelopeUnwrapper`（答题上报方向）互为镜像。
- 定位：**仅当题库对外开放给第三方用户时**再立项，用于提高爬取成本；明确局限——密钥仍需下发至客户端，只能防低成本爬虫（本研究本身即证明可被还原）。
- 若立项：后端新增 `/api/question-banks/{bank_id}/envelope-key/{key_id}` 风格端点 + WebCrypto/Android Keystore 解密链路；届时单独出专项文档。

---

## 4. 明确不做（负面清单）

| ExamCrafts 功能 | 不做理由 |
| :--- | :--- |
| 留言墙 / 弹幕 / 排行榜 / 公众号打赏 | 社区包袱；ToneUp 笔记共享（§10 F 系列）已覆盖协作需求，举报审核按既有 P2 节奏走 |
| DaisyUI / cupcake 主题一把梭 | ToneUp `tokens.css` 多主题体系（6 套 + 自定义背景）已更细，不回退 |
| 听力 / 字幕 / 音频播放器全套 | 考研初试无听力，无业务场景 |
| 按学科复制视图（其 CET/TEM 双份组件） | 红线（见 §0.2），以统一 DTO + bank_registry 零代码扩容为准 |
| Django 式裸响应（无统一信封） | ToneUp 信封契约 `{success, data, message, request_id}` 已优于对方，不改动 |

---

## 5. 来源映射表（研究材料 ↔ 需求）

| EC 需求 | ExamCrafts 机制 | 研究材料位置 |
| :--- | :--- | :--- |
| EC-01 | 专题 session：content/draft/continue/submit/check_scoring_status/result/history/delete | `examcrafts_study/pretty/cetApi-jjMa8qCT.js`；`REPORT.md` §三、§五 |
| EC-02 | 答题页/答案页/解析页路由分离 | `examcrafts_study/REPORT.md` §一（路由表 exam/:id 相关条目） |
| EC-03 | ScoreHistoryChart / TopicScoreHistoryChart 纯展示组件 | `examcrafts_study/pretty/ScoreHistoryChart-BpME2Vpz.js` |
| EC-04 | 题型组件独立 chunk 懒加载 | `examcrafts_study/REPORT.md` §二 |
| EC-05 | examListCache 列表缓存模块 | `examcrafts_study/pretty/examListCache-BTs7jylU.js` |
| EC-06 | manifest.json + PWA 可安装 | `examcrafts_study/index.html` |
| EC-07 | EdgeOne + nginx 强缓存 / HSTS / HTTP2 | `REPORT.md` §部署 |
| EC-08 | common/heartbeat + common/online | `examcrafts_study/REPORT.md` §三（common 模块） |
| EC-09 | decryptionV2 + fieldMapping 信封加密 | `examcrafts_study/pretty/decryptionV2-DSZfZEyZ.js`、`fieldMapping-BIM-p-AE.js` |

---

## 6. 排期建议与台账登记

1. **下一轮（安卓向）**：EC-02（F7 收尾）→ EC-01（F2 选题 + session，先出后端契约）→ EC-03（安卓首页图表）。
2. **随版本顺带**：EC-04 ~ EC-07（各项均 ≤ 半天级改动，可夹在任意轮次）。
3. 本批次需求建议在《开发需求文档.md》§12 待办台账追加一行："ExamCrafts 借鉴批次（EC-01~EC-09），见《ExamCrafts借鉴需求文档.md》"，并以本文档为该批次唯一需求基线。
4. 开放问题（沿用 §10.6 风格，需拍板）：
   - EC-01 选题 UI 是否需要"数量上限"让用户可调（建议首版固定 20，不做调节）；
   - EC-01 session 是否允许跨 bank 混选（建议首版不允许，单 `bank_id`）；
   - EC-02 背题模式入口层级（专题列表页顶部开关 vs 答题页设置面板）。
