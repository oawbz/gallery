简体中文与本地 OpenAI 兼容 API 测试版，版本 1.0.20-api5（versionCode 51）。

本版新增默认开启的思考模式（默认预算 2048 tokens），通过标准 Responses reasoning_text 返回思考内容，Chat Completions 不添加非标准字段；默认总输出提高为 4096 tokens。可在服务页面关闭或修改预算，客户端可通过标准 reasoning_effort 覆盖。

补齐真实聚合 usage、流式用量块、停止词、采样种子、存在/频率惩罚、JSON/schema 输出、严格工具参数验证、developer 消息、模型详情、chunked HTTP 请求，以及无状态 /v1/responses 和 typed SSE。

沿用 api4 的工具元数据误判修复，以及默认 32K、最高可选 256K 的上下文配置。沿用既有 GitHub Actions Secrets 签名，可覆盖安装之前相同签名的测试包；请安全保留原签名备份。

公开 /v1 不添加 context_length、timings、reasoning_content 等自定义返回字段；速度由客户端计算。完整 OpenAI 差异、参数范围及尚未实现的能力见 docs/OPENAI_API_COMPATIBILITY.zh-CN.md。统计不可用时为 null，不伪造缓存或思考 token 数。模型实际能力、LLGuidance、GPU 和性能仍需真机测试；云端测试使用替身后端并用官方 SDK 校验线协议。

安装附件 gallery-zh-openai-api.apk，需要 Android 12 及以上。安装后停止并重启 API 服务，先保留默认 32K 进行功能验证。
