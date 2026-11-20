# AI 平台与模型配置

在管理后台的「AI 设置」添加提供商。一个提供商共用 API 基础地址和密钥，可以保存多个模型 ID。每个模型有独立的生成接口、输出上限和附加参数。摘要、全文提取、必知 / 精选和精选日报使用当前提供商的默认模型，输入与结果都是文字。

## 添加并验证模型

1. 点击「添加自定义提供商」，选择 API 协议，填写名称、基础地址和密钥。
2. 点击「添加模型」，可以直接填写模型 ID，也可以先获取平台的模型列表。第一个模型会成为默认模型。
3. 在「配置与测试的模型」选择要编辑的模型，展开「模型高级选项」。更换正在编辑的模型会保留其他模型的草稿。列表里的默认模型决定新闻任务实际使用哪个模型。
4. 「测试连接」读取模型列表。「测试模型生成」会用当前表单、所选模型和实际生成参数发送一条简短请求，校验返回的 JSON，可能产生少量费用。两项测试均可在保存前和全局 AI 关闭时使用。
5. 点击「保存提供商」保存整份表单，或点击「保存并启用」同时切换当前提供商。已启用提供商的设置保存后立即生效。第一次使用时，在页面上方开启「AI 功能」。

平台未实现模型列表接口时，可手动填写 ID 后直接测试生成。模型能列出、能按指令输出 JSON、能在给定额度内完成一篇长文分析，分别需要相应请求来验证。

## 协议和参数示例

| API 协议 | 基础地址示例 | 文本生成接口 | 附加生成参数示例 |
|---|---|---|---|
| OpenAI 兼容 | `https://api.openai.com/v1` | Chat Completions 或 Responses | `{"reasoning_effort":"low","verbosity":"low"}` |
| Anthropic 兼容 | `https://api.anthropic.com/v1` | Messages | `{"thinking":{"type":"enabled","budget_tokens":1024}}` |
| Gemini | `https://generativelanguage.googleapis.com/v1beta` | generateContent 或 streamGenerateContent | `{"thinkingConfig":{"thinkingBudget":1024}}` |
| Ollama | `http://localhost:11434` | /api/generate | `{"temperature":0.4,"num_ctx":8192,"think":false}` |

参数支持情况取决于具体模型。选项可以留空，使用平台默认值。最大输出 Token、原生 JSON 和 OpenAI 接口类型在表单中配置。附加参数使用 JSON 对象，可填写采样、推理、停止序列等协议原生字段。流式模型可设 `{"stream":true}`，服务端收齐并验证结果后再保存。工具调用、文件上传、模型输入、认证和地址由应用控制。

OpenAI、Anthropic 的基础地址可以带中转服务的路径前缀，根地址默认补 `/v1`。Gemini 根地址默认补 `/v1beta`。完整生成地址会归一化为基础地址。Ollama 使用服务根地址，Docker 中的 `localhost` 指运行本应用的容器。只有 Ollama 配置受 `OLLAMA_HOST` 影响。

## 兼容与错误处理

- OpenAI 选「自动选择」会先使用 Chat Completions。如果平台明确提示端点不适用，或端点不存在，会在同一地址尝试 Responses。也可以为模型指定固定接口。
- 对于 Chat 输出上限，自动模式优先发送 `max_completion_tokens`。平台明确拒绝上限字段时，会尝试另一种拼写，并保留该模型的输出额度。Responses 使用 `max_output_tokens`，Anthropic 使用 `max_tokens`，Gemini 使用 `maxOutputTokens`，Ollama 使用 `num_predict`。
- 结构化任务会提示模型返回 JSON。启用原生 JSON 的接口还会发送协议对应的格式参数。平台明确拒绝这个格式参数时，会继续通过提示词请求 JSON，日报仍会校验结果的字段和类型。
- 自动协商只调整同一模型的传输细节。成功的组合在内存中暂存，缓存按平台、模型、参数和凭据隔离。模型明确拒绝用户填写的生成参数时，会提示调整该参数。
- 向量、图像和音频模型可能也出现在模型列表里。如果接口拒绝文本任务或只返回其他类型的结果，任务会报告原因。账号权限、额度、输出截断、拒答、没有正文或连接中断也会产生相应错误。阅读页可以在修正配置后重试。
- 平台的原始错误可能包含文章或认证信息，页面和任务记录使用本地错误说明。已识别的 HTTP 错误显示状态码、原因，以及平台指出的生成字段。平台未提供可识别原因时，显示可确认的状态和排查方向。

## 密钥、历史数据与任务恢复

- API Key 存在服务端的 `ai-config.json`。编辑时密钥留空表示沿用原密钥，也可以勾选清除。更换协议或服务器 origin 时需重新输入密钥或明确清除。文件原子替换，在支持 POSIX 的系统上权限为 `0600`。
- 配置使用 `activeProfile`、全局 `enabled` 和 `profiles`。每个提供商的 `models` 保存 ID 列表，`config.model` 指定默认模型，`modelCapabilities` 按模型 ID 保存参数。旧版提供商级选项会分配给原来的每个模型，更早的单一 host/model 配置按 Ollama 读取。下次保存时写入新格式。
- 精选日报保存无密钥的配置快照。切换平台、模型或生成设置会使进行中的日报暂停，分析缓存也会区分这些设置。轮换密钥可以沿用原模型身份。已成功生成的文章摘要继续复用。
- 可通过 `-Dfetch-news.ai-config=/path/ai-config.json` 指定文件；Docker 继续使用 `/data/ai-config.json`。

字段与事件格式见各平台文档。[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create) · [OpenAI Responses](https://developers.openai.com/api/reference/resources/responses/streaming-events) · [Anthropic Messages](https://platform.claude.com/docs/en/build-with-claude/streaming) · [Gemini generateContent](https://ai.google.dev/api/generate-content) · [Ollama 流式响应](https://docs.ollama.com/api/streaming)
