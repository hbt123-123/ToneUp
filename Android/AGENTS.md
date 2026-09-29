# Android 客户端知识库

## OVERVIEW

Kotlin 2.0 + Jetpack Compose M3 + Hilt + Retrofit 安卓客户端，minSdk 26 / targetSdk 35 / JVM 17。**完整文档见 `README.md`**（极详尽，含验收清单），本文件仅补充差异与索引。

## STRUCTURE

```
Android/app/src/main/java/com/toneup/app\
├── di\             # Hilt 模块
├── domain\
│   ├── model\      # 领域模型
│   └── logic\      # 状态机/幂等键（纯逻辑，可单测）
├── data\
│   ├── remote\     # api(11) / dto(10) / interceptor
│   ├── local\      # DataStore(7)
│   └── repository\ # 仓储层(15)
└── ui\
    ├── theme\      # Material3 主题
    ├── navigation\ # Compose Navigation 图
    ├── components\ # formula/question/charts/common
    ├── main\       # MainScaffold
    └── feature\    # auth/bank/practice/analysis/review/wrongbook/stats/mine/aiphoto/sessionhistory/sectionlist
```

## WHERE TO LOOK

| Task | Location | Notes |
|------|----------|-------|
| 应用入口 | `ToneUpApp.kt` | @HiltAndroidApp，WebView 池预热 + RendererRegistry fail-fast |
| Activity | `MainActivity.kt` | 单 Activity，setContent { ToneUpRoot() } |
| 版本锁定 | `gradle/libs.versions.toml` | 所有依赖集中管理，禁止散落写版本号 |
| 构建配置 | `app/build.gradle.kts` | BASE_URL 注入、签名、lint 策略 |
| 仓库镜像 | `settings.gradle.kts` | 阿里云镜像 + content 过滤器 |
| 完整文档 | `README.md` | 技术栈/机制对照/验收清单 |

## CONVENTIONS

- **版本目录**：新增依赖必须先进 `gradle/libs.versions.toml`，同组依赖复用 version.ref
- **ViewModel 一律 @HiltViewModel 构造注入**
- **Contract 测试**：*ContractTest 用 MockWebServer 验证网络契约
- **纯逻辑测试**：PracticeStateMachineTest 等测 domain/logic 状态机转移表
- **无 androidTest/UI 测试**（M-1 已删死配置）
- **debug BASE_URL** = `http://10.0.2.2:8000/`（模拟器访问宿主机）
- **release BASE_URL** 经 `-Ptoneup.baseUrl=<url>` 或 `TONEUP_BASE_URL` 注入

## ANTI-PATTERNS

- 禁止在 Composable 内直接持有仓库引用
- 禁止令牌明文存储
- 禁止 Coil 一次性拉取大图集
- 禁止组件自行持久化答案（onAnswerChange 是唯一上行通道）
- 禁止截断选项长文本/固定高度截断文本
- 禁止预载超出练习范围的批量资源
- 禁止长列表一次性全量组合
- 禁止账号数据跨账号出现
