# ToneUp 一潼上岸 · 安卓原生客户端

依据《Android/安卓端目标需求文档.md》V1.0 开发的考研刷题系统安卓端，覆盖 A0–A3 全部批次的 V1 功能面。

## 技术栈

| 层级 | 实现 |
| :-- | :-- |
| 语言/UI | Kotlin 2.0 + Jetpack Compose Material3（Material You 动态取色，低版本回退品牌色 #2B3A67 / #7C3AED） |
| DI | Hilt（NetworkModule / DataStoreModule / CoroutineModule + Repository 注入） |
| 网络 | Retrofit + OkHttp + kotlinx-serialization；统一外层 `{success,data,message,request_id}` 在仓库层解包 |
| 存储 | Preferences DataStore（偏好）+ 按用户命名空间的 DataStore 会话文件（草稿/未同步队列/最近练习/标记）+ Keystore AES-GCM 加密令牌 |
| 导航 | Navigation Compose 单 Activity：login/register/main(四 Tab)/practice/analysis/wrongbook/noteEditor/aiPhoto |
| 公式 | WebView 对象池（3 实例预热）+ KaTeX 0.16.11 本地离线渲染（assets/katex，约 1.5MB） |
| 相机 | CameraX ImageCapture（AI 拍照纠错专用） |
| 图片 | Coil 懒加载 + 占位图 + 200MB LRU 磁盘缓存，鉴权头经共享拦截器注入 |

minSdk 26 / targetSdk 35 / compileSdk 35。

## 构建

```bash
# Android Studio 直接打开本目录；或命令行：
./gradlew :app:assembleDebug        # 调试 APK
./gradlew :app:testDebugUnitTest    # 47 个纯逻辑单元测试
./gradlew :app:lintDebug            # Lint（当前无 error）
```

- Debug `BASE_URL=http://10.0.2.2:8000/`（模拟器访问宿主机后端），允许明文流量仅限 debug。
- Release `BASE_URL` 为占位域名，上线前在 `app/build.gradle.kts` 修改。

## 目录速览

```
app/src/main/java/com/toneup/app/
├── ToneUpApp.kt                  # Hilt 入口、WebView 池预热、Coil 配置、注册表校验日志
├── MainActivity.kt               # 单 Activity
├── di/                           # Hilt Module 与限定符（@IoDispatcher/@UploadClient 等）
├── domain/
│   ├── model/                    # QuestionType 密封类、AnswerValue 多态作答值
│   └── logic/                    # 状态机、幂等键、轮询退避、Markdown 白名单、图片引用抽取、答案编解码、防御性解析
├── data/
│   ├── remote/{api,dto,interceptor}
│   ├── local/                    # 加密令牌、偏好、会话数据（按 user_id 隔离）、连接监听
│   └── repository/               # 统一解包、分类异常、练习提交队列、结果缓存等
└── ui/
    ├── theme / navigation
    ├── components/formula        # WebView 池 + FormulaText + 三级降级链路
    ├── components/question       # RendererRegistry + QuestionContext
    └── feature/                  # auth / bank / practice(renderers×10) / analysis / review / wrongbook / stats / mine / aiphoto
```

## 关键机制对照

| 需求条款 | 实现位置 |
| :-- | :-- |
| §8.1 练习状态机六态 | `domain/logic/PracticeStateMachine.kt`（纯函数，单测覆盖转移表） |
| §8.2 client_request_id 幂等复用 | `domain/logic/IdempotencyKeyStore.kt` + PracticeRepository |
| §8.3 草稿/断网续答/自动重放 | PracticeViewModel 草稿防抖（ESSAY 另有 3s 兜底落盘）+ ConnectivityMonitor 触发 replayPendingQueue + 待同步横幅 |
| §8.5 相邻题预取 | PracticeViewModel.loadQuestion 中 N±1 ensureSlot，失败静默 |
| §6.1 注册表启动校验 | ToneUpApp.onCreate 输出 `RendererRegistry missing: XXX` ERROR 日志 |
| §6.4 未知题型降级卡 | FallbackRenderer（含原始 code、重试、跳过） |
| §7.6 公式三级降级 | PooledWebView 800ms 超时守卫 → 原文退化 → 连续 3 次失败提示反馈 |
| §9.4 触感映射 | ui/components/Haptics.kt，可在设置关闭 |
| §10 AI 拍照纠错 | aiphoto 包：权限三态引导→压缩(≤1600px/q80/≤5MB)→multipart→2s→5s 退避轮询上限 60s→诊断卡→自评兜底(mode=self_judge) |

## 公式渲染 PoC 归档（§7.5 硬性闸门）

Debug 构建入口：「我的」→「公式渲染 PoC（调试）」。内置 12 类样本（行内/独立/分式/根号/上下标/矩阵/分段/积分/求和极限/markdown 残留），实时统计成功率与平均首帧。

**门槛指标归档表**（容器内无法测量的项需真机补测后填写）：

| 指标 | 门槛 | 实测 |
| :-- | :-- | :-- |
| 渲染成功率 | ≥99% | 待真机填写 |
| 单题首帧（池化热复用） | ≤150ms | 待真机填写 |
| 连续切题 30 次掉帧率 | <5% | 待真机 Perfetto 复测 |
| WebView 池内存增量 | ≤150MB | 待真机填写 |
| 深浅色切换 | 无白底闪烁 | 待真机填写 |

> 样本扩容至 ≥200 条真实题库内容的方法：从 math1/math2 库导出含 `$...$` 的 content 列表替换 `FormulaPocScreen.POC_SAMPLES` 后重跑。

## 真机手工验收清单

- [ ] 登录 → 选题 → 作答 → 提交 → 解析全链路
- [ ] 断网作答草稿保留；恢复联网自动重放且横幅计数清零；快速连点确认不产生重复流水
- [ ] 主观题四态流转 queued→processing→succeeded/failed；failed 自评兜底闭环
- [ ] 320dp 宽 + 字体缩放 2.0 + 深色模式组合无溢出无裁切
- [ ] 错误提示晃动动画 + WARNING 触感；收藏 LIGHT_IMPACT；打卡 SUCCESS
- [ ] TalkBack：选项朗读选中态、排序可用序号下拉路径完成
- [ ] 相机权限三态（授权/拒绝置灰可再申请/永久拒绝跳设置）；未授权不影响其他功能
- [ ] 冷启动 ≤2.5s 到首页可交互（中端机）

## 与后端契约的临时约定 ⚠️

以下两个端点在后端契约中缺失，客户端按自拟临时路径实现并收敛在仓库层单点，后端定稿后仅需修改对应 API 接口：

| 功能 | 临时端点 | 代码位置 |
| :-- | :-- | :-- |
| 错题本列表（FR-WB-02） | `GET /api/wrongbook?subject_id&type_code&page&page_size` | `WrongbookApi` / `WrongbookRepository` |
| 笔记聚合列表（FR-ME-02） | `GET /api/notes?page&page_size` | `NotesApi.myNotes` / `NotesRepository` |

其余差异备忘：

- `GET /api/auth/me` 契约未含 `created_at`，DTO 以可选字段兼容，「我的」页缺省显示“未知”。
- 错题本条目点击暂以“单题练习会话”进入重做链路（契约无 attempt 引用无法直开只读解析）。
- 统计 overview 字段名（accuracy_rate/streak_days/checked_today 等）为按 DB 口径的提案，联调时以 OpenAPI 为准校正 DTO 即可。

## 版本锁定

见 `gradle/libs.versions.toml`。AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 / Hilt 2.53.1 / Retrofit 2.11.0 / CameraX 1.4.1 / Coil 2.7.0 / DataStore 1.1.1。
