# packages-by-audience-and-layer

Status: accepted (2026-10-24)

根包 `app` 曾平铺 32 个类：公开订阅源接口、后台 `/index` 的各个页面、阅读页 `/reader`、radar 页面、抓取流水线、凭据配置和 Spring 装配混在一起。找一个页面的入口要在 30 多个文件里按名字猜；`CredentialConfigStore` 放在根包，导致 16 个路由类反向依赖装配层；`SubscriptionResource` 在根包，它的 `SubscriptionService` 却在 `app.subscriptions`。

我们决定按两条规则分包：**HTTP 入口按受众分**，`/index` 下的一切进 `app.admin`，`/reader` 下的一切进 `app.reader`（与已有的阅读卡片视图模型同包）；**领域逻辑按功能分**，留在 `app.ai`、`app.radar`、`app.feishu`、`app.subscriptions` 等功能包。抓取流水线（`FeedFetcher`、`FetchScheduler`）独立为 `app.fetch`，凭据配置下沉到 `app.core`。根包只保留装配：`AppRuntime`、`AppConfig`、路由注册表、公开订阅源接口和静态资源。

`app.auth` 是唯一既含领域逻辑又含页面的包，保持原样：登录、令牌和账号页面是一个自洽的整体，拆开反而要跨包追踪。

## Considered Options

维持平铺根包：
  优点: 零改动；包级可见性随手可用
  缺点: 入口难找，依赖方向不可见，路由类依赖装配层

纯按技术层分（`web`/`service`/`repository`）：
  优点: 常见、可预期
  缺点: 一个功能散落到三个包；本项目的领域包（ai、radar、feishu）已经是功能切片，改成技术层要重排全部代码

按受众分 Web 层、按功能分领域（本决策选择）：
  优点: 从 URL 前缀就能找到入口包；领域包不知道页面存在；改动只涉及根包和测试
  缺点: radar 的代码分布在 `app.radar`（流水线）、`app.admin`（设置/任务页）和 `app.reader`（报告页）三处；被跨包使用的成员要改为 public（如 `FetchScheduler.fetchNow`、`TestPages`）

## Consequences

- `PackageLayoutTest` 守住方向：领域包（core、routes、store、support、ai、radar、feishu）不得 import 根包、`app.fetch`、`app.admin` 或 `app.reader`。需要共享的类型下沉到 `app.core` 或领域包。
- 新增 HTTP 接口时按 URL 前缀选包；AGENTS.md 的 Package map 与 Style 规则同步记录这一约定。
- 测试随被测类迁移到对应包；`app.TestPages` 改为 public 供各包测试复用。
- `app.core` 与 `app.support` 的双向依赖已消除（2026-10-27）：唯一的反向引用 `XmlEscaper` 移入 `app.core`，`PackageLayoutTest` 禁止 core 引用 support。
