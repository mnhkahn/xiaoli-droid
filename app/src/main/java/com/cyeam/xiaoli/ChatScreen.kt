package com.cyeam.xiaoli

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

internal fun approvalIntent(brand: Brand, approval: Approval): Intent {
    val page = Uri.parse(brand.verificationUrl(approval.url).toString())
    val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE)
    val host = "applink.${brand.domain}"
    val link = if (page.host == host) {
        page.buildUpon().scheme(brand.appScheme)
    } else {
        Uri.Builder().scheme(brand.appScheme).authority(host).path("/client/web_url/open")
            .appendQueryParameter("mode", "window").appendQueryParameter("url", page.toString())
    }
    return intent.setData(link.build()).setPackage(brand.clientPackage)
}

private fun openApproval(context: Context, brand: Brand, approval: Approval) {
    context.startActivity(approvalIntent(brand, approval))
}

@Composable
internal fun ChatScreen(model: ChatModel, clients: List<Brand>, onCheckUpdate: () -> Unit, launchApproval: (Context, Brand, Approval) -> Unit = ::openApproval) {
    val brand = model.brand
    val peer = model.selected
    val context = LocalContext.current
    var openOnReady by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pending = model.approval
    var launchError by remember(brand, pending) { mutableStateOf<String?>(null) }
    fun openCurrentApproval() {
        val ticket = model.approval ?: return
        val provider = model.brand ?: return
        if (ticket.until <= System.currentTimeMillis()) {
            if (model.signedIn) { model.notice("确认码已过期，请重新授权添加任务"); return }
            openOnReady = true
            model.renewLoginLink()
            return
        }
        openOnReady = false
        launchError = null
        val failure = "无法打开${provider.title}，请确认客户端已启用并更新到最新版本。"
        try { launchApproval(context, provider, ticket) }
        catch (_: ActivityNotFoundException) { launchError = failure }
        catch (_: SecurityException) { launchError = failure }
    }
    LaunchedEffect(openOnReady, pending, brand, clients, lifecycle) {
        if (brand != null && !model.signedIn && brand !in clients) {
            openOnReady = false
            model.home()
        } else if ((openOnReady || model.taskDraft != null) && pending != null && brand != null) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            openCurrentApproval()
        }
    }
    var confirmLogout by remember(brand) { mutableStateOf(false) }
    BackHandler(enabled = brand != null && (!model.signedIn || model.sending)) {
        if (!model.sending) model.home()
    }
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    Surface(modifier = Modifier.fillMaxSize(),
        color = if (peer == null) MaterialTheme.colorScheme.surface else chatColor(0xffffffff, 0xff191b20)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = if (peer == null) 20.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (peer == null) 8.dp else 0.dp)) {
            if (peer == null) Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (peer == null) {
                    Text(if (model.signedIn) "连接智能体" else "小李", style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f), maxLines = 2)
                } else {
                    IconButton(onClick = { back?.onBackPressed() }, enabled = !model.sending) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(peer.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        Surface(shape = RoundedCornerShape(5.dp), color = chatColor(0xffeee7ff, 0xff433256)) {
                            Text("智能体", Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                color = chatColor(0xff7446bb, 0xffdec3ff), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (brand != null && model.signedIn) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }, enabled = !model.sending) { Icon(Icons.Outlined.MoreHoriz, "更多") }
                        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                            if (peer != null) DropdownMenuItem(text = { Text("刷新快捷菜单") },
                                onClick = { expanded = false; model.refreshMenu() })
                            DropdownMenuItem(text = { Text("检查更新") }, onClick = { expanded = false; onCheckUpdate() })
                            if (clients.any { it != brand }) DropdownMenuItem(text = { Text("切换平台") },
                                onClick = { expanded = false; model.home() })
                            DropdownMenuItem(text = { Text("断开连接") }, onClick = { expanded = false; confirmLogout = true })
                        }
                    }
                } else if (brand != null && clients.any { it != brand }) {
                    TextButton(onClick = model::home) { Text("切换平台") }
                }
            }
            if (peer != null) HorizontalDivider(color = chatColor(0xffeceef1, 0xff373a41))
            if (model.taskDraft == null) model.error?.let { Text(it, Modifier.padding(horizontal = if (peer == null) 0.dp else 12.dp), color = MaterialTheme.colorScheme.error) }
            if (model.taskDraft == null) launchError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (brand == null) {
                Text(if (clients.isEmpty()) "请先安装或启用 Lark 或飞书，再返回小李登录。" else "在开放平台选中你的智能体应用，登录后直接进入和它的单聊。")
                for (entry in clients) Button(onClick = { openOnReady = true; model.enter(entry) }, modifier = Modifier.fillMaxWidth()) {
                    Text("使用${entry.title}登录")
                }
            } else if (!model.signedIn) {
                Text("${brand.title}登录", style = MaterialTheme.typography.titleLarge)
                val approval = model.approval
                if (approval != null) {
                    Text(if (approval.registration) "在开放平台选择你想聊天的智能体应用。确认后，回到小李授权账号。"
                        else "请确认授权小李读取你与这个智能体的单聊，并以你的身份发消息。")
                    if (approval.code.isNotEmpty()) SelectionContainer { Text("确认码  ${approval.code}") }
                    Button(onClick = ::openCurrentApproval) { Text("打开${brand.title}确认") }
                    TextButton(onClick = { openOnReady = true; model.renewLoginLink() }) { Text("获取新链接") }
                    Text("在${brand.title}中确认后，返回小李继续。")
                } else if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    Button(onClick = { openOnReady = true; model.login() }) { Text("继续登录") }
                    if (model.error != null) TextButton(onClick = { confirmLogout = true }) { Text("重置本机连接") }
                }
            } else if (peer == null) {
                if (model.busy) {
                    Text("正在${brand.title}查找你与所选智能体的单聊…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else Button(onClick = model::connect) { Text("重新连接") }
            } else key(brand, peer.id) { Conversation(model, peer.name) }
            if (!model.signedIn) TextButton(onClick = onCheckUpdate) { Text("检查更新") }
        }
    }
    model.taskDraft?.let { MessageTaskDialog(model, it, launchError, ::openCurrentApproval) }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(if (model.signedIn) "断开与智能体的连接？" else "重置本机连接？") },
        text = { Text("下次连接需要重新选择智能体应用并授权账号。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; model.disconnect() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable
private fun ColumnScope.Conversation(model: ChatModel, peerName: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.refreshMenu()
            val poll = model.startPolling()
            try { awaitCancellation() } finally { poll.cancel() }
        }
    }
    var preview by remember { mutableStateOf<Pair<java.io.File, String>?>(null) }
    preview?.let { (file, type) -> MediaPreview(file, type, model::notice) { preview = null } }
    var savingId by rememberSaveable { mutableStateOf<String?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val msg = model.messages.firstOrNull { it.id == savingId }
        if (uri != null && msg != null) model.save(msg, uri)
        savingId = null
    }
    model.historyError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var actionId by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetHeight by remember(actionId) { mutableIntStateOf(0) }
    var composerHeight by remember { mutableIntStateOf(0) }
    val selection = remember(actionId) { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == actionId } }
    val originalViewportHeight = remember(actionId) { listState.layoutInfo.viewportSize.height }
    LaunchedEffect(model, lifecycle, model.brand, model.selected) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                .distinctUntilChanged().collectLatest { model.observeReactions(it) }
        }
    }
    model.reactionError?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall) }
    val messagesById = remember(model.messages) { model.messages.associateBy { it.id } }
    val actionMessage = messagesById[actionId]
    if (actionMessage != null && !actionMessage.deleted) MessageActions(model, actionMessage, { sheetHeight = it }) { actionId = null }
    else if (actionId != null) LaunchedEffect(actionId) { actionId = null }
    model.topId?.let { id ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    try {
                        val index = model.locateTop(id)
                        if (index == null) model.notice("当前会话中找不到这条置顶消息") else listState.scrollToItem(index)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { model.notice(e.message ?: "无法定位消息") }
                }
            }) {
                Text("本机置顶 · ${messagesById[id]?.text ?: "查看原消息"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { model.setTop(null) }) { Text("取消置顶") }
        }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("chat-history"), state = listState, reverseLayout = true,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(model.messages, key = { it.id }) { msg ->
            MessageBubble(model, msg, peerName, onActions = { actionId = msg.id }) {
                fun openImage(resource: Resource) = model.preview(msg, resource) { preview = it to "image" }
                val parent = messagesById[msg.parentId]
                val quote = parent?.let {
                    val author = when (it.sender) {
                        Sender.USER -> model.userProfile?.name ?: "我"
                        Sender.BOT -> peerName
                        Sender.OTHER -> ""
                    }
                    listOf(author, it.text).filter(String::isNotBlank).joinToString("：")
                }
                val card = msg.type == "interactive" && msg.blocks.isNotEmpty()
                if (!card && !msg.deleted && msg.parentId != null) {
                    Text(quote ?: "回复消息", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                }
                if (card) {
                    MessageCard(msg.blocks, quote) { resource -> HistoryImage(model, msg, resource) { openImage(resource) } }
                } else if (msg.blocks.isNotEmpty()) {
                    for (block in msg.blocks) when (block) {
                        is MessageBlock.Paragraph -> Text(block.value, Modifier.padding(vertical = 2.dp),
                            fontWeight = if (block.heading) FontWeight.Bold else FontWeight.Normal)
                        is MessageBlock.Picture -> HistoryImage(model, msg, block.resource) { openImage(block.resource) }
                        else -> Unit
                    }
                } else if (msg.resource?.type == "image") {
                    HistoryImage(model, msg, msg.resource) { openImage(msg.resource) }
                } else Text(msg.text)
                val link = android.util.Patterns.WEB_URL.matcher(msg.text).let { if (it.find()) it.group() else null }
                if (link != null && link.startsWith("https://")) TextButton(onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                    catch (_: ActivityNotFoundException) { model.notice("没有可用的浏览器") }
                }) { Text("打开链接") }
                msg.resource?.let { resource ->
                    if (msg.type == "audio")
                        TextButton(onClick = { model.preview(msg, resource) { preview = it to msg.type } }, enabled = !model.busy) {
                            Text("播放语音")
                        }
                    TextButton(onClick = { savingId = msg.id; save.launch(resource.name.substringAfterLast('/').substringAfterLast('\\')) },
                        enabled = !model.busy) { Text("保存附件") }
                }
            }
        }
        if (model.older != null) item { TextButton(onClick = model::loadOlder, enabled = !model.busy) { Text("查看更早的消息") } }
    }
    // Lift a short message above the modal when it would be covered. Large cards keep
    // their reading position, including the header and the page the user long-pressed.
    if (actionMessage != null && selection != null && sheetHeight > 0) {
        val density = LocalDensity.current
        val overlap = sheetHeight + with(density) { 16.dp.roundToPx() } - composerHeight - selection.offset.coerceAtLeast(0)
        if (overlap > 0 && selection.size + sheetHeight < originalViewportHeight) {
            Spacer(Modifier.height(with(density) { overlap.toDp() }))
        }
    }
    Column(Modifier.onSizeChanged { composerHeight = it.height }) { MessageComposer(model, peerName) }
}
