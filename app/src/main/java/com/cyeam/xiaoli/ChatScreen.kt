package com.cyeam.xiaoli

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation

internal fun openApproval(context: Context, brand: Brand, approval: Approval, browser: Boolean) {
    val verified = brand.verificationUrl(approval.url)
    if (!browser) {
        val link = Uri.Builder().scheme("https").authority("applink.${brand.domain}")
            .path("/client/web_url/open").appendQueryParameter("url", verified.toString()).build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, link).setPackage(brand.clientPackage))
            return
        } catch (_: ActivityNotFoundException) { /* A browser can also finish device authorization. */ }
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(verified.toString())).addCategory(Intent.CATEGORY_BROWSABLE))
}

@Composable
internal fun ChatScreen(model: ChatModel, launchApproval: (Context, Brand, Approval, Boolean) -> Unit = ::openApproval) {
    val brand = model.brand
    val peer = model.selected
    val context = LocalContext.current
    var openOnReady by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val pending = model.approval
    LaunchedEffect(openOnReady, pending, brand) {
        if (openOnReady && pending != null && brand != null) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                openOnReady = false
                try { launchApproval(context, brand, pending, false) }
                catch (_: ActivityNotFoundException) { model.notice("找不到可打开授权页的浏览器") }
            }
        }
    }
    var update by rememberSaveable { mutableIntStateOf(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler(enabled = brand != null) {
        if (!model.sending) { if (peer != null) model.select(null) else model.home() }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(peer?.name ?: if (model.signedIn) "选择单聊" else "小李", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f), maxLines = 2)
                if (brand != null) {
                    TextButton(onClick = { if (peer != null) model.select(null) else model.home() },
                        enabled = !model.sending) { Text(if (peer != null) "切换会话" else "切换平台") }
                }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (brand == null) {
                Text("用已有账号登录，再选择要联系的智能体。")
                for (entry in Brand.entries) Button(onClick = { openOnReady = true; model.enter(entry) }, modifier = Modifier.fillMaxWidth()) {
                    Text("使用${entry.title}登录")
                }
            } else if (!model.signedIn) {
                Text("${brand.title}登录", style = MaterialTheme.typography.titleLarge)
                val approval = model.approval
                if (approval != null) {
                    Text(if (approval.registration) "首次连接，请在开放平台确认创建小李专用应用。完成后还需授权账号访问单聊。"
                        else "请确认授权小李读取单聊，并以你的身份向选中的会话发消息。")
                    if (approval.code.isNotEmpty()) SelectionContainer { Text("确认码  ${approval.code}") }
                    fun open(browser: Boolean) {
                        try { launchApproval(context, brand, approval, browser) }
                        catch (_: ActivityNotFoundException) { model.notice("找不到可打开授权页的浏览器") }
                    }
                    Button(onClick = { open(false) }) { Text("前往${brand.title}确认") }
                    TextButton(onClick = { open(true) }) { Text("在浏览器中打开") }
                    Text("确认后返回小李即可继续。")
                } else if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    Button(onClick = { openOnReady = true; model.login() }) { Text("继续登录") }
                    if (model.error != null) TextButton(onClick = { confirmLogout = true }) { Text("重置本机连接") }
                }
            } else if (peer == null) {
                Text("${brand.title} · 选择已有的智能体单聊")
                Row {
                    TextButton(onClick = model::listPeers, enabled = !model.busy) { Text("刷新列表") }
                    TextButton(onClick = { confirmLogout = true }) { Text("退出账号") }
                }
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (model.peers.isEmpty() && model.error == null) Text("没有可见的单聊。先在${brand.title}和智能体发一条消息，再刷新。")
                var query by rememberSaveable(brand) { mutableStateOf("") }
                if (model.peers.size > 1) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("搜索会话名称") })
                LazyColumn(Modifier.weight(1f)) {
                    items(model.peers.filter { it.name.contains(query.trim(), ignoreCase = true) }, key = { it.id }) { chat ->
                        OutlinedButton(onClick = { model.select(chat) }, modifier = Modifier.fillMaxWidth()) { Text(chat.name) }
                    }
                }
            } else key(brand, peer.id) { Conversation(model) }
            if (peer == null) TextButton(onClick = { update++ }) { Text("检查更新") }
        }
    }
    AppUpdateDialog(update)
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false },
        title = { Text(if (model.signedIn) "退出${brand?.title}账号？" else "重置本机连接？") },
        text = { Text(if (model.signedIn) "下次登录需要重新授权和选择会话。" else "下次登录将重新创建专用应用。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout(resetApp = !model.signedIn) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}

@Composable
private fun ColumnScope.Conversation(model: ChatModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(model.messages, key = { it.id }) { msg ->
            Surface(shape = MaterialTheme.shapes.medium, color = if (msg.bot) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(10.dp)) {
                    SelectionContainer { Text(msg.text) }
                    val link = android.util.Patterns.WEB_URL.matcher(msg.text).let { if (it.find()) it.group() else null }
                    if (link != null && link.startsWith("https://")) TextButton(onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                        catch (_: ActivityNotFoundException) { model.notice("没有可用的浏览器") }
                    }) { Text("打开链接") }
                    msg.resource?.let { resource ->
                        if (msg.type in setOf("image", "audio"))
                            TextButton(onClick = { model.preview(msg) { preview = it to msg.type } }, enabled = !model.busy) {
                                Text(if (msg.type == "image") "查看图片" else "播放语音")
                            }
                        TextButton(onClick = { savingId = msg.id; save.launch(resource.name.substringAfterLast('/').substringAfterLast('\\')) },
                            enabled = !model.busy) { Text("保存附件") }
                    }
                }
            }
        }
        if (model.older != null) item { TextButton(onClick = model::loadOlder, enabled = !model.busy) { Text("查看更早的消息") } }
    }
    AttachmentActions(model)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(model.draft, model::edit, Modifier.weight(1f), placeholder = { Text("消息") }, maxLines = 4)
        Button(onClick = model::sendText, enabled = !model.sending && model.draft.isNotBlank()) { Text("发送") }
    }
    Spacer(Modifier.height(4.dp))
}
