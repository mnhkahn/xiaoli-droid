# 小李

支持飞书智能体和 Xiaoli Server 配对的安卓客户端。Android 10+，应用 ID `com.cyeam.xiaoli`。

- 文字、多图、拍照、分享、粘贴，以及 PDF、DOCX、XLSX、UTF-8 文档；实际解析附件内容。
- 会话搜索、置顶、归档、回收站、导出；编辑/重新回答保留原记录并建立上下文分支，草稿按会话保存。
- 「设置 → 模型」搜索、刷新 cyeam 免费模型，可设置当前会话或新对话默认；不影响微信/飞书默认模型。
- 流式回答、可展开思考和真实工具详情；Markdown、表格、代码复制、公式、图片预览/保存/分享。
- 服务端问题和授权卡片可操作，支持停止、断线恢复和请求去重。
- 自定义说明、当前配对设备的记忆管理、内存临时会话、服务端定时任务和可停止的后台通知连接。
- Ogg Opus ASR、中文 TTS、连续语音自动分句/发送/播报/继续，点击打断和语速偏好。
- 默认助手、快捷磁贴/桌面入口、位置填入草稿、深浅主题、系统字号和系统认证应用锁。
- GitHub Release 更新检查、下载/包名/签名校验和系统安装；main 验证、Tag 和手动 Actions 发布。

前两组功能统一交付，第三组高级能力取消。范围与边界见 [功能范围](docs/chatgpt-android-parity.md)。

## 飞书智能体

首次打开未配对的小李会进入飞书登录页。在飞书页面点「登录」，返回后选择智能体，也可手动点「选择智能体」。首次添加时填写名称，粘贴飞书 Web SDK 渠道的 `channelToken` 或包含它的配置代码。在「设置 → 飞书智能体」也可进入。已添加的列表保存在本机，重开应用会回到上次使用的智能体。

智能体管理员需要在「使用渠道 → Web SDK」选择飞书账号登录，把 `xiaoli-droid.cyeam.com` 加入域名白名单，并将使用者加入可用范围。该域名是 APK 内置页面的 HTTPS 来源，在 WebView 中由本地资源提供。登录、对话和会话历史由飞书提供，嵌入代码仅提取渠道标识。该模式不需要小李服务器、配对码或飞书应用密钥。Web SDK 的开通资格取决于飞书租户版本。参见[飞书 Web SDK 接入文档](https://aily.feishu.cn/hc/1u7kleqg_xrlhqg54)。

APK 内附由官方 `@lark-apaas/aily-web-sdk` 构建的脚本。更新 SDK 后在 `web/feishu` 运行 `npm ci && npm run build`，一并提交生成的资源。

## 连接

先部署包含 `/mobile/v1/` 的服务端。打开应用的「连接小李」，登录管理台，通过「添加学习平板」生成现有 Android 配对码，扫码或粘贴码。手机复用配对认证，不使用管理员密钥。

「设置 → 设为默认助手」由系统请求确认。系统能否把电源键、手势和锁屏入口交给第三方助手取决于 ROM；不提供小米专属硬件热词替换。后台不常驻监听麦克风。标准语音助手入口在已配对后打开录音，普通启动由用户点击录音。

普通安卓应用升级需要用户授权安装，无法静默替换自己。每次启动检查最新稳定 Release；也可在设置手动检查。main 执行验证，版本 Tag 或手动发布产生稳定版本。

## 构建与测试

JDK 17 或 21、Android SDK 35：

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
python3 -m unittest discover -s scripts -p 'test_*.py'
```

调试构建只对 `localhost`、`127.0.0.1`、`10.0.2.2` 开放 HTTP；正式版要求 HTTPS。

## 发布

仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`PGYER_API_KEY`。发布证书 alias 为 `xiaoli`，首次生成后必须保存，后续所有发布复用。

- 推送 main：跑单测、Lint 和模拟器 UI 测试。
- 推送 `v0.2.0` 或 `0.2.0` 形式的 Tag：同样验证后签名、上传蒲公英并发布稳定版，应用会发现更新。
- Actions 手动运行：填写 `major.minor.patch` 版本。

Release 包含 `xiaoli.apk`、SHA-256 和 `update.json`。versionCode 使用固定基数 1000001000 加当前工作流的 GITHUB_RUN_NUMBER，在 main、Tag 和手动运行间递增，为本机验证包保留低位编号，并避免同秒发布的版本冲突。每个 Release 只创建一次，重发请使用新版本。

本机签名材料存放在 `~/.config/xiaoli-droid/signing/`，不进入仓库。构建正式版：

```sh
export XIAOLI_KEYSTORE="$HOME/.config/xiaoli-droid/signing/xiaoli.jks"
export XIAOLI_STORE_PASSWORD="$(cat "$HOME/.config/xiaoli-droid/signing/password")"
./gradlew assembleRelease -PversionName=0.2.0 -PversionCode=YOUR_MONOTONIC_CODE
```

协议说明见 [mobile-protocol.md](docs/mobile-protocol.md)。验证结果见 [validation.md](docs/validation.md)。
