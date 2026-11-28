package com.cyeam.xiaoli

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
internal fun HistoryImage(model: ChatModel, msg: ImMessage, resource: Resource, open: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var bitmap by remember(msg.id, resource) { mutableStateOf<Bitmap?>(null) }
    var error by remember(msg.id, resource) { mutableStateOf<String?>(null) }
    LaunchedEffect(model, msg.id, resource, attempt) {
        error = null
        try { bitmap = model.thumbnail(msg, resource) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "图片加载失败" }
    }
    val image = bitmap
    if (image != null) {
        Image(image.asImageBitmap(), "查看图片", Modifier.testTag("image-${msg.id}-${resource.key}").widthIn(max = 248.dp).heightIn(max = 230.dp)
            .aspectRatio(image.width.toFloat() / image.height).clip(RoundedCornerShape(8.dp)).clickable(onClick = open))
    } else if (error != null) {
        TextButton(onClick = { attempt++ }) { Text("重新加载图片 · ${error.orEmpty()}") }
    } else {
        Box(Modifier.size(120.dp, 68.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp)) }
    }
}

@Composable
internal fun CardTable(table: MessageBlock.Table) {
    val pages = if (table.rows.isEmpty()) 1 else (table.rows.size - 1) / table.pageSize + 1
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val page = selected.coerceIn(0, pages - 1)
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.padding(vertical = 4.dp).border(1.dp, border, RoundedCornerShape(8.dp)).padding(1.dp)) {
        BoxWithConstraints {
            val width = maxOf(maxWidth, (table.headers.size * 90).dp)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                (listOf(table.headers) + table.rows.drop(page * table.pageSize).take(table.pageSize)).forEachIndexed { rowIndex, row ->
                    Row(Modifier.width(width)) {
                        row.forEach { cell -> SelectionContainer(Modifier.weight(1f)) {
                            Text(cell, Modifier.padding(6.dp), style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal)
                        } }
                    }
                    HorizontalDivider(color = border)
                }
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = page - 1 }, enabled = page > 0) { Text("上一页") }
            Text("${page + 1}/$pages", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { selected = page + 1 }, enabled = page + 1 < pages) { Text("下一页") }
        }
    }
}

private val reactionLabels = mapOf("Typing" to "⌨️ 输入中", "OnIt" to "🫡 收到", "THINKING" to "🤔 思考",
    "OneSecond" to "☝️ 稍等", "THUMBSUP" to "👍", "OK" to "👌", "HEART" to "❤️", "SMILE" to "🙂", "CLAP" to "👏")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageReactions(model: ChatModel, msg: ImMessage) {
    var picker by remember(msg.id) { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        model.reactions[msg.id].orEmpty().forEach { entry ->
            FilterChip(selected = entry.ownId != null, enabled = !model.changingReaction,
                onClick = { model.toggleReaction(msg.id, entry.type) },
                label = { Text("${reactionLabels[entry.type] ?: entry.type} ${entry.count}") })
        }
        Box {
            IconButton(onClick = { picker = true }, enabled = !model.changingReaction, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.AddReaction, "回复表情", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(picker, onDismissRequest = { picker = false }) {
                listOf("THUMBSUP", "OK", "HEART", "SMILE", "CLAP", "THINKING").forEach { type ->
                    DropdownMenuItem(text = { Text(reactionLabels.getValue(type)) },
                        onClick = { picker = false; model.toggleReaction(msg.id, type) })
                }
            }
        }
    }
}
