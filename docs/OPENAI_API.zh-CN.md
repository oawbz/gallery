# 在 Android 设备上提供 OpenAI 兼容 API

应用支持简体中文：跟随系统语言；Android 13 及以上也可以在系统的应用语言设置中选择“简体中文”。品牌、模型名称、协议字段及模型输出不作自动翻译。

## 启动服务

1. 在 Gallery 中下载或导入一个 **LiteRT-LM 聊天模型（`.litertlm`）**。嵌入模型和 AICore 模型不适用。
2. 退出其他模型聊天，以免同时加载两个模型导致内存不足。
3. 打开 **设置 → OpenAI API 服务**，选择模型、端口和 CPU/GPU。
4. 保留自动生成的 API 密钥，或填写至少 16 位、无空格的 ASCII 密钥。点击“启动服务”，等待“服务正在运行”。模型首次加载可能较慢。
5. 在页面或常驻通知中停止服务。系统重启或终止进程后，需要手动重新启动；服务不会自动对外开放。

默认仅监听手机本机 `127.0.0.1`。通过 USB 使用时，在电脑执行：

```sh
adb forward tcp:8080 tcp:8080
```

如需同一局域网内访问，启动前勾选“允许局域网连接”，使用页面显示的手机地址。服务提供 HTTP，密钥和消息不加密传输，应使用可信网络或 USB 转发，不要直接映射到公网。Android 电池管理可能影响后台运行。

## 调用示例

先将页面中的密钥填入客户端环境变量 `GALLERY_API_KEY`。所有 `/v1` API 端点都要求 `Authorization: Bearer <密钥>`。

```sh
curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer $GALLERY_API_KEY"
```

从返回列表取得模型 `id`（所选模型文件名去掉 `.litertlm`），替换下方 `MODEL_ID`：

```sh
curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer $GALLERY_API_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"model":"MODEL_ID","messages":[{"role":"system","content":"请用中文回答。"},{"role":"user","content":"介绍一下你自己。"}],"stream":false,"max_tokens":256}'
```

流式调用将 `stream` 设为 `true`，并在 curl 中加入 `-N`。响应为 SSE，使用 `chat.completion.chunk`，最后发送 `data: [DONE]`。

Python OpenAI SDK 示例：

```python
import os
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:8080/v1",
    api_key=os.environ["GALLERY_API_KEY"],
)
model = client.models.list().data[0].id
for chunk in client.chat.completions.create(
    model=model,
    messages=[{"role": "user", "content": "你好，请用中文回答。"}],
    stream=True,
    max_tokens=256,
):
    print(chunk.choices[0].delta.content or "", end="", flush=True)
```

## 兼容范围

- `GET /v1/models`：当前所选的单个本地模型。
- `POST /v1/chat/completions`：文本内容，支持首条 `system`，以及交替出现的 `user` / `assistant` 消息，最后必须是 `user`。
- 支持 `stream`、`temperature`（0—2）、`top_p`（0—1），以及 `max_tokens` 或 `max_completion_tokens`（二选一，1—4096，默认 512）。引擎上下文预算为 4096 个词元，包含历史消息；生成仍受模型自身限制。
- 每个请求创建独立会话。多轮调用需重新发送完整消息历史，不会读取或修改 Gallery 的聊天记录。
- 同时只执行一个推理请求；繁忙时返回 429。单次推理超时为 5 分钟。请求体上限为 12 MiB，最多 256 条消息。
- 支持用户消息中的图片，以及由客户端执行的函数工具调用；需要模型本身支持。音频、嵌入、Responses API、`usage` 统计或 `stream_options` 等参数暂不支持；不支持的参数返回 400。
- 返回工具调用时 `finish_reason` 为 `tool_calls`，文本完成时为 `stop`；当前适配层仍不区分文本正常结束与输出词元限制。不返回未经测量的词元统计。

## 验证范围

云环境可验证 APK 构建与真实 HTTP 套接字上的协议测试，协议测试使用替身推理后端。模型加载、GPU 支持、前台通知和设备上的实际推理仍需安装 APK 后实测。

协议测试覆盖文本与工具 JSON/SSE 响应、工具调用和结果的关联、多模态消息转换，以及图片下载与大小限制。主机测试使用替身推理后端；Android 位图解码、实际模型图片理解、工具选择与执行往返仍需要真机实测。此前发现的原有 Android Lint 问题不属于本次验证范围。


## Android 17 / LineageOS 24 局域网连接

Android 17 上，启用“允许局域网连接”后启动服务时，需要授予“访问本地网络”权限；拒绝后不会启动局域网服务，可以点击“打开应用权限”修改。仅授予联网或通知权限不足以获得局域网访问权限。

运行后检查页面的“监听地址”：局域网模式应为 `0.0.0.0:端口`，仅本机模式为 `127.0.0.1:端口`。Wi-Fi 地址变化时点击“刷新网络地址”，不要把 VPN 网卡地址当作 Wi-Fi 地址。

在另一台设备打开 `http://手机Wi-Fi地址:端口/health`，正常应返回 `{"status":"ok"}`，这个健康检查不需要密钥，也不返回模型信息。`/v1/models` 仍需要密钥，返回 401 表示网络已连通。`/v1` 只是客户端配置使用的 base URL，不是模型列表接口。

若手机本机访问正常、其他设备仍不能访问，检查同一 Wi-Fi 下的客户端隔离/访客网络、VPN、LineageOS 的应用网络权限及防火墙。仅在手机本机测试并不能验证局域网入站连接。


设置与服务页面采用应用原有主题、顶部导航和 Material 3 卡片。连接信息旁的复制按钮可分别复制 Base URL、健康检查地址、模型 ID 和 API Key；密钥默认隐藏，显示/隐藏不影响复制完整值。API 服务运行时仍可复制地址和密钥，配置修改需要先停止服务。


启动模型列表已与 APK 版本后缀解耦，并内置离线回退列表；网络无法访问 GitHub 或本地缓存损坏时仍可进入应用。此回退只包含模型信息，不包含模型权重。固定签名的配置和旧包迁移限制见 `docs/ANDROID_SIGNING.zh-CN.md`。


## 图片输入（1.0.20-api3）

在用户消息的 `content` 数组中传入文本和图片，顺序会保留并传给原生视觉输入：

```json
{
  "model": "gemma-4-E4B-it",
  "messages": [{"role": "user", "content": [
    {"type": "text", "text": "请描述这张图片"},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,这里替换为图片Base64"}}
  ]}]
}
```

`url` 也可以是手机可访问的 HTTP(S) 地址，下载请求不会携带 API 密钥。支持直接指定 HTTP 地址，Android 已启用明文访问策略；请使用可信网络。HTTPS 跳转到 HTTP 会明确拒绝，HTTPS 证书校验保持启用。支持 PNG、JPEG、WebP；单张压缩文件不超过 4 MiB，每请求最多 4 张、合计不超过 8 MiB。原图不超过 3200 万像素，送入模型前等比采样至最长边不超过 2048 像素。`detail` 接受 `auto`、`low`、`high`，目前均使用相同预处理，实际视觉词元由模型决定。视觉后端按模型元数据选择 GPU 或 CPU，与文本后端分别配置。无视觉能力的模型返回 400。

## 工具调用（1.0.20-api3）

声明函数，模型可返回结构化调用：

```json
{
  "model": "gemma-4-E4B-it",
  "messages": [{"role": "user", "content": "北京天气如何？"}],
  "tools": [{"type": "function", "function": {
    "name": "get_weather",
    "description": "查询城市天气",
    "parameters": {"type": "object", "properties": {"city": {"type": "string"}}, "required": ["city"]}
  }}],
  "tool_choice": "auto"
}
```

响应的 `choices[0].message.tool_calls` 包含 `id`、`type: "function"` 与 `function.name`、`function.arguments`（JSON 字符串）。客户端执行工具后，把原始 assistant 消息和对应结果加到完整历史，再发送相同的工具定义：

```json
{"role":"tool","tool_call_id":"响应中的调用ID","content":"北京，晴，25℃"}
```

支持多个调用，结果通过 `tool_call_id` 匹配，允许结果顺序不同；必须回传全部结果，未知、重复或遗漏的 ID 会返回 400。SSE 以 `delta.tool_calls` 返回完整参数字符串和递增 `index`，以 `finish_reason: "tool_calls"` 及 `[DONE]` 结束。分析通道不会当作普通文本输出。

`tool_choice` 支持 `auto`、`none`、`required` 或 `{"type":"function","function":{"name":"get_weather"}}`。指定函数时仅向模型提供该函数；`required` 会增加调用指令并校验实际输出，模型未生成调用时返回 422，不会把普通文本当作成功的必选调用。`parallel_tool_calls: false` 时若模型仍返回多个调用，也返回 422。流式响应已开始时，生成失败通过 SSE `error` 事件和 `[DONE]` 返回。LiteRT-LM 未提供这些选项的硬约束生成接口，因此模型可能需要重试或调整提示。

工具参数使用对象 JSON Schema；支持最多 64 个不同名称的函数。`strict: true` 暂不支持，会明确返回 400；本接口不承诺严格 Schema 生成。工具由外部客户端执行；不使用 Gallery Agent Chat 的内置工具或 MCP 连接，也不在手机上自动执行客户端提供的函数。
