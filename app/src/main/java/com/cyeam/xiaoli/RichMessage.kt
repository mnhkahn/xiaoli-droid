package com.cyeam.xiaoli

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
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
private fun cardTint(template: String, foreground: Boolean = false): Color = when (template.substringBefore('-')) {
    "green" -> if (foreground) chatColor(0xff237840, 0xff8cdc9f) else chatColor(0xffecf7ef, 0xff263c2d)
    "orange", "yellow" -> if (foreground) chatColor(0xff945500, 0xffffc375) else chatColor(0xfffff4e5, 0xff413529)
    "red", "carmine" -> if (foreground) chatColor(0xffbf3543, 0xffff9ba3) else chatColor(0xffffeff0, 0xff462c32)
    "purple" -> if (foreground) chatColor(0xff6745b4, 0xffcab1ff) else chatColor(0xfff3efff, 0xff392f49)
    "grey", "gray", "neutral", "default" -> if (foreground) LocalContentColor.current else chatColor(0xfff3f4f5, 0xff31343b)
    else -> if (foreground) chatColor(0xff245bdb, 0xff98b7ff) else chatColor(0xfff1f3ff, 0xff29354b)
}

@Composable
internal fun MessageCard(blocks: List<MessageBlock>, quote: String?, image: @Composable (Resource) -> Unit) {
    val header = blocks.firstOrNull() as? MessageBlock.CardHeader
    if (header != null || quote != null) {
        val tone = header?.template.orEmpty()
        val pale = cardTint(tone)
        val gradient = Brush.linearGradient(listOf(lerp(pale, cardTint(tone, true), 0.07f), pale))
        Column(Modifier.fillMaxWidth().background(gradient).padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (quote != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(2.dp, 13.dp).background(chatColor(0xff737985, 0xffabb1bd), RoundedCornerShape(1.dp)))
                Spacer(Modifier.width(4.dp))
                Text(quote, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = chatColor(0xff656c78, 0xffc1c7d1), fontSize = 14.sp, letterSpacing = 0.sp, lineHeight = 19.sp)
            }
            if (header != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (header.icon == "notice_colorful")
                        Icon(Icons.Outlined.Campaign, null, Modifier.size(18.dp), tint = cardTint(tone, true))
                    Text(header.title, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold,
                        color = cardTint(tone, true))
                }
                if (header.subtitle.isNotBlank()) Text(header.subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    val body = if (header == null) blocks else blocks.drop(1)
    if (body.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (block in body) when (block) {
            is MessageBlock.Paragraph -> {
                val tint = block.background?.takeUnless { it in setOf("none", "transparent", "default") }
                val background = if (tint == null) Modifier else Modifier.fillMaxWidth()
                    .background(cardTint(tint), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 5.dp)
                Text(block.value, background, fontWeight = if (block.heading) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 15.sp, letterSpacing = 0.sp, lineHeight = 22.sp)
            }
            is MessageBlock.Picture -> image(block.resource)
            is MessageBlock.Table -> CardTable(block)
            is MessageBlock.CardHeader -> Unit
        }
    }
}

@Composable
internal fun CardTable(table: MessageBlock.Table) {
    val pages = if (table.rows.isEmpty()) 1 else (table.rows.size - 1) / table.pageSize + 1
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val page = selected.coerceIn(0, pages - 1)
    val line = chatColor(0xffdfe1e4, 0xff494d55)
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth().padding(end = 6.dp).clip(shape).border(1.dp, line, shape)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = if (table.headers.size <= 2) maxWidth else maxOf(maxWidth, (table.headers.size * 90).dp)
            val scroll = if (width > maxWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier
            Column(scroll) {
                Column(Modifier.width(width)) {
                    val rows = listOf(table.headers) + table.rows.drop(page * table.pageSize).take(table.pageSize)
                    rows.forEachIndexed { index, row ->
                        Row(Modifier.fillMaxWidth().drawWithContent {
                            drawContent()
                            for (col in 1 until table.headers.size) {
                                val x = size.width * col / table.headers.size
                                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                            }
                        }, verticalAlignment = Alignment.CenterVertically) {
                            row.forEach { cell -> TableCell(cell, index == 0, Modifier.weight(1f)) }
                        }
                        if (index < rows.lastIndex) HorizontalDivider(color = line)
                    }
                }
            }
        }
        if (pages > 1) {
            HorizontalDivider(color = line)
            Row(Modifier.fillMaxWidth().padding(end = 4.dp).heightIn(min = 44.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                Text("${page + 1}/$pages", fontSize = 14.sp)
                Spacer(Modifier.width(5.dp))
                IconButton(onClick = { selected = page - 1 }, enabled = page > 0, modifier = Modifier.width(36.dp).height(44.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowUp, "上一页", Modifier.size(20.dp),
                        tint = if (page > 0) chatColor(0xff646a73, 0xffc9cdd4) else chatColor(0xffbfc1c6, 0xff555960))
                }
                IconButton(onClick = { selected = page + 1 }, enabled = page + 1 < pages, modifier = Modifier.width(36.dp).height(44.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "下一页", Modifier.size(20.dp),
                        tint = if (page + 1 < pages) chatColor(0xff646a73, 0xffc9cdd4) else chatColor(0xffbfc1c6, 0xff555960))
                }
            }
        }
    }
}

@Composable
private fun TableCell(value: String, header: Boolean, modifier: Modifier) {
    var clipped by remember(value) { mutableStateOf(false) }
    var expanded by remember(value) { mutableStateOf(false) }
    val read = if (clipped) Modifier.clickable(onClickLabel = "查看完整内容") { expanded = true } else Modifier
    Box(modifier.then(read).heightIn(min = 38.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, letterSpacing = 0.sp, lineHeight = 21.sp,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            onTextLayout = { clipped = it.hasVisualOverflow })
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, text = { SelectionContainer { Text(value) } },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessageReactions(model: ChatModel, msg: ImMessage, modifier: Modifier = Modifier) {
    val entries = model.reactions[msg.id].orEmpty()
    if (entries.isEmpty()) return
    val ownMessage = msg.sender == Sender.USER
    val tint = when {
        ownMessage -> chatColor(0xffbdd2fb, 0xff324e76)
        else -> chatColor(0xffe3e6ec, 0xff39404a)
    }
    val ink = chatColor(0xff505d72, 0xffd4deed)
    FlowRow(modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { entry ->
            val emoji = ChatEmoji.byType[entry.type]
            val byline = model.reactionByline(entry)
            val label = emoji?.label ?: entry.type
            val selected = entry.ownId != null
            val shape = RoundedCornerShape(50)
            Row(Modifier.clip(shape).background(tint)
                .then(if (selected) Modifier.border(1.dp, chatColor(0xff7499dc, 0xff81a8e8), shape) else Modifier)
                .toggleable(value = selected, enabled = !model.changingReaction, role = Role.Checkbox) {
                    model.toggleReaction(msg.id, entry.type)
                }.semantics { contentDescription = "$label，$byline" }
                .heightIn(min = 24.dp).padding(horizontal = 7.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (emoji != null) EmojiArtwork(emoji, 20.dp, description = null)
                else Text(entry.type, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                Box(Modifier.size(1.dp, 12.dp).background(ink.copy(alpha = 0.23f)))
                Text(byline, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp,
                    lineHeight = 16.sp, letterSpacing = 0.sp)
            }
        }
    }
}
