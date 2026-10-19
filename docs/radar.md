# 精选日报（Horizon 核心能力迁移）

在现有采集之后增加 Profile → 评分 → 阈值筛选 → 事件去重 → 配额 → 区块生成。原始 RSS、阅读器、收藏、短摘要及飞书推送保持各自行为。精选日报目前输出中文 HTML 和 Markdown，不自动投递。

## 启用

1. 在 `/index` 配好现有来源及 Ollama，并启用 AI。雷达和短摘要共用一个生成许可，最多一个请求进行模型推理。
2. 在应用工作目录创建 `radar-config.json`，例如：

```json
{
  "enabled": true,
  "zoneId": "Asia/Shanghai",
  "scheduleCron": "",
  "defaultProfile": "tech-news",
  "candidateLimit": 200,
  "maxItems": 20,
  "maxModelCalls": 500,
  "runTimeoutMinutes": 60,
  "profileSettings": {
    "tech-news": { "threshold": 7, "topicDedup": true },
    "tech-blog": { "threshold": 7, "topicDedup": true }
  },
  "sources": {
    "/my-news": { "profiles": ["tech-news"], "category": "AI" },
    "/my-blog": { "profiles": ["tech-blog"], "category": "Engineering" }
  },
  "categoryGroups": {
    "ai": { "limit": 5, "categories": ["AI"] }
  },
  "defaultGroupLimit": 0
}
```

3. 重启应用以读取配置，访问 `/reader/radar`，选择日期并生成。普通页面刷新和状态查询不会发起模型或来源请求。已有日报在 AI 关闭时仍可阅读。

来源键必须是实际订阅 mount path。未绑定时使用默认 Profile；可设置 `profiles: ["auto"]` 或候选列表 `["tech-news","tech-blog"]`，每篇最终只归一个 Profile。自动匹配额外消耗模型调用，建议先显式绑定验证质量。同 URL 多来源时，固定 Profile 优先于候选列表，再优先于 auto；同优先级按路径排序。

应用第一次启动会在 `radar-profiles/` 安装 tech-news 和 tech-blog。修改其 `profile.json` 及 Markdown 提示词可改变评分准则、匹配依据和内容区块；每次新提交读取一次，已有运行使用快照。Profile 和运行配置中的 ID 必须匹配，提示词不能引用 Profile 目录外的文件。运行配置文件修改需重启；Profile 提示词修改对下一次新提交生效。

支持 JVM 参数 `-Dfetch-news.radar-config=/path/radar-config.json` 和 `-Dfetch-news.radar-profiles=/path/profiles`。Docker 将两者放在现有 `/data` 数据卷中，宿主分别为 `data/radar-config.json`、`data/radar-profiles/`，重建镜像后沿用现有挂载即可。

## 日报语义

- 文章按**首次发现时间**归属所选时区的自然日，不按发布日期。晚到文章属于首次发现的日期；不伪造 pubDate。
- 创建任务时保存标题、正文、来源、配置和模型身份。后续抓取不改变旧快照；同日期“生成新版本”会保存新版本。
- 默认只分析最新 200 篇候选，页面显示总数和未覆盖数量。配额只设上限，不用低分内容补满，也不保证每类最低数量。
- 分数范围为 0–10；达到 Profile 阈值才可入选。非法 JSON、缺字段、模型失败均记录为分析失败，不伪装成低分。
- 去重仅在本次运行的同一 Profile 内进行。不同已知本地发布日期、明确不同版本不合并；其余依赖模型保守判断。关联原文均保留，不改变原文、收藏或阅读状态。不同日期和版本的确定性排除可能保留更多重复报道，这是有意采用的保守策略。
- 入选文章才生成内容区块。正文不足时允许取全文；抓取失败或某一区块无效不删除文章，保留其他有效区块并显示缺失原因。生成内容均标识为 AI 内容，不能代替原文核验。
- `success` 为完整结果，`partial` 表示超限、分析或区块失败、预算耗尽、去重降级。零条通过阈值也是合法结果；数据库故障是任务失败。
- 同一天优先显示最新成功版本，没有成功版本时显示最新部分版本。最新生成失败不覆盖原有成功日报。下载链接绑定具体版本。

## 定时、恢复与预算

设置 `scheduleCron: "0 0 8 * * *"`，按配置时区每天 08:00 为前一个自然日提交任务。每分钟检查，错过触发时间时只补最近一个到期窗口；重复 tick 和重启使用同一持久化幂等键。配置、Profile 或模型身份变化可产生新版本。来源抓取仍由原调度器负责，日报任务不主动刷新订阅。

运行中关闭 AI/雷达或切换模型会暂停新步骤；已发出请求的结果可保存，但发布前再次检查。恢复原模型后点击“恢复任务”，或者生成新版本。重启会把中断任务标记为暂停，保留已保存结果，不自动反复重试失败。评分按文章保存、区块按入选报道保存、去重按完成阶段保存；去重阶段中断可能再次调用模型，不能承诺跨崩溃 exactly-once。

调用数在请求前保存；预算覆盖提取正文所需的模型调用。时间预算在一次模型调用之间检查，单次请求超时仍为 120 秒。崩溃恢复会保守地将未结算运行时段（含停机间隔）计入预算，防止反复重启重置预算。耗尽预算产生明确的部分日报，不自动提高上限。

目前只支持单应用实例。停用雷达即可继续用原始日报。升级前停机备份 SQLite、所有配置及 Profile 目录，或使用 SQLite 一致性备份。不要直接复制正在写入的数据库文件。新表为增量结构；恢复旧备份会丢失恢复点后的新增数据，回退前应先保存当前数据。

## API

| 方法与路径 | 行为 |
|---|---|
| GET `/reader/radar?date=YYYY-MM-DD&zoneId=Asia/Shanghai` | 只读日报页 |
| POST `/reader/radar/runs` | 表单 `date`、`zoneId`、`requestKey`，返回 202 + run 状态 |
| GET `/reader/radar/runs/{runId}` | 只读状态、阶段与调用数 |
| POST `/reader/radar/runs/{runId}/resume` | 显式恢复暂停任务 |
| GET `/reader/radar/runs/{runId}/markdown` | 下载固定版本 |

同一 requestKey 重试不会生成第二个任务；不同日期/时区不能共用同一 key。配置禁用返回 409，存储不可用返回 503，未知任务或尚无报告返回 404。提交响应丢失时刷新页面查询，不自动重复 POST。

## 验证边界

自动化使用临时 SQLite 和本地假 Ollama，验证真实 HTTP、持久化、阈值、配额、故障、重启和页面，不访问真实订阅或真实模型。固定场景位于 `src/test/resources/radar/scenarios.json`。

本次机器上 `127.0.0.1:11434` 无可用 Ollama，因此**未完成真实模型质量评估**。计划中的 40 篇人工标注样本、JSON 首次合法率 ≥95%、误合并为 0、筛选一致率 ≥80% 仍是启用日常自动运行前的质量门槛，不能用假模型测试代替。功能默认关闭；建议先用少量显式绑定来源手动试跑，检查评分和事件分组。
