# 小肥鱼桌宠（DeepSeekPet / 大肥鱼桌宠 手机版）

桌面 / 手机端 Q 版桌宠，联动 DeepSeek Harness（DSH）桌面端，实现碎碎念、对话、余额、工作状态等能力。

## 开源说明
- `web/` 为**生成目录**：由 `tools/web/build-web.mjs` 从外部 DSH 运行时（`dsh-pet` 插件）复制并重排生成，含第三方 DSH 代码，**不纳入本仓库**。
- 如需重新生成 `web/`，请先安装 DSH 桌面端，再运行 `node tools/web/build-web.mjs`。
- 签名密钥与密码（`android/release.keystore`、`android/gradle.properties`）**不入库**；请复制 `android/gradle.properties.example` 为 `gradle.properties` 并填入你自己的签名信息。
- 仓库内**不含**任何 DeepSeek API Key；密钥由本地 DSH 桌面端持有，手机端通过局域网 / adb 连接 DSH 使用。

## 结构
- `android/`：手机端 Android 工程（Kotlin）。
- `tools/`：构建脚本（`build-web.mjs`）与 Wi-Fi 代理（`harness-wifi-proxy.js`）等工具。
- `recon/merged-config.json`：桌宠配置基准，`web/config.json` 由它生成。
- `SPEC.md`：设计规格。

## 构建
1. `android/local.properties` 配置 `sdk.dir`（不入库）。
2. 复制 `android/gradle.properties.example` 为 `android/gradle.properties` 并填入签名信息（不入库）。
3. `android/gradlew assembleRelease` 生成 APK（APK 建议通过 GitHub Releases 分发，不入库）。
