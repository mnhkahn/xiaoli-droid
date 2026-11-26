package com.cyeam.xiaoli

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun FeishuScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val vault = remember { FeishuChannelStore(context) }
    // Most recently selected channel is first. A single encrypted write keeps selection and list consistent.
    var channels by remember { mutableStateOf(vault.read()) }
    var choosing by rememberSaveable { mutableStateOf(channels.isEmpty()) }
    var signingIn by rememberSaveable { mutableStateOf(channels.isEmpty()) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<FeishuPanel?>(null) }
    val links = LocalUriHandler.current
    fun save(updated: List<FeishuChannel>): Boolean = runCatching {
        vault.save(updated)
        channels = updated
    }.onFailure { error = "无法保存智能体，请检查设备存储后重试。" }.isSuccess
    fun select(item: FeishuChannel) {
        if (save(listOf(item) + channels.filter { it.token != item.token })) choosing = false
    }
    BackHandler {
        when {
            panel?.back() == true -> Unit
            signingIn -> signingIn = false
            choosing && channels.isNotEmpty() -> choosing = false
            else -> onExit()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (signingIn) "飞书登录" else if (choosing) "飞书智能体" else channels.first().name,
                    Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.titleMedium)
                if (signingIn) {
                    TextButton(onClick = { signingIn = false }) { Text("选择智能体") }
                } else if (!choosing) {
                    TextButton(onClick = { reload++ }) { Text("重新加载") }
                    TextButton(onClick = { choosing = true }) { Text("切换") }
                }
            }
            if (error.isNotEmpty()) Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
            if (signingIn) {
                key("sign-in") {
                    AndroidView(factory = { FeishuPanel(it, null) { signingIn = false }.also { view -> panel = view } },
                        modifier = Modifier.fillMaxSize(), onRelease = { view ->
                            if (panel === view) panel = null
                            view.close()
                        })
                }
            } else if (choosing || channels.isEmpty()) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(channels, key = { it.token }) { item ->
                        OutlinedButton(onClick = { select(item) }, modifier = Modifier.fillMaxWidth()) { Text(item.name) }
                    }
                    item {
                        Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("添加已有智能体") }
                    }
                    item {
                        Text("在飞书智能体的「使用渠道 → Web SDK」开启飞书账号登录，并把 $FEISHU_PANEL_HOST 加入域名白名单。复制渠道配置中的 channelToken 即可添加。",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    item { TextButton(onClick = { links.openUri("https://aily.feishu.cn/") }) { Text("去飞书创建或配置智能体") } }
                    item { TextButton(onClick = { signingIn = true }) { Text("登录飞书账号") } }
                    item { TextButton(onClick = { confirmLogout = true }, enabled = !loggingOut) { Text("清除本机飞书登录状态") } }
                }
            } else {
                key(channels.first().token, reload) {
                    AndroidView(factory = { FeishuPanel(it, channels.first()).also { view -> panel = view } },
                        modifier = Modifier.fillMaxSize(), onRelease = { view ->
                            if (panel === view) panel = null
                            view.close()
                        })
                }
            }
        }
    }
    if (adding) {
        var name by remember { mutableStateOf("") }
        var input by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("添加飞书智能体") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it.take(80) }, label = { Text("显示名称") }, singleLine = true)
                    OutlinedTextField(input, { input = it.take(16000); invalid = false },
                        label = { Text("channelToken 或 Web SDK 配置代码") }, maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = invalid)
                    if (invalid) Text("请从 Web SDK 渠道复制 channelToken。", color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = {
                TextButton(enabled = name.isNotBlank() && input.isNotBlank(), onClick = {
                    val token = channelFromInput(input)
                    if (token == null) invalid = true else {
                        select(FeishuChannel(name.trim(), token))
                        adding = false
                    }
                }) { Text("打开会话") }
            }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("清除飞书登录状态？") },
        text = { Text("下次打开会话时需要重新登录飞书。") },
        confirmButton = { TextButton(onClick = {
            confirmLogout = false
            loggingOut = true
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                loggingOut = false
            }
        }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
