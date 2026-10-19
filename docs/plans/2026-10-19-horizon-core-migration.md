# Horizon 核心能力迁移计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本文是待评审计划，本次不实施功能。

**Goal:** 在 fetch-news 内提供 Profile 驱动的评分、筛选、事件去重和持久化日报，复用现有采集、SQLite、Ollama 和阅读器。

**Architecture:** 在采集落库之后增加独立的 `app.radar` 处理流水线，不让路由、RSS 请求或阅读页等待推理。日报使用输入与配置快照，评分、去重关系和生成内容独立保存，经薄控制器呈现。

**Tech Stack:** Spring Boot 4 / Java 25 / SQLite / Ollama / Jackson / Thymeleaf + HTMX + Alpine.js；首期不引入 Python 服务、向量数据库或 Agent 框架。

**Spec:** 本文“范围与设计约定”为该计划的设计依据；外部事实见 [Horizon 研究笔记](../research/2026-10-19-horizon.md)。用户已选择“核心优先：Profile、评分筛选、去重、日报”；具体默认值是本项目建议值，不代表已确认的产品要求。

## Global Constraints

- 所有数据库访问通过 `FeedStore`；新增调用兼容 `FeedStore == null` 的测试环境。
- `FeedFetcher` 继续独占 fetch → persist → log；来源请求通过 `FetchClient`，路由只生成原始 `Feed`。
- 不向 `FeedItem` 添加评分、Profile 或生成区块字段；不覆盖原文、阅读状态、收藏和已有摘要缓存。
- AI 默认关闭；日报请求返回任务状态，GET 页面及状态查询不能触发模型或文章抓取。
- 沿用显式注册和小类；英文代码与注释；新增前端脚本必须由 `FrontendAssetResource` 注册。
- 单实例部署；SQLite 短事务内不执行网络、模型调用或等待并发许可。
- 本次仅新增规划文档，不修改、提交或清理用户已有工作区改动。

## Review Focus

1. 同 URL 来自不同订阅：保留来源关联，并确定性选择一个 Profile；见任务 2、4。
2. 小模型返回非法 JSON、伪造链接或忽略约束：校验失败不能成为高分或错误合并；见任务 3–5。
3. 跨 Profile、不同日期或相同实体的不同事件：默认不合并；见任务 5。
4. 重启、重复提交、配置热更新和 AI 关闭：状态可解释、不重复发布、不混用配置；见任务 3、7、9。
5. 时间边界、超出候选上限、零条入选和部分失败：展示真实覆盖范围，区分空结果与错误；见任务 2、6–9。

## 范围与设计约定

### 1. 基线与能力对照

本地基线为 `42bb461`，同时阅读了当前未提交改动。实施前须重新核对基线，尤其是 `ReaderResource`、`BriefingSection`、新增 `BriefingStory` 和日报模板。

Horizon 固定研究版本：[`8f5446bf45c2d7763d17423d97d17906add85da1`](https://github.com/Thysrael/Horizon/tree/8f5446bf45c2d7763d17423d97d17906add85da1)。[中文说明](https://github.com/Thysrael/Horizon/blob/8f5446bf45c2d7763d17423d97d17906add85da1/README_zh.md)提出的核心是按 Profile 分析、筛选和去重，再为入选内容生成日报，而非单纯增加抓取源。

| 能力 | 本项目当前状态 | 本轮决策 |
|---|---|---|
| 多源采集、RSS/Atom、HN、GitHub Release、X | 已有路由；以 `AppRuntime` 实际注册为准 | 复用，不补全 Horizon 来源清单 |
| 全文与中文短摘要 | 已有 `ArticleExtractor` 和持久化单 worker 队列 | 保持；复用提取缓存，不用旧短摘要冒充结构化分析 |
| Profile、兴趣规则、来源绑定、自动匹配 | 未实现 | 迁移，先显式绑定再开启 auto |
| 0–10 分、理由、阈值 | 未实现 | 迁移，并保存失败与淘汰原因 |
| URL 去重 | SQLite 全局唯一 URL；首个来源拥有 `route_path` | 保持，增补来源关联 |
| 事件语义去重 | 当前工作区仅有同分类、足够长的同标题折叠 | 新增 Profile 内事件分组，保留所有原文 |
| 总量与类别配额 | 现有日报每分类最多展示 15 组，未做优先级选择 | 新增评分后的确定性选择 |
| 日报 | 动态查询当天文章，没有生成版本快照 | 新增可保存、重跑、查看状态的精选版 |
| 背景/方案/启示等可配置区块 | 只有固定短摘要 | 首期只从已保存正文生成，无外部检索 |
| 双语、联网背景、评论总结、云模型 | 未形成对应流水线 | 后续阶段 |
| 飞书 | 已有即时推送及定时分类摘要 | 原能力保留；精选日报投递后续接入 |
| 邮件、其他 Webhook、微信、Pages、MCP、配置向导 | 不同程度缺失 | 后续独立项目，不纳入本轮工期 |

### 2. 迁移方式

推荐 **Java 原生迁移行为与配置理念**。优势是一个部署单元、一套存储和任务状态，能复用已有阅读器。代价是结构化输出和去重要重新实现、测试和校准。

备选是将 Horizon 作为 Python sidecar，经 API 交换文章和日报；最初能更快复用功能，但会出现双配置、双缓存和两套任务生命周期。直接搬入整个仓库最难控制范围。按当前核心优先需求，采用原生方案。

不承诺兼容 Horizon 全部配置格式或得分完全一致。可参考其 MIT 许可下的提示词和规则；实际复制的文件或片段需保留相应版权及许可说明。首期内置 `tech-news`、`tech-blog` 两个示例，其余通过配置扩展。

### 3. 用户可见行为与默认值

- 现有 `/reader/briefing` 继续可读，默认保持原始日报。增加“精选日报”入口 `/reader/radar?date=YYYY-MM-DD`，避免模型故障使原始日报不可用。
- 首期中文，按 Profile 分组，展示分数、入选理由、内容区块、主报道和关联原文；Profile 不等于来源分类 `Route.category`。
- 独立 `radar-config.json` 默认 `enabled=false`、`scheduleCron=""`，不改现有 `RouteConfig` 构造器与 JSON 结构。
- 建议默认：`zoneId=Asia/Shanghai`、阈值 `7.0`、`maxItems=20`、`candidateLimit=200`、`maxModelCalls=500`、`runTimeoutMinutes=60`。时区可配置，提交任务时固定。
- 类别组上限默认不配置；所有上限均为 upper bound，不保底、不绕过阈值补满。分类组不允许重叠；未匹配类别归 `other`。按来源绑定类别，默认回退注册分类；首期不拿模型 tags 当配额类别。
- 来源绑定以实际 mount path 为键，支持固定 Profile、候选 Profile 列表、`auto`。未绑定来源使用默认 `tech-news`；`auto` 只在用户显式设置后启用。
- Profile 定义含 `id`、版本、路由说明、评分提示词、区块 ID/提示词/顺序；阈值、是否去重、来源绑定属于运行偏好。默认 Profile 从资源目录复制到数据目录，之后不覆盖用户修改。
- 配置错误必须明确报错；运行使用不可变配置快照，不静默回退另一 Profile。配置保存使用临时文件和原子替换，持久化失败不更新内存状态。

### 4. 流水线与边界

```text
既有 FeedFetcher → FeedStore 原始文章 + 来源关联
                              ↓
显式生成 / 定时提交 → 输入与配置快照 → Profile 路由
 → AI 评分 + 理由 + 短摘要 + tags → 阈值筛选
 → Profile 内事件去重 → 类别/总量上限
 → 仅对入选主报道生成区块 → 保存日报版本 → HTML / Markdown
```

**候选快照：** 按所选时区一天的 `first_seen_at` 半开区间 `[start,end)` 选取，首期不以 `pubDate` 排除晚到或无发布日期文章。不复用现有 `loadRecentItems` 的 500 条或 `loadItemsForDate` 的 1000 条隐式上限。创建任务时在短事务中复制正文、标题、原链接、时间和来源；候选按首次发现时间降序、URL 升序稳定排序，取前 200 条并保存完整候选数/遗漏数。超限显示“部分覆盖”，不是声称已分析全天。新表时间使用 epoch 毫秒；旧字符串时间查询沿用已有边界修复模式，最终用 `Instant` 比较。

**来源关系：** 新增 `(item_link, route_path)` 关系，在 `saveItems` 同一事务写入；不改变 `feed_items` 首源归属。旧数据只回填已知首源，不虚构历史来源。同 URL 多绑定的优先级为固定 Profile > 候选列表 > auto > 默认，相同优先级按 route path 字典序；同时记录选择依据及采用的配额类别。每篇文章最终只归一个 Profile。

**分析输入与缓存：** 优先保存的正文，再读提取缓存；两者不足时允许只凭标题/简介评分，但标记 `limitedInput=true`。入选后需要正文才调用 `ArticleExtractor`，抓取失败保留入选卡片并标记区块缺失。已有 `ai_summaries` 仍供普通阅读器使用；新分析缓存键为 `SHA-256(inputSnapshot + profileContent + promptVersion + modelIdentity)`。阈值变化复用分析，正文/提示词/模型变化失效；不以 URL 单独判断可复用。

**模型资源：** 为 `OllamaClient` 的所有生成调用共用一个可中断许可，覆盖现有摘要 worker、提取及新雷达 worker；避免多个 worker 同时占满本地模型。保留现有 `generate` 行为，新增结构化 JSON 调用。雷达运行锁定模型/host 身份，配置变化后停止新模型步骤并给出可重跑状态。关 AI 后不再发起新调用；已发出调用允许落库，随后暂停。许可不保证任务级公平，但每次调用后释放；两类队列用测试验证不会永久饥饿。

**评分：** JSON 结果严格校验有限数值 `0 <= score <= 10`、必填理由及摘要、tags 类型；来源文本作为不可信数据传入，不授予执行或网络工具。非法结果记录失败，可显式重试；不得置成 0 或高分，也不得照搬上游“失败但无阈值可通过”的兼容行为。

**事件去重：** 先阈值，再在同一运行、同一 Profile 内处理。按 score 降序、firstSeenAt 降序、URL 升序遍历，每篇与已有主报道做批量模型判断，候选集合分批最多 20 个主报道。模型只能返回提供的 ID 或 `distinct`；校验成员唯一归属、不跨 Profile、不自引用。拿不准或解析失败则保留独立事件并记录降级。不同进展/日期/版本不能仅因主题相同而合并；不做跨日报去重，不用向量库。主报道为排序最高者，原文不删除、不合并阅读状态。

**配额：** 遍历已排序事件，达到类别组上限就跳过，直至总量上限；关联原文不额外消耗名额。事件类别取主报道的确定性来源分类。组上限是跨 Profile 共用的上限，不分别重置。不同 Profile 的分数是各自规则下的主观结果，跨 Profile 排序沿用上游启发式，不宣称客观可比；用固定样本校准。未入选保留原因：低分、重复关联、类别超额、总量超额、分析失败或预算耗尽。

**区块：** `tech-news` 默认摘要和重要性；`tech-blog` 默认背景、方案、启示。输出为纯文本或文本列表，限制长度并转义，不直接渲染模型 HTML。不足以支撑结论时允许缺省区块并说明，无联网背景和虚构引用；链接仅取快照中的原链接。

**预算与发布：** 模型调用前检查调用数、截止时间及配置；预算耗尽或项目失败形成 `partial`，显示阶段计数与缺失原因。已有完成版本继续可读，失败重跑不得覆盖它。零条合格内容是合法空日报；存储失败是任务错误，不能呈现为空。

### 5. 持久化与类边界

新增表由 `FeedStore` 用现有事务机制初始化；不引入第二个连接所有者：

| 表 | 用途与关键约束 |
|---|---|
| `feed_item_sources` | `(item_link,route_path)` 主键，保存首次/末次发现；旧数据回填首源 |
| `radar_runs` | runId、日期/时区、版本、幂等键、配置快照、状态、阶段、统计、预算、错误、时间 |
| `radar_candidates` | `(run_id,item_link)` 主键；输入快照、hash、来源、所选 Profile/类别 |
| `radar_analyses` | 分析缓存键唯一；结构化结果、实际模型、prompt hash，不混入旧 summaries |
| `radar_decisions` | 每篇的分析引用、状态/原因、eventId、排序；运行内唯一归属 |
| `radar_reports` | runId 唯一；不可变报告 JSON 和 Markdown；只有原子保存后才可读取 |

`radar_runs` 状态为 `queued/running/paused/success/partial/failed`；阶段为 `routing/scoring/dedup/select/enrich/render`。单 worker 领取；启动时将中断的 running 转为 paused 并保留已完成步骤，由显式恢复继续。日报当前版本选择最新 success，否则最新 partial；失败版本仅在状态列表中可见。候选快照与报告长期保留；首期不自动清理。

`app.radar.RadarTypes` 集中定义该子域的不可变嵌套 records：`Candidate`、`Analysis`、`Event`、`Decision`、`Report`、`RunRequest`、`RunStatus`、`RunSnapshot`、`Selection`。下文签名省略此前缀。`Candidate` 包含输入副本及来源，`Event` 包含主报道与关联成员，`Report` 包含按 Profile 分组的纯文本区块和来源链接，`RunStatus` 包含阶段和计数。

新增 `RadarConfig`/`RadarConfigStore` 管运行偏好，`Profile`/`ProfileStore` 管 Profile；`ProfileRouter`、`ArticleAnalyzer`、`EventDeduplicator`、`DigestSelector`、`BlockGenerator`、`RadarRenderer` 各负责一个阶段。`RadarService` 管准入与状态，`RadarWorker` 管执行；其余通过 `AppConfig` 显式装配。

## 交付任务

每个任务先写行为测试确认失败，再最小实现、执行对应测试并单独提交；提交只包含该任务路径，避免夹带当前工作区改动。任务 1–8 构成核心版本，任务 9 开启自动运行并完成验收。

### Task 1：Profile 与配置契约

**Files:** 新增 `src/main/java/app/radar/{RadarTypes,RadarConfig,RadarConfigStore,Profile,ProfileStore}.java`；新增 `src/main/resources/radar/profiles/{tech-news,tech-blog}/` 下 `profile.json`、`scoring.md`、各区块 Markdown；测试 `src/test/java/app/radar/ProfileStoreTest.java`、`RadarConfigStoreTest.java`。

**Interfaces:** `RadarConfigStore.get(): RadarConfig`、`save(RadarConfig): void`；`ProfileStore.loadAll(): Map<String,Profile>`。配置用 `fetch-news.radar-config`、`fetch-news.radar-profiles` 指定路径，便于测试隔离。

- [ ] 写测试：无文件默认禁用；两内置 Profile 可加载；阈值越界、未知 Profile、空候选列表、非法时区/cron、重叠类别组拒绝；提示词路径越出 Profile 目录拒绝；写盘失败内存不变；自定义提示词不被启动覆盖。
- [ ] 执行 `./gradlew test --tests 'app.radar.ProfileStoreTest' --tests 'app.radar.RadarConfigStoreTest'`，确认新契约尚未实现而失败。
- [ ] 实现配置校验、不可变对象、原子保存、默认资源初始化与配置 hash；不执行模型请求。
- [ ] 重跑上述测试，预期全部通过；输出非法配置的具体字段与原因。
- [ ] 单独提交：`feat: add radar profiles and configuration`。

### Task 2：来源关联、运行快照与持久化

**Files:** 修改 `src/main/java/app/store/FeedStore.java`；实现 `RadarTypes` records；新增 `src/test/java/app/store/RadarStoreTest.java`。

**Interfaces:** `FeedStore.createRadarRun(RunRequest request, RadarConfig config, Map<String,Profile> profiles): RunStatus`；`loadRadarRun(String runId): RunSnapshot`；`saveRadarAnalysis(String runId, Analysis analysis): void`；`saveRadarDecisions(String runId, List<Decision> decisions): void`；`saveRadarReport(String runId, Report report, String markdown): void`。创建时事务保存输入和配置，不只保存 URL。

- [ ] 写测试：旧库升级不丢原文/收藏/FTS；同 URL 双来源只存一篇但有两关联；首源归属不变；新插入或正文变更不改变既有快照；午夜、混合时间精度、200/201 条及同时间排序；保存报告失败原版本可读。
- [ ] 执行 `./gradlew test --tests 'app.store.RadarStoreTest'`，确认新方法缺失的失败。
- [ ] 新增表、索引、首源回填和短事务方法；在 `saveItems` 同事务补来源。候选查询不复用限额的阅读器方法，截断同时记录总数。
- [ ] 重跑新测试及 `./gradlew test --tests 'app.store.*' --tests 'app.FeedFetcherPersistenceTest'`；预期全部通过。
- [ ] 单独提交：`feat: persist radar inputs and source provenance`。

### Task 3：结构化输出和共享模型准入

**Files:** 修改 `src/main/java/app/ai/OllamaClient.java`；新增 `src/main/java/app/ai/AiGenerationGate.java`；修改/新增 `src/test/java/app/ai/{OllamaClientTest,AiGenerationGateTest}.java`。

**Interfaces:** 保留现有生成方法；增加 `OllamaClient.generateJson(String prompt, AiConfig expected): Generation`。`AiGenerationGate` 使用一个可中断的公平 semaphore，生成方法内部持有许可并在所有退出路径释放；结构化调用固定并校验 expected 身份，保留实际 model 元数据。

- [ ] 用本地假 Ollama 写测试：请求带 JSON format；同时普通摘要、提取、雷达调用时活跃模型请求最多 1；异常/中断释放许可；等待期间禁用 AI 不发请求；host/model 变化结构化步骤失败；已有普通调用仍保留请求时模型。
- [ ] 执行 `./gradlew test --tests 'app.ai.OllamaClientTest' --tests 'app.ai.AiGenerationGateTest'`，观察对应新行为失败。
- [ ] 实现生成许可和 JSON API，连接探测不占用生成许可；检查配置在取得许可后进行，日志不打印正文/完整配置。
- [ ] 重跑 `./gradlew test --tests 'app.ai.*'`；确认旧摘要与队列行为通过。
- [ ] 单独提交：`feat: support bounded structured Ollama generation`。

### Task 4：Profile 路由与评分筛选

**Files:** 新增 `src/main/java/app/radar/{ProfileRouter,ArticleAnalyzer}.java`；新增同名 `src/test/java/app/radar/` 测试。

**Interfaces:** `ProfileRouter.resolve(Candidate candidate, RunSnapshot run): String`；`ArticleAnalyzer.analyze(Candidate candidate, Profile profile, RunSnapshot run): Analysis`。固定绑定不调用路由模型；auto/候选只接受快照中允许的 Profile ID。缓存读写经 FeedStore，由 worker 保存阶段结果。

- [ ] 写测试：固定/候选/auto/默认、多来源优先级、候选外 ID 拒绝；7.0 通过而 6.99 淘汰；负数、11、NaN、缺字段及模型失败不通过；正文/模型/提示词变化失效而阈值变化复用；输入不足有标记。
- [ ] 执行 `./gradlew test --tests 'app.radar.ProfileRouterTest' --tests 'app.radar.ArticleAnalyzerTest'`，确认新功能失败。
- [ ] 实现路由和评分 JSON 契约、schema 校验、缓存键与理由；先以显式绑定打通，再增加 auto/候选分支，均不修改原 FeedItem。
- [ ] 重跑上述测试；预期所有失败分析都有明确原因，无异常结果混入入选集合。
- [ ] 单独提交：`feat: route and score radar articles by profile`。

### Task 5：保守的事件语义去重

**Files:** 新增 `src/main/java/app/radar/EventDeduplicator.java`、`src/test/java/app/radar/EventDeduplicatorTest.java`。

**Interfaces:** `EventDeduplicator.group(List<Analysis> passed, RunSnapshot run): List<Event>`。ID 基于运行及主报道稳定生成；返回每个通过分析的文章且仅出现一次，降级写入运行诊断。

- [ ] 写测试：同事件不同标题形成主/关联报道；同主题不同事件、不同日期进展和版本不合并；跨 Profile 不合并；单成员无模型调用；伪造 ID/重复归属/非法 JSON 保持独立；输入顺序变化不改变主报道。
- [ ] 执行 `./gradlew test --tests 'app.radar.EventDeduplicatorTest'`，确认失败。
- [ ] 实现确定性排序、按 Profile 分区、每批最多 20 个主报道的候选判断；保留原文及链接，失败不删除项目，预算计数交给 worker 的运行上下文。
- [ ] 重跑测试，确认未入选与关联报道均可追溯，不写 `read_items` 或删除 `feed_items`。
- [ ] 单独提交：`feat: group duplicate radar events conservatively`。

### Task 6：配额、区块与确定性渲染

**Files:** 新增 `src/main/java/app/radar/{DigestSelector,BlockGenerator,RadarRenderer}.java` 及各自测试；模型 HTML 转义沿用 Thymeleaf，Markdown 独立转义。

**Interfaces:** `DigestSelector.select(List<Event> events, RadarConfig config): Selection`；`BlockGenerator.generate(Event event, Profile profile, RunSnapshot run): Map<String,List<String>>`；`RadarRenderer.markdown(Report report): String`。`Selection` 含入选事件及所有其余事件原因；Report 引用完整选择结果。

- [ ] 写测试：总量 20；跨 Profile 的 AI 类别组上限 5；other 上限；配额跳过可选下一类别但不以低分补位；关联原文不多占份额；同分稳定排序；0 条合法；区块失败只缺该区块；恶意 HTML/链接不注入；同一报告导出字节一致。
- [ ] 执行 `./gradlew test --tests 'app.radar.DigestSelectorTest' --tests 'app.radar.BlockGeneratorTest' --tests 'app.radar.RadarRendererTest'`，确认失败。
- [ ] 实现纯 Java 配额选择，只对入选主报道调用区块生成；正文不足时才使用现有 ArticleExtractor，失败保留报告和缺失原因；生成内容不写入原文或旧 summary 缓存。
- [ ] 重跑测试，预期离线假模型覆盖所有结果；报告注明 AI 生成及输入限制。
- [ ] 单独提交：`feat: select and render profile based radar reports`。

### Task 7：可恢复的后台流水线

**Files:** 新增 `src/main/java/app/radar/{RadarService,RadarWorker}.java`；修改 `FeedStore.java`、`AppConfig.java`；新增 `src/test/java/app/radar/{RadarServiceTest,RadarWorkerTest}.java`。

**Interfaces:** `RadarService.submit(RunRequest request): RunStatus`、`status(String runId): RunStatus`、`resume(String runId): RunStatus`；`RadarWorker.processNext(): void`、`start(): void`、`close(): void`。补充 FeedStore 的原子领取、阶段保存、暂停、恢复方法，状态修改只能经 store。

- [ ] 写测试：并发重复提交同幂等键共用 run；同日期显式重跑使用新键与新版本；阶段后重启只继续未完成步骤；AI 关闭暂停、恢复继续；500 次或 60 分钟停止新增调用；候选截断/项目失败为 partial；持久化失败不发布；null-store 不启动；关闭 worker 不接新活。
- [ ] 执行 `./gradlew test --tests 'app.radar.RadarServiceTest' --tests 'app.radar.RadarWorkerTest'`，确认失败。
- [ ] 实现单 worker、逐阶段 checkpoint 和运行诊断。模型调用使用任务 3 的入口；普通阅读摘要与雷达分析保持独立队列，共享推理许可。缓存命中不计为模型调用。
- [ ] 重跑上述及 `app.ai.*` 测试，确认未改变原摘要队列成功/失败/显式重试语义。
- [ ] 单独提交：`feat: run resumable radar pipelines in background`。

### Task 8：精选日报页面、状态与 Markdown 导出

**Files:** 新增 `src/main/java/app/RadarResource.java`、`src/main/java/app/reader/RadarPageView.java`、`src/main/resources/templates/reader/radar.html`；修改 `AdminTemplateRenderer.java`、`reader/briefing.html`；必要时新增 `static/radar.js` 并修改 `FrontendAssetResource.java`；新增 `src/test/java/app/RadarReaderTest.java`。

**Interfaces:** `GET /reader/radar?date=` 只读取已保存报告；`POST /reader/radar/runs` 提交日期/时区/幂等键并返回 202 + runId；`GET /reader/radar/runs/{runId}` 只读状态；`POST /reader/radar/runs/{runId}/resume` 显式恢复；`GET /reader/radar/runs/{runId}/markdown` 下载该版本。日期/时区非法 400，无 run 404，存储不可用 503，禁用时拒绝新生成但允许历史阅读。

- [ ] 写测试：GET 不抓取/不推理；无报告、合法空报告、排队/暂停/partial/失败各有明确状态；历史版本保持内容；所有关联原文及站内阅读可达；原始日报和阅读/收藏行为不变；转义模型文本；导出对应固定 runId。
- [ ] 执行 `./gradlew test --tests 'app.RadarReaderTest' --tests 'app.BriefingReaderTest'`，确认新增端点失败。
- [ ] 实现薄控制器和模板，生成按钮仅提交任务；丢响应后可查询状态，不自动再 POST；提供阶段计数、截断数、入选/淘汰原因。首期配置通过 JSON/Markdown 文件，UI 不扩展为完整 Profile 编辑器。
- [ ] 重跑上述测试和 `app.ReaderInteractionTest`、`app.ReaderArticleTest`；预期全部通过。
- [ ] 单独提交：`feat: expose saved radar reports in reader`。

### Task 9：定时运行与迁移验收

**Files:** 新增 `src/main/java/app/radar/RadarScheduler.java`、`src/test/java/app/radar/RadarSchedulerTest.java`、`src/test/java/app/RadarPipelineTest.java`、`src/test/resources/radar/scenarios.json`；修改 `build.gradle` 的测试路径隔离、`CONTEXT.md`、`README.md` 和 Docker 挂载文档。

**Interfaces:** `RadarScheduler.tick()` 使用注入 `Clock` 与配置时区，默认不启用。首期开启后按六字段 cron 为“前一个本地自然日”提交；幂等键为日期 + 时区 + 配置 hash，历史补跑通过显式请求。定时生成不承担来源刷新；来源仍由 FetchScheduler 独立采集。

- [ ] 写测试：重复 tick/重启不重复提交；错过触发时间启动后只补最近一个到期窗口；时区和 DST 边界；晚到文章按 firstSeen 而非 pubDate 归属日期，同日快照之后新增的文章只在显式重跑中出现；关闭雷达不影响抓取和短摘要；新配置/目录不会在测试中触及真实数据。
- [ ] 执行 `./gradlew test --tests 'app.radar.RadarSchedulerTest' --tests 'app.RadarPipelineTest'`，确认新自动化行为失败。
- [ ] 接入独立定时提交；在 build.gradle 隔离 radar 配置/Profile 路径；增加配置样例、数据卷、暂停/恢复、备份和版本回退说明。首期仅生成与下载，不自动投递飞书。
- [ ] 执行 `./gradlew test bootJar --console=plain` 与 `git diff --check`；所有自动测试使用临时 SQLite、本地假 HTTP/假 Ollama。隔离环境在 320/390/768/1440 宽度检查原始与精选日报、断网、状态恢复和导出。
- [ ] 真实 Ollama 用固定 40 篇标注样本离线评估：两 Profile 各 20 篇，至少 8 组重复事件及 8 对同主题不同事件；记录 JSON 首次合法率、误合并、筛选一致性、总时间、调用次数和内存。目标为 JSON 首次合法率 ≥95%、样本误合并为 0、入选/不入选与人工二分类一致率 ≥80%。若未达到，保持功能可试用但默认关闭，先调提示词/模型，不伪称质量达标。
- [ ] 单独提交：`feat: schedule and validate core radar migration`。

## 里程碑、依赖与投入

| 里程碑 | 对应任务 | 可验收结果 | 单人估算 |
|---|---|---|---|
| M1 基础与契约 | 1–3 | 配置可加载、输入可追溯、模型并发受控 | 3–5 工作日 |
| M2 核心选择 | 4–6 | 固定数据集可路由、评分、分组、限额、导出 | 4–6 工作日 |
| M3 可用产品 | 7–8 | 异步生成、恢复、历史精选日报可读 | 3–5 工作日 |
| M4 自动化与质量 | 9 | 可选定时、回归与真实模型评估完成 | 2–4 工作日 |

总计约 **12–20 个工作日**，是粗略工程投入，不是交付承诺；模型质量与正文覆盖是主要不确定性。依赖顺序：1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9。若要最早验证价值，M2 先使用显式绑定跑通一组本地样本，再验证 auto，不先扩来源。

## 发布与回退

- 开始实施前整理当前未提交日报改动的归属，避免覆盖；本计划不要求替用户提交这些改动。
- 升级前停应用并备份 SQLite 与全部配置目录，或使用 SQLite 一致性备份；不直接复制正在写入的数据库文件。
- 新表为增量，原始数据/旧摘要结构不变；先手动生成，再对少量来源开启定时。
- 发现问题时关闭 radar 配置，继续使用原始日报。需要二进制回退时验证旧版对增量表的兼容；若要恢复备份，先停机并明确恢复点后的新增数据影响。
- 每个版本显示输入范围、配置/model/prompt 标识、分析/淘汰/合并/入选/失败数及耗时；不依赖读者自行推测“今天是不是没新闻”。

## 后续能力顺序

1. 核心质量稳定后：双语生成、将保存的精选日报接入现有飞书通道；投递游标与失败重试单独设计，不复用当前内存游标承诺 exactly-once。
2. 按需增加社区评论与联网背景；先补 HN/Reddit 的讨论元数据边界，正文和模型输入按不可信数据处理，限制工具权限和请求预算。
3. 按真实订阅需求补 Telegram、Reddit、GitHub 用户动态等；Google News 优先作为通用 RSS 挂载，OpenBB/Apify 等独立评估账号、费用和部署依赖。
4. 最后再考虑多供应商、MCP、邮件订阅/退订、Pages、微信、Profile 编辑器与推荐向导，各自单独定义范围和验收。

## 本次检查记录

- 已核对实际源码与现有计划目录；AGENTS.md 部分路由文件清单已落后于当前注册，以源码为准。
- 已将“现有同标题折叠”与“新增语义去重”区分，未把上游全部能力纳入首期。
- 已检查任务间签名、顺序、模型并发、来源归属、时间边界、预算和回退约束。
- 本次只有文档变更，不运行应用或真实模型，不将此前已有测试结果作为本次功能验证。

## 实施进度（2026-10-19）

任务 1–9 的核心代码已在 `feat/horizon-core` 实现，具体记录见 [实施与验证](../research/2026-10-19-radar-implementation.md)，启用方式见 [精选日报文档](../radar.md)。322 项测试及 bootJar 构建通过；真实 Ollama 与 40 篇人工标注样本质量评估仍待完成，功能默认关闭。本文上方复选框保留原实施步骤，实际完成与偏差以实施记录为准。
