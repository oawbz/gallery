# 固定 Android 签名

之前发布的测试包由不同的临时 GitHub Actions 构建机生成调试密钥，因此签名会变化。版本号增加不能解决签名不一致；没有原私钥，无法生成可以覆盖原包的签名。

新版工作流只在配置固定签名后发布 APK。普通本地开发仍可构建调试包，但仓库缺少签名配置时会在发布前停止，避免继续分发签名不稳定的安装包。

## 一次性配置

请在你自己的电脑上操作，先安装 Java 21（包含 keytool）、Git 和 GitHub CLI，并用 `gh auth login` 登录有权管理 oawbz/gallery 仓库 Secrets 的账号。不要在聊天、Issue、日志或 Git 仓库里提交私钥和密码。

下载仓库 `tools/configure_android_signing.py` 后运行：

```sh
python configure_android_signing.py --repo oawbz/gallery
```

脚本会：

1. 在用户目录 `.local/share/gallery-android-signing` 创建并保留 PKCS12 签名文件和密码文件，别名为 `gallery`。
2. 将签名文件和密码通过标准输入安全写入仓库的 `ANDROID_SIGNING_KEYSTORE_BASE64`、`ANDROID_SIGNING_PASSWORD` 两个 Actions Secrets；不会打印其内容。
3. 等待已有运行结束，并重跑分支 `codex/android-zh-openai-api` 的最新构建。

请安全备份整个签名目录。后续构建复用相同 Secrets，便可保持相同证书；不要每次重新生成密钥。

若已有同名 Secrets，脚本默认拒绝覆盖。确需从备份恢复时，先恢复原签名目录，再加 `--overwrite-secrets`；这不是绕过旧签名的办法。也可加 `--create-only` 仅生成本地文件，或 `--no-rerun` 只配置 Secrets。

当前云执行环境的 GitHub API 访问被拒绝，无法代你写入这些仓库 Secrets，所以这一步需要在你的电脑上完成。无需把密钥或密码发给我。

## 已安装旧测试包的数据

固定新签名只能保证今后的更新，不会自动兼容旧的随机签名。请先保留现有安装；若必须迁移，先导出聊天记录并备份模型文件，再考虑卸载。卸载会删除应用数据及应用专属目录中的模型。仅备份 APK 文件并不能备份原私钥或应用数据。
