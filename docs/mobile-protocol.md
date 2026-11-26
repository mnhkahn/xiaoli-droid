# Mobile protocol v1

所有 `/mobile/v1/` 请求需要 `Device-Id` 和 `Authorization: Bearer <paired-device-token>`。先通过现有 `/xiaozhi/pair` 一次性配对，设备 token 仅以哈希存储在服务端，手机使用 Android Keystore AES-GCM 加密保存。配对由 Logto 管理员发起，不向手机分发管理密钥。

| Method | Path | 内容 |
| --- | --- | --- |
| GET | /mobile/v1/models | cyeam 免费模型目录与服务端已配置模型的交集；仅返回 ID、名称和平台 |
| GET | /mobile/v1/capabilities | 协议版本与 ASR 能力 |
| GET / POST | /mobile/v1/sessions | 列表 / 创建独立 android 会话 |
| PATCH | /mobile/v1/sessions/:id | JSON：model（空字符串恢复服务端默认），仅修改当前会话 |
| GET | /mobile/v1/sessions/:id?before=N | 最近 50 条消息，before 返回下一页游标 |
| POST | /mobile/v1/sessions/:id/messages | multipart：text、request_id、images（0–4 张） |
| POST | /mobile/v1/sessions/:id/reply | JSON：interaction_id、text、request_id |
| POST | /mobile/v1/sessions/:id/cancel | 取消执行；空闲时放弃未处理的交互 |
| GET | /mobile/v1/sessions/:id/events | SSE，after / Last-Event-ID |
| GET | /mobile/v1/media/:id | 仅可访问当前设备会话中的上传图片 |
| POST | /mobile/v1/transcribe | multipart：audio，Ogg Opus |

单张图片 ≤3 MiB，最多四张；服务端实际检测格式和尺寸（8192 边长、2500 万像素以内），客户端按最长边压缩到 2048。未指定会话模型时，图文内容通过现有 Vision → Agent 链路处理；指定模型时，图片作为原始多模态输入直接交给该模型，不先经过其他视觉模型。最近图片关联到手机会话。会话模型不修改微信、飞书的全局默认模型。

SSE `data` 为 `{id,type,data}`。`snapshot` 是权威快照（messages、pending、busy、before、revision），`delta` 包含 message_id、field（text / thinking）、delta，`progress` 提供工具/重试状态。每次连接先给快照，再推后续事件；无需通过重新提交消息来恢复。快照会修正流式输出与最终回答的差异。客户端 40ms 批量归并事件，Markdown 在后台解析；滚动查看历史时不会强制跳到底部。

request_id 在每个会话中持久化去重。只允许每个会话同时执行一个任务。手机 ID 必须与会话、媒体归属匹配。权限回复只能选择服务端下发的选项，不能伪造授权；过期 bash 授权必须重发任务。多个确认必须全部处理后才能继续执行。服务重启后的在途任务标为 interrupted，不自动重放操作。

会话和消息保存在服务端 DataDir/mobile，文件权限 0600；最多每设备 50 个未删除会话，每会话 500 条消息；消息达到上限后新建对话，会话数量达到上限后先移入回收站。上传图片保存在相同私有目录；本地生成的结果图片复制进会话私有媒体目录，重连后仍可访问；外部图片与既有 artifact 链接遵循各自有效期。

微信、飞书继续使用原有路由和 Formatter。共享 Agent 的 Thinking / ResponseInstructions 都是可选参数，只由手机通道启用。

模型目录来自 `https://www.cyeam.com/v1/models`（与 `/ai/models` 同源），服务端缓存 5 分钟，网络失败可沿用最近缓存。不返回密钥或调用地址；未配置平台和专用语音/Embedding 等非聊天模型不进入手机选择器。目录按服务端启动时已加载的模型配置筛选，新配置在重启服务后生效。

## 0.2 扩展（保持协议 v1 兼容）

| Method | Path / 字段 | 内容 |
| --- | --- | --- |
| GET | sessions?q=文字&view=archived/deleted | 标题/消息搜索，归档/回收站；默认只列普通会话，置顶优先 |
| POST | sessions `{temporary:true}` | 内存临时会话，当前设备新临时会话替换遗留临时会话 |
| PATCH | sessions/:id | 可选 title、pinned、archived、deleted、model；空 model 恢复默认 |
| POST | sessions/:id/fork | message_id、request_id、可选 text；返回新分支和 draft，不自动执行 |
| GET | sessions/:id/export | Markdown 文本，不包含附件访问凭证 |
| POST | sessions/:id/discard | 关闭临时会话，取消执行并释放私有临时媒体 |
| POST | messages: files / reuse_files / reuse_images / search | 文档、多模态草稿引用、显式联网请求；引用只能来自该会话的分支草稿 |
| GET | files/:id?preview=text | 当前设备拥有的原文件 / 提取文字 |
| GET / PATCH | profile | instructions、memory_enabled、default_model；仅当前设备 |
| GET / PUT / DELETE | memories | 当前 Android 设备记忆；更新/删除使用 key，更新附 value |
| GET / POST | tasks | 查看/创建任务：session_id、title、prompt、next、timezone、repeat、paused |
| PATCH / DELETE | tasks/:id | 编辑、暂停、删除；重复方式 none/daily/weekly |
| GET | activity | 当前设备会话状态快照 SSE；心跳与重连，不携带原始工具参数 |

消息增加 files 和 tools 字段；tool SSE 事件包含 message_id 与该消息的工具状态、耗时、成功检索来源。来源仅从成功工具输出提取。客户端忽略未知字段/事件，旧渠道不依赖这些扩展。

文档最多 4 个，每个 8 MiB、最多 64000 字提取文本。PDF 使用运行环境的 pdftotext；空文字扫描件明确拒绝并提示改发图片。DOCX/XLSX 采用有界 ZIP/XML 解析，文本要求 UTF-8。附件作为带边界的数据交给手机模型，不赋予附件中的指令更高权限。生成的文档只能从当前会话的 xiaoli-artifacts 目录复制为私有附件。

手机输入规划按数据编码，允许明确只分析输入的只读任务直接作答；外部操作仍保留工具与授权要求。Android 记忆与其他设备及全局渠道记忆隔离。关闭记忆时同时关闭加载与记忆工具。

临时会话不调用普通 Agent 会话、长期记忆、检查点或工具；用户对话和媒体留在有界内存，普通会话保存函数跳过临时内容。文档解析所需的私有文件随解析结束删除。空闲一小时回收，显式关闭或重新创建立即释放。模型提供方的数据保留规则不受此协议控制。

调度运行在服务端。待执行请求先持久化，受理后用同一 request_id 确认；重启不能自动重放已受理的工具操作。通知连接为用户主动开启的 Android 前台服务，带系统停止入口；任务暂停/删除影响后续调度，当前运行仍可在会话中停止。
