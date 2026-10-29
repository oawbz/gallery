# Gallery 本地 API 与 OpenAI API 对比

适用版本：1.0.20-api5。以 OpenAI 官方 Python SDK 3.41.0 的 Chat Completions、Responses 类型作为线协议验证依据。公开 `/v1` 仅使用官方存在的接口和字段，不添加自定义返回字段。目标是本地模型聊天与 Agent 接入；模型实际能力、速度和最大上下文由所选 `.litertlm` 文件、LiteRT-LM 和设备决定。

## 接口与主要能力

| 项目 | 本地实现 | 与 OpenAI 的差异或限制 |
|---|---|---|
| `GET /health` | 已有的无密钥运维探针 | 非 OpenAI 接口，不属于 `/v1` 兼容层 |
| `GET /v1/models` | 返回当前所选模型 | 单模型；仅返回标准 Model 字段，客户端上下文长度需自行配置 |
| `GET /v1/models/{id}` | 当前模型详情，未知 ID 返回 404 | 不查询云端模型库 |
| `POST /v1/chat/completions` | JSON、SSE、图片、函数工具、思考、用量 | 下面列出参数范围与剩余差异 |
| `POST /v1/responses` | 无状态文本、图片、函数工具、思考及 typed SSE | 必须重发历史，不支持存储、后台任务或 previous_response_id |
| 文本消息 | system、developer、user、assistant、tool | 合并开头多个 system/developer；中途 system 指令仍拒绝；允许相邻普通同角色消息 |
| 图片输入 | PNG/JPEG/WebP，Base64 或 HTTP(S) URL | 每请求最多 4 张；单张 4 MiB，合计 8 MiB；32MP，预处理最长边 2048 |
| 函数工具 | 定义、调用 ID、并行调用、结果回传、JSON/SSE | 工具由客户端执行，未连接 Gallery 内置 MCP/手机操作 |
| `tool_choice` | auto、none、required、指定函数 | required/指定函数使用指令与输出校验；模型不遵守时返回 422，不把文本当作成功调用 |
| `parallel_tool_calls` | 支持 | false 时多个调用会明确失败，不丢弃多余调用 |
| 工具 `strict:true` | 支持下面的 Schema 子集，输出前验证 | 不匹配时返回 422；不保证模型每次都能生成合格调用 |
| 思考模式 | 原生 ThinkingConfig，页面默认开启 | 模型需支持；不凭可缺失的能力标记提前拒绝 |
| 思考内容 | Responses 的标准 reasoning/reasoning_text | Chat Completions 不添加非标准 reasoning_content 字段，隐藏思考不混入 content |
| JSON 输出 | response_format/text.format 的 json_object、json_schema | 原生约束生成与结果校验；需设备运行时支持；与活动工具同时使用会返回 400 |
| `usage`（Chat） | 真实运行时 prefill/decode 计数 | 统计不可用时为 null；没有准确的缓存/思考 token 细分 |
| `usage`（Responses） | 标准 usage 为 null | JNI 缺少标准 Responses 必需的细分数据，不返回自定义聚合字段或假的 0 |
| 速度、耗时 | 由客户端计时和计算 | 没有自定义 timings/tokens_per_second 返回字段 |
| 错误、鉴权 | Bearer、标准 error 对象、HTTP 状态码、X-Request-Id | 原生内部路径不暴露；系统杀进程仍可能导致断连 |
| HTTP | UTF-8 JSON、Content-Length、chunked 请求、CORS/OPTIONS | 请求体 12 MiB；同一时刻一个推理，其余返回 429 |
| 音频、视频、图像生成 | 未接入 | 当前 API 不提供这些端点；模型卡上的能力不等于 API 已适配 |
| Embeddings、Files、Uploads、Batch、Assistants、Vector Stores、Realtime | 未实现 | 不能由当前选定的聊天模型服务替代 |
| 云端内置 web_search/computer/mcp/code_interpreter | 未实现 | 可声明同名普通函数，但必须由客户端真正执行 |
| 云端计费、账号、组织、限额、数据保留 | 不提供 | 本地服务不访问 OpenAI 账号和收费系统 |

## Chat Completions 参数

支持的标准字段：

- `model`、`messages`、`stream`、`temperature`（0—2）、`top_p`（0—1）。
- `max_tokens` 或 `max_completion_tokens`，两者只能提供一个非 null 值；范围 1—当前上下文预算。
- `stream_options: {"include_usage": true}`：普通块 usage 为 null；结束前发出 choices 为空的用量块，然后 `[DONE]`。若运行时没有计数，用量仍为 null。
- `stop`：一个或最多四个非空字符串（空数组表示不设置停止词），每个最多 256 字符；跨 native chunk 匹配，只处理最终文本，停止字符串不返回。不会把思考通道当作文本搜索。
- `seed`：有符号 32 位整数，传入原生采样器；不同硬件、后端和运行时不保证位级复现。
- `presence_penalty`、`frequency_penalty`：-2—2，传入原生重复惩罚接口。
- `logit_bias`：仅支持 token ID → -100（禁止）或 0（不调整）；其它偏置值因运行时不提供接口而返回 400。
- `n:1`、`logprobs:false`、`top_logprobs:0`、`store:false`，用于兼容客户端显式默认值。多候选、logprobs 和存储请求返回 400。旧式 functions/function_call 与 /v1/completions 未实现，请使用 tools 和 Chat Completions。
- `metadata`：最多 16 个字符串键值，键长 64、值长 512；仅在响应中回显，不持久化。
- `user`、`safety_identifier`：接受字符串注释，不传给模型，不用于云端审计、计费或安全分类。
- `service_tier:auto/default`：本地仅有 default 服务层级，响应返回 default；不承诺优先队列。
- 工具、结构化输出和思考扩展，见下文。

尚未实现的请求参数会返回 400，避免静默接受。不存在的端点返回 404。请求必须是严格 JSON；无效 UTF-8、额外 JSON 文档、Content-Length/Transfer-Encoding 冲突均拒绝。

`finish_reason` 返回 stop、tool_calls 或 length。length 根据原生 decode 计数达到输出上限推断；此版 JNI 没有独立的终止原因枚举。缺少计数时不伪造 length。停止词终止优先记为 stop；完整工具调用记为 tool_calls。

## 思考与输出预算

页面可设置默认开关和预算，默认开启、2048 thinking tokens。默认总输出在开启时为 `min(context, max(4096, 2 * configured_budget))`，关闭时为 512。思考和最终回答共享总输出上限。本地页面配置不是 API 字段。

Chat Completions 使用官方 `reasoning_effort`：none 关闭；low/medium/high 分别映射到 512/2048/8192 的预算，并受总输出上限约束、至多占一半。没有 enable_thinking/thinking_budget 自定义请求字段。

```json
{"reasoning_effort":"medium","max_completion_tokens":4096}
```

Responses 使用官方 `reasoning:{"effort":"medium"}`。实际产生的思考可通过标准 reasoning/reasoning_text 项及事件返回；不将原文伪装为 summary。Chat Completions 不返回自定义 reasoning_content，也不将思考混进最终 content。不能据此判断模型没有进行内部思考。总推理超时仍为五分钟。

## JSON Schema 范围

支持对象、数组、字符串、数字、整数、布尔、null 类型（包括 type 数组），properties、required、additionalProperties、items、enum、const、anyOf、allOf、oneOf、本地 `$ref`/`$defs`/definitions、长度/数量/数值上下界、multipleOf、uniqueItems。允许 title、description、default、examples 等注释；default 不会自动填充值。Schema 嵌套与验证深度上限 64。

不支持的约束（例如 pattern、format、patternProperties、if/then/else、外部 URL 引用）会返回 400；不会忽略约束后宣称严格兼容。根工具参数和 JSON response schema 需要 object 类型。

```json
{"response_format":{"type":"json_schema","json_schema":{"name":"answer","strict":true,"schema":{"type":"object","properties":{"answer":{"type":"integer"}},"required":["answer"],"additionalProperties":false}}}}
```

response_format 通过原生 ResponseFormat/LLGuidance 约束生成，完成后校验；严格工具参数在回传给客户端前校验。无效结果返回 422。流式响应可能已发送部分内容，失败会以 error 事件结束；消费者应等待完成标记后再把结果当作成功。达到输出长度上限时，JSON 可能不完整，会标记 length/incomplete。

## 无状态 Responses

```json
{"model":"模型ID","input":"你好","store":false,"reasoning":{"effort":"medium"},"stream":true}
```

- 支持 input 字符串或消息数组；消息 content 支持 input_text、output_text、input_image。
- 图片使用 `{"type":"input_image","image_url":"data:image/png;base64,..."}`。
- 支持 instructions、函数 tools（扁平 name/parameters）、tool_choice、parallel_tool_calls、max_output_tokens、temperature、top_p、metadata、reasoning.effort、text.format。
- 函数结果用 function_call_output，提供 call_id 和字符串 output。后续请求重发原始消息、整个 response.output 和工具结果。reasoning_text 会保留为模型历史。
- 返回 message/output_text、function_call、reasoning/reasoning_text 项；SSE 提供 created/in_progress、output_item、content_part、文本/思考/函数参数 delta 与 done、completed/incomplete/failed 事件及 sequence_number。Responses 不使用 Chat 的 `[DONE]`。
- store 默认 false；store:true、background:true、previous_response_id、自动截断、托管工具、加密推理、推理摘要和 include 扩展均明确拒绝。
- SDK Responses 的 usage 需要此 JNI 无法测得的缓存/推理细分，所以为 null；不添加 x_gallery_usage/timings 等扩展字段。需要聚合用量时使用 Chat Completions；速度由客户端计时并结合真实 usage 计算。

## 验证与来源

`tools/check_openai_contract.py` 用官方 OpenAI Python SDK 3.41.0 验证 HTTP 测试生成的 Chat Completion、Chat chunks、Responses 对象及 typed SSE 事件，并递归拒绝官方 SDK 类型之外的额外字段（SDK 默认会容许这些字段，因此额外加了这项检查）。测试样本属于当前运行输出，并登记为 Gradle task outputs，缓存恢复后也可检查。发布流水线执行同一校验。

这证明线协议与适配逻辑，不证明实际手机上的模型加载、思考、Schema 解码、图片理解、性能或工具选择质量。后者仍需安装 APK 实测。原生指标不可用时统计为 null；不是 0 tokens。

参考：

- https://github.com/openai/openai-python/blob/main/src/openai/types/chat/completion_create_params.py
- https://github.com/openai/openai-python/blob/main/src/openai/types/chat/chat_completion_chunk.py
- https://github.com/openai/openai-python/blob/main/src/openai/types/responses/response.py
- https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/java/com/google/ai/edge/litertlm/Benchmark.kt
