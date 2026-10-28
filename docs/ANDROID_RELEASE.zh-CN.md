简体中文界面与 Android 本地 OpenAI 兼容 API 服务测试版。

下载附件 **gallery-zh-openai-api.apk** 后在手机上安装。需要 Android 12 及以上；这是使用调试签名的测试包，可能无法直接覆盖来自其他渠道、使用不同签名的应用。请先备份需要保留的聊天记录和模型文件。

- 简体中文界面跟随系统语言；Android 13 及以上也可在系统应用语言设置中选择简体中文。
- API 入口：设置 → OpenAI API 服务。先下载或导入 .litertlm 聊天模型，再启动服务。
- 支持 /v1/models、/v1/chat/completions、多轮文本消息、SSE 流式响应和 API Key 验证。
- 默认仅本机访问，可通过 USB 转发或启用局域网访问；仅在可信网络中使用 HTTP 服务。

构建时执行中文资源校验和 6 个 HTTP 协议测试。真机推理、GPU 支持和后台运行仍需设备实测。仓库原有 Lint 错误未在此测试版中修复。

完整说明见仓库 docs/OPENAI_API.zh-CN.md。附件提供 SHA-256 校验文件。
