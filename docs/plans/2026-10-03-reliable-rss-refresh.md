# Reliable RSS Refresh Implementation Plan

**Goal:** 让 `/rss` 挂载支持持久化条件请求、故障退避、重启恢复及可见刷新状态，同时保留已有路由接口。

**Architecture:** 在 `FetchClient` 增加兼容的响应方法；`GenericRssRoute` 负责 RSS 条件刷新策略，所有数据库操作仍经过 `FeedStore`。`FeedFetcher` 继续统一保存条目与日志，HTTP 与后台调度共享进行中的抓取。状态展示复用管理页的健康视图。

**Tech Stack:** Java 25、Spring Boot 4、SQLite、Java HttpClient、Thymeleaf。

**Spec:** [开发路线图阶段一](../research/2026-10-03-development-roadmap.md#阶段一一个-rss-源可以长期可靠刷新)。本次用户要求“开始开发”，按已推荐顺序执行第一阶段。

## Constraints and decisions

- 保留单方法 `RouteHandler` 和现有 `FetchClient.get()`；新增响应方法使用 default 实现，已有 lambda 测试客户端不受影响。
- 仅通用 RSS 使用持久条件缓存；正文、ETag、Last-Modified 绑定完整 feedUrl，换源不会复用旧验证器；刷新状态以 (route_path, feed_url) 为复合键。每个订阅另存规范化 Feed 的 SHA-256 指纹，准确追踪禁缓存来源和共享地址别名的内容变化。
- `304` 只在有有效正文时复用；缺少正文最多补做一次无条件请求；解析失败/空 feed 不替换有效缓存。
- 不持久化要求 no-store/private、带 Vary/Set-Cookie 的 HTTP 快照；已有快照也删除。不是通用 HTTP 代理缓存。
- RSS 失败退避为 60 秒起、翻倍至最多 1 小时；有效 Retry-After（秒数或 HTTP 日期）为不可提前的下界。退避保存在 SQLite，不以线程睡眠等待。
- 同主机最多两个活动请求；不同主机独立。旧正文 API 保留最多三次临时故障重试，短暂等待并正确传播中断；429/503 交由调用策略处理。
- 首版展示最后检查、成功、内容变化、下次重试、HTTP 状态、正文 UTF-8 字节数、耗时与连续失败，不承诺流量节省比例或自适应刷新。
- 不改已有用户阅读器模板/ADR，不迁移到外部队列，不调用真实飞书/Ollama。

## Tasks

1. [x] HTTP 响应与并发：新增 `FetchResponse(statusCode, headers, body)`、`FetchClient.fetch(url, headers)`、`RetryAfter.parse(value, now)`；本地 HTTP fixture 验证状态/头、旧 get 错误语义、中断、每主机限流与 Retry-After 两种格式。
2. [x] 持久化：新增 `HttpFeedSnapshot(url, body, etag, lastModified)`、`FeedRefreshState(routePath, feedUrl, lastCheckedAt, lastSuccessAt, lastChangedAt, nextRetryAt, consecutiveFailures, lastHttpStatus, responseBytes, lastDurationMs, lastError, contentFingerprint)`。`FeedStore` 增加独立表与同步读写方法，验证重启、换源隔离、快照删除与已有条目兼容。
3. [x] RSS 垂直流程：先用真实 SQLite + 本地 HTTP 测试 `200+ETag → 304 → changed 200`，再实现；依次补冷启动304、失败保护缓存、退避重启恢复、no-store/Vary 边界、Last-Modified。
4. [x] 共享抓取与状态：`AppRuntime` 提供共享抓取协调器，`FeedFetcher` 在协调范围内统一持久化和日志；调度检查 RSS 下次重试时间，手动刷新走同一校验。健康视图显示刷新状态并过滤过时 feedUrl 的记录。验证 HTTP/调度并发仅一次加载及模板渲染。
5. [x] 验收：定向回归、全量 `./gradlew test bootJar`、审查 diff、更新 CONTEXT 与路线图。所有修改保留本地可审查状态。

## Review focus

- 旧接口及带身份头的来源不得被条件缓存改变。
- 失败、缓存正文损坏或删除不能让 304 丢失已有文章。
- 冷却期检查不算一次真实上游失败，也不延长冷却期。
- 配置换源、重启、同源跨入口并发都不能绕过正确来源身份。
- 测试采用本地 HTTP、临时 SQLite 和可注入时钟；不等待真实分钟，不读取开发配置。

协议依据：[RFC 9110 Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)、[RFC 9111 验证已有响应](https://www.rfc-editor.org/rfc/rfc9111.html#section-4.3.4)。

## Acceptance record — 2026-10-04

执行 `./gradlew test bootJar --warning-mode all` 成功。22 个测试类，共 **182 项测试，0 失败、0 错误、0 跳过**。产物：`build/libs/fetch-news-0.3.0.jar`。`git diff --check` 通过。仅有现有 SQLite native-access / JVM CDS 提示，无构建失败。

本轮新增 97 项行为回归（首次优化后的基线为 85 项），覆盖：

- 本地 HTTP + 真实 SQLite：200 → 重启 → 304 → 更新200；首次发现时间保留，新增条数准确。
- 缺失/损坏快照、连续冷304、无效正文、条件头更新、禁缓存策略，以及失败后保留有效正文。
- 秒数/HTTP日期 Retry-After、指数退避上限、退避期间不发请求且不增加失败日志、重启后的恢复。
- 每主机两个活动请求、跨端口共享预算、重定向目标限制、跨 origin 清除额外头、中断后释放并发许可。
- HTTP与定时任务共享一次抓取/持久化/日志，原始异常保真，不同来源互不合并。
- 换源后的独立状态与首次调度；旧请求晚完成不会覆盖新来源的退避。
- 独立内容指纹：禁缓存的相同正文不误报变化，共用同一URL的别名各自追踪内容更新。
- 管理页真实 Thymeleaf 渲染、错误转义、来源URL隐藏及过时配置过滤；持久化错误单独记录。

独立审查提出的变化时间误判已通过新增回归复现并修复，修复复核无新增阻塞项。保留用户已有的阅读器模板修改和 ADR；未提交、推送或部署。

## Usage and remaining boundaries

在 `/index` 创建或编辑挂载，选择 `/rss`、填入 HTTP(S) RSS/Atom URL 并启用定时。首次抓取后，健康页出现“RSS 刷新状态”；手动抓取和后台调度都遵守同一个重试期限。应用首次启动会创建两张新表，已有文章数据无需迁移。

本地测试没有访问真实订阅源、飞书或 Ollama。线上长期稳定性、真实流量收益以及七天更新频率样本仍待运行观察。当前的字节数是解码后正文的 UTF-8 大小；耗时包含请求等待，但没有独立排队时间指标。跨 origin 重定向会丢弃验证器，因此可能每次下载完整正文。新HTTP快照/刷新状态还没有自动清理旧URL记录。

已存文章仍沿用全局 URL 去重及原有路由归属模型，本轮没有重构跨来源的文章归属或阅读队列。下一阶段按路线图推进跨天未读与中文检索，再处理可恢复的本地 AI 任务。
