# AI 平台与模型配置

在 `/index` → **AI 设置** 中管理多个平台配置。摘要、全文提取和精选日报共用“当前使用”的平台及一个生成并发许可；默认仍为关闭的 Ollama 配置。

## 设置流程

1. 点击“新增平台”，填写名称、协议、API 地址、模型 ID 和密钥。
2. 根据平台选择最大输出 Token、输出上限字段及 JSON 模式。
3. 点击“保存并使用”，新配置立即成为当前平台。AI 开关是全局开关，切换平台保留此开关；表单保存会应用开关当前值。
4. 用“切换使用”选择已保存的平台；“编辑”不会自动切换。当前平台不能直接删除，先切换到另一平台，再删除旧配置。

“测试当前平台连接”只测试**已保存且当前选中的配置**，不使用未保存表单内容、不进行模型推理。Ollama 查询版本与模型；远程协议查询模型列表。列表包含模型不代表实际生成一定成功；404/405 表示平台没有提供该接口，可继续手动填写模型 ID。

## 协议与地址

| 协议 | 地址示例 | 调用及认证 |
|---|---|---|
| Ollama | `http://localhost:11434` | `/api/generate`；沿用现有行为 |
| OpenAI Chat Completions | `https://api.openai.com/v1` 或第三方 `https://relay.example/prefix/v1` | `/chat/completions`，`Authorization: Bearer …` |
| Anthropic Messages | `https://api.anthropic.com/v1` 或第三方 `https://relay.example/prefix/v1` | `/messages`，`x-api-key` 与 `anthropic-version: 2023-06-01` |

远程协议也接受完整生成地址，例如 `https://relay.example/v1/chat/completions`、`https://relay.example/v1/messages`，不会重复追加路径。根地址 `https://relay.example` 默认补 `/v1`；有自定义路径时保留该前缀。请使用平台实际提供的地址和模型 ID，不需要在项目中预先登记模型。

OpenAI 兼容协议支持 `max_tokens` 或 `max_completion_tokens`，默认前者；按平台/模型要求选择。需要后者的模型可以在设置页切换。Anthropic 使用必需的 `max_tokens`。Ollama 沿用原有生成请求，不应用此远程 Token 选项。

OpenAI 的原生 JSON 模式会发送 `response_format: {"type":"json_object"}`；不支持该字段的平台可关闭。Anthropic 使用明确的 JSON 提示词，不发送 OpenAI 特有字段。精选日报对结果始终严格校验，非法 JSON、截断响应、拒绝或需要工具调用的响应不会成为正常分析结果。目前是非流式单轮文本调用，不含工具执行、多模态输入或自动平台回退。

## 密钥、迁移与任务恢复

- API Key 保存在服务端的 `ai-config.json`，页面只显示“已配置”，不会回填原密钥。编辑留空表示保留，勾选“清除已保存密钥”才能清除。
- 改协议或目标 origin（协议/主机/端口）时，需重新输入密钥或显式清除；不会将原平台密钥自动带到新平台。
- 密钥只允许可用于 HTTP 头的可打印 ASCII，保存前会拒绝隐藏字符。错误内容不包含密钥或平台返回的原始错误响应。
- 支持 POSIX 权限的系统将配置文件保存为 `0600`；配置保存为原子替换，写盘失败不改变正在使用的设置。配置文件已经在 `.gitignore` 中。
- 旧 `{host, model, enabled}` 配置在读取时作为一个名为 Ollama 的默认平台，首次保存写为多平台格式。旧三参数 Java 构造器仍可用。
- `OLLAMA_HOST` 只覆盖当前 Ollama 平台的地址，不影响 OpenAI/Anthropic 平台。
- 精选日报仅保存去除密钥的协议、地址、模型及生成选项快照。API Key 不进入日报快照或分析缓存键。
- 切换协议、地址、模型或生成选项会使未完成的精选日报暂停，并隔离后续分析缓存。恢复原配置后可继续，或生成新版本；已发出的请求仍使用发送时的配置。单纯轮换密钥不改变模型身份。
- 原有成功摘要继续复用，不因切换平台而自动重新生成。全局 AI 关闭后，已有内容仍可阅读，队列暂停新推理。

新配置格式包含 `activeProfile`、全局 `enabled` 和 `profiles` 数组，每个平台包含 `id`、`name`、`config`。可继续通过 `-Dfetch-news.ai-config=/path/ai-config.json` 指定文件。Docker 继续使用现有 `/data/ai-config.json` 挂载，不需要新增密钥文件。回退到只认识旧格式的版本前应保留新配置和数据库备份。

## 验证

本功能使用本地假 HTTP 平台与临时 SQLite 验证协议 payload、路径前缀/完整 endpoint、认证头、响应截断、密钥脱敏、多平台迁移/保存/切换/删除、配置保存失败，以及两种远程协议的完整精选日报流程。浏览器验证新增、切换、连接测试、留空保留密钥和修改目标后的错误提示，并检查 320/390/768/1440 宽度。未使用真实平台密钥或调用付费模型；第三方平台的实际兼容性仍以其实现为准。

协议依据：[OpenAI Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)、[Anthropic 官方 Messages 示例](https://github.com/anthropics/anthropic-sdk-python#getting-started)、[Anthropic 官方认证头实现](https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/_client.py)。
