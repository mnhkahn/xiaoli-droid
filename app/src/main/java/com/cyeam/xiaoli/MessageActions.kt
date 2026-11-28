package com.cyeam.xiaoli

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActions(model: ChatModel, message: ImMessage, onHeight: (Int) -> Unit, close: () -> Unit) {
    var more by rememberSaveable(message.id) { mutableStateOf(false) }
    val surface = chatColor(0xffffffff, 0xff30333a)
    val sheet = chatColor(0xfff2f3f5, 0xff212328)
    val shape = RoundedCornerShape(12.dp)
    val ink = chatColor(0xff252930, 0xffeceef2)
    fun react(type: String) { close(); model.toggleReaction(message.id, type) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheet, contentColor = ink, tonalElevation = 0.dp, scrimColor = Color.Black.copy(alpha = .13f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), dragHandle = {
            Box(Modifier.padding(top = 9.dp, bottom = 4.dp).size(40.dp, 4.dp).background(chatColor(0xffdfe1e5, 0xff535862), CircleShape))
        }) {
        Column(Modifier.fillMaxWidth().onSizeChanged { onHeight(it.height) }.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .testTag("message-actions")) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                for (emoji in quickReactions) {
                    IconButton(onClick = { react(emoji.type) }, modifier = Modifier.weight(1f), enabled = !model.changingReaction) {
                        EmojiArtwork(emoji, 33.dp)
                    }
                }
                IconButton(onClick = { more = !more }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.MoreHoriz, if (more) "收起表情" else "更多表情",
                        Modifier.size(30.dp).background(chatColor(0xffe6e8eb, 0xff393d44), CircleShape).padding(4.dp))
                }
            }
            if (more) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (emoji in moreReactions) {
                    IconButton(onClick = { react(emoji.type) }, enabled = !model.changingReaction) { EmojiArtwork(emoji, 31.dp) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile("回复", Icons.AutoMirrored.Outlined.Reply, Modifier.weight(1f), surface) {
                    close(); model.setReply(message.id)
                }
                ActionTile("添加任务", Icons.Outlined.TaskAlt, Modifier.weight(1f), surface, enabled = !model.busy) {
                    close(); model.startTask(message.id)
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(shape).background(surface)) {
                PinAction(model, message.id, surface, close)
                HorizontalDivider(Modifier.padding(start = 54.dp), color = chatColor(0xffe4e5e8, 0xff42464e))
                val top = model.topId == message.id
                ListItem(headlineContent = { Text(if (top) "取消置顶" else "置顶消息") },
                    trailingContent = { Text("本机", style = MaterialTheme.typography.labelMedium) },
                    leadingContent = { Icon(Icons.Outlined.VerticalAlignTop, null) },
                    modifier = Modifier.clickable { model.setTop(if (top) null else message.id); close() },
                    colors = ListItemDefaults.colors(containerColor = surface))
            }
        }
    }
}

@Composable
private fun PinAction(model: ChatModel, id: String, surface: Color, close: () -> Unit) {
    var pinned by remember(id) { mutableStateOf<Boolean?>(null) }
    var pinError by remember(id) { mutableStateOf<String?>(null) }
    var retry by remember(id) { mutableIntStateOf(0) }
    DisposableEffect(model, id, retry) {
        pinError = null
        val request = model.loadPin(id) { result ->
            result.onSuccess { pinned = it }.onFailure { pinError = it.message ?: "读取失败" }
        }
        onDispose { request.cancel() }
    }
    val pinLabel = when {
        pinError != null -> "重试读取 Pin"
        pinned == null -> "正在读取 Pin…"
        pinned == true -> "移除 Pin"
        else -> "Pin"
    }
    ListItem(headlineContent = { Text(pinLabel) }, leadingContent = { Icon(Icons.Outlined.PushPin, null) },
        supportingContent = if (pinError != null) ({ Text(pinError.orEmpty(), maxLines = 2) }) else null,
        modifier = Modifier.clickable(enabled = !model.busy && (pinned != null || pinError != null)) {
            if (pinError != null) retry++ else pinned?.let { close(); model.setPin(id, !it) }
        }, colors = ListItemDefaults.colors(containerColor = surface))
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, color: Color, enabled: Boolean = true, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp), color = color) {
        Column(Modifier.heightIn(min = 76.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(23.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun MessageTaskDialog(model: ChatModel, draft: TaskDraft, launchError: String?, openApproval: () -> Unit) {
    AlertDialog(onDismissRequest = model::cancelTask, title = { Text("添加到我的任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = draft.title, onValueChange = model::editTask, enabled = !model.busy,
                    label = { Text("任务名称") }, maxLines = 3)
                (launchError ?: model.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                model.approval?.let { if (it.code.isNotBlank()) Text("确认码  ${it.code}") }
            }
        },
        confirmButton = {
            when {
                model.approval != null -> TextButton(onClick = openApproval) { Text("打开${model.brand?.title.orEmpty()}确认") }
                draft.needsAuthorization -> TextButton(onClick = model::authorizeTasks, enabled = !model.busy) { Text("授权添加任务") }
                else -> TextButton(onClick = model::createTask, enabled = !model.busy && draft.title.isNotBlank()) { Text("创建任务") }
            }
        },
        dismissButton = { TextButton(onClick = model::cancelTask, enabled = !model.busy || draft.needsAuthorization) { Text("取消") } })
}
