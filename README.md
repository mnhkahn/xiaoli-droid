# 小李

Android 10 及以上的飞书和 Lark 单聊客户端。应用 ID 为 `com.cyeam.xiaoli`。小李从手机直连对应平台的开放 API，账号授权、会话列表和消息均由所选平台提供。

## 连接已有智能体

1. 首页会检测当前 Android 用户已安装并启用的客户端。只装了 Lark 或飞书时，点唯一的登录按钮即可。两者都装了时，选择与智能体已有单聊的那个平台。若都未安装，先安装任意一个，再返回小李。
2. 小李直接打开对应客户端。首次连接时，按平台提示确认创建小李专用的开放平台应用。两个平台都从飞书账号服务申请注册确认码。选择 Lark 时，确认页在 Lark 内打开，平台识别出 Lark 租户后，小李向 Lark 领取应用凭证。你的智能体继续在原来的会话里回复。[官方注册流程](https://github.com/larksuite/cli/blob/main/internal/auth/app_registration.go)
3. 返回小李，再点确认按钮授权账号访问单聊。若平台提示链接已失效，可返回小李点「获取新链接」。授权完成后，选择现有单聊。租户可能需要管理员批准应用创建或消息权限。
4. 小李只读取并发送到选中的会话，打开会话期间每五秒刷新一次。重启会恢复该平台的选择，通过「切换会话」可以重新选择或返回另一平台。

两个平台的应用凭证、用户令牌和已选会话分别用 Android Keystore 加密保存在本机。覆盖安装后会清除旧 Aily 渠道配置和 WebView 数据。连接现有单聊无需渠道 Token 或域名白名单。

申请的用户权限包括 `im:chat:read`（列出单聊）、`im:message:readonly` 和 `im:message.p2p_msg:get_as_user`（读取单聊）、`im:message` 和 `im:message.send_as_user`（以用户身份发送）、`im:resource`（上传附件）及 `offline_access`（续期）。开放平台按账号授予权限，小李在客户端将消息请求限定到所选会话。[官方会话列表实现](https://github.com/larksuite/cli/blob/main/shortcuts/im/im_chat_list.go) · [官方设备授权实现](https://github.com/larksuite/cli/blob/main/internal/auth/device_flow.go)

## 图片、文件、文档、语音和位置

- 图片、普通文件及本地文档从 Android 文件选择器选择。收到的图片可预览，附件可保存到系统文件选择器指定的位置。云文档可作为链接分享，打开时沿用文档自身的访问权限。
- 录音使用 Ogg Opus，通过平台语音消息发送并在小李内播放。已有的 WAV、MP3 等也可以作为文件发送。
- 位置通过「添加附件 → 当前位置」插入坐标和地图链接到草稿，点「发送」后才发出。收到的位置消息也可以打开地图链接。发出的是文本和链接。
- 富文本和卡片回复提取可读文字及链接，卡片内的交互按钮暂未接入。

## 构建与测试

JDK 17 或 21、Android SDK 35：

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
python3 -m unittest discover -s scripts -p 'test_*.py'
```

Android 16 模拟器已验证从小李打开 Lark 官方登录页，并通过网页的「Login by Lark」唤起 Lark 8.7.10。设备测试使用本地 HTTPS 测试服务核对授权链接刷新、单聊收发、图片和文件字节、录音及坐标。真实租户批准授权后与已有智能体对话，仍待账号实测。

## 发布

正式包启动时检查 GitHub Release 的 `update.json`，也可以在首页或会话选择页点「检查更新」。发现新版本后点「下载更新」，小李在后台下载 APK，核对 SHA-256、包名、版本号和签名。下载完成后点「安装更新」，按 Android 系统提示确认覆盖安装。首次安装可能需要允许小李安装应用。已发布的 0.0.8 和 0.0.9 仍会打开蒲公英，需通过旧入口覆盖安装一次带应用内更新的版本。0.0.7 及更早版本可从蒲公英手动覆盖安装。[蒲公英下载页](https://www.pgyer.com/xiaoli-android-2)

仓库 Secrets：`RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`PGYER_API_KEY`。发布证书 alias 为 `xiaoli`，首次生成后必须保存，后续所有发布复用。

- 推送 main 或创建拉取请求会运行 `Android CI`，执行单测、Lint 和模拟器 UI 测试。
- CI 遇到 KVM 不可用、模拟器无法启动或 Android 桌面无响应且恢复失败时，会跳过设备测试并在运行摘要中标明原因。编译、单测、Lint 及已开始运行的设备测试失败都会阻止发布。
- 推送 `v0.2.0` 或 `0.2.0` 形式的标签会运行 `Android Release`，通过同一套验证后签名、上传蒲公英并发布 GitHub Release。
- 手动运行 `Android Release` 时填写 `major.minor.patch` 版本。

Release 包含 `xiaoli.apk`、SHA-256 和 `update.json`。versionCode 使用固定基数 1000001000 加发布工作流的 GITHUB_RUN_NUMBER，随版本标签和手动发布运行递增，为本机验证包保留低位编号，并避免同秒发布的版本冲突。每个 Release 只创建一次，重发请使用新版本。

本机签名材料存放在 `~/.config/xiaoli-droid/signing/`，不进入仓库。构建正式版：

```sh
export XIAOLI_KEYSTORE="$HOME/.config/xiaoli-droid/signing/xiaoli.jks"
export XIAOLI_STORE_PASSWORD="$(cat "$HOME/.config/xiaoli-droid/signing/password")"
./gradlew assembleRelease -PversionName=0.2.0 -PversionCode=YOUR_MONOTONIC_CODE
```
