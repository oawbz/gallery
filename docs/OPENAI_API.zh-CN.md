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
- 同时只执行一个推理请求；繁忙时返回 429。单次推理超时为 5 分钟。请求体上限为 1 MiB，最多 256 条消息。
- 暂不支持图片、音频、工具调用、嵌入、Responses API、`usage` 统计或 `stream_options` 等额外参数；不支持的参数返回 400，避免静默忽略。
- 成功完成的响应统一返回 `finish_reason: "stop"`；当前适配层不区分运行时正常结束与输出词元限制。不返回未经测量的词元统计。

## 验证范围

云环境可验证 APK 构建与真实 HTTP 套接字上的协议测试，协议测试使用替身推理后端。模型加载、GPU 支持、前台通知和设备上的实际推理仍需安装 APK 后实测。

本次验证：Debug APK 构建及签名检查通过；8 个 HTTP 协议测试全部通过；954 项简体中文资源通过覆盖和格式占位符检查。Android Lint 报告 25 个错误，均位于相对 HEAD 未修改的原有文件中，涉及 Compose 的 Locale 读取和 Media3 实验 API 标注；本次没有屏蔽这些检查。


## Android 17 / LineageOS 24 局域网连接

Android 17 上，启用“允许局域网连接”后启动服务时，需要授予“访问本地网络”权限；拒绝后不会启动局域网服务，可以点击“打开应用权限”修改。仅授予联网或通知权限不足以获得局域网访问权限。

运行后检查页面的“监听地址”：局域网模式应为 `0.0.0.0:端口`，仅本机模式为 `127.0.0.1:端口`。Wi-Fi 地址变化时点击“刷新网络地址”，不要把 VPN 网卡地址当作 Wi-Fi 地址。

在另一台设备打开 `http://手机Wi-Fi地址:端口/health`，正常应返回 `{"status":"ok"}`，这个健康检查不需要密钥，也不返回模型信息。`/v1/models` 仍需要密钥，返回 401 表示网络已连通。`/v1` 只是客户端配置使用的 base URL，不是模型列表接口。

若手机本机访问正常、其他设备仍不能访问，检查同一 Wi-Fi 下的客户端隔离/访客网络、VPN、LineageOS 的应用网络权限及防火墙。仅在手机本机测试并不能验证局域网入站连接。


设置与服务页面采用应用原有主题、顶部导航和 Material 3 卡片。连接信息旁的复制按钮可分别复制 Base URL、健康检查地址、模型 ID 和 API Key；密钥默认隐藏，显示/隐藏不影响复制完整值。API 服务运行时仍可复制地址和密钥，配置修改需要先停止服务。


启动模型列表已与 APK 版本后缀解耦，并内置离线回退列表；网络无法访问 GitHub 或本地缓存损坏时仍可进入应用。此回退只包含模型信息，不包含模型权重。固定签名的配置和旧包迁移限制见 `docs/ANDROID_SIGNING.zh-CN.md`。
