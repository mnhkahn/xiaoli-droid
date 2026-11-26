# 小李

Android 10 及以上的飞书智能体客户端。应用 ID 为 `com.cyeam.xiaoli`。首次打开进入飞书登录页。选定智能体后，后续启动直接打开该会话。登录和聊天由飞书官方 Web SDK 提供。

## 配置智能体

1. 在飞书智能体开发后台打开「档案 → 使用渠道 → Web SDK」。
2. 在「鉴权与访问控制」选择飞书账号、设置可用人员，把 `xiaoli-droid.cyeam.com` 加入域名白名单。这个域名是 APK 内置页面的 HTTPS 来源，页面由手机本地提供。
3. 在小李点「选择智能体 → 添加已有智能体」，填写名称并粘贴 `channelToken` 或包含它的渠道配置代码。
4. 点「打开会话」。已添加的渠道仅保存在本机，可在「切换」中选择其他智能体。

飞书要求租户为 AI 企业版及以上。[飞书官方配置说明](https://aily.feishu.cn/hc/1u7kleqg_xrlhqg54)

覆盖安装后，应用从全新的飞书登录状态开始，并清理旧客户端的本机配对信息、选项、草稿和附件。此前的小李对话不迁入飞书。当前飞书账号可见的会话由飞书管理。

APK 内附官方 `@lark-apaas/aily-web-sdk` 脚本。更新 SDK 后在 `web/feishu` 运行 `npm ci && npm run build`，一并提交生成的资源。

## 构建与测试

JDK 17 或 21、Android SDK 35：

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
python3 -m unittest discover -s scripts -p 'test_*.py'
```

## 发布

仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`PGYER_API_KEY`。发布证书 alias 为 `xiaoli`，首次生成后必须保存，后续所有发布复用。

- 推送 main：跑单测、Lint 和模拟器 UI 测试。
- 推送 `v0.2.0` 或 `0.2.0` 形式的 Tag：同样验证后签名、上传蒲公英并发布稳定版。
- Actions 手动运行：填写 `major.minor.patch` 版本。

Release 包含 `xiaoli.apk`、SHA-256 和 `update.json`。versionCode 使用固定基数 1000001000 加当前工作流的 GITHUB_RUN_NUMBER，在 main、Tag 和手动运行间递增，为本机验证包保留低位编号，并避免同秒发布的版本冲突。每个 Release 只创建一次，重发请使用新版本。

本机签名材料存放在 `~/.config/xiaoli-droid/signing/`，不进入仓库。构建正式版：

```sh
export XIAOLI_KEYSTORE="$HOME/.config/xiaoli-droid/signing/xiaoli.jks"
export XIAOLI_STORE_PASSWORD="$(cat "$HOME/.config/xiaoli-droid/signing/password")"
./gradlew assembleRelease -PversionName=0.2.0 -PversionCode=YOUR_MONOTONIC_CODE
```
