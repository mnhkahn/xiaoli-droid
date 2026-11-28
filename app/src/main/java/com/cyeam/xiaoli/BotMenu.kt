package com.cyeam.xiaoli

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal sealed interface BotMenuItem {
    val id: String
    val label: String
    data class Command(override val id: String, override val label: String) : BotMenuItem
    data class Group(override val id: String, override val label: String, val commands: List<Command>) : BotMenuItem
}

internal fun decodeBotMenu(bot: JSONObject?): List<BotMenuItem> {
    if (bot == null || !bot.optBoolean("bot_menu_enable")) return emptyList()
    val items = bot.optJSONArray("bot_menus") ?: return emptyList()
    fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)
    val nodes = (0 until items.length()).mapNotNull(items::optJSONObject)
        .filter { it.text("menu_id").isNotBlank() }
        .sortedBy { it.optInt("sort") }
        .groupBy { it.text("parent_menu_id") }
    fun label(node: JSONObject) = node.optJSONObject("i18n_name")?.text("zh_cn").orEmpty().ifBlank { node.text("default_name") }
    fun command(node: JSONObject): BotMenuItem.Command? {
        if (node.optInt("menu_content_type") != 4) return null
        return label(node).takeIf(String::isNotBlank)?.let { BotMenuItem.Command(node.getString("menu_id"), it) }
    }
    return nodes[""].orEmpty().mapNotNull { node ->
        if (node.optInt("menu_content_type") != 3) return@mapNotNull command(node)
        val id = node.getString("menu_id")
        val title = label(node)
        val commands = nodes[id].orEmpty().mapNotNull(::command)
        if (title.isBlank() || commands.isEmpty()) null else BotMenuItem.Group(id, title, commands)
    }
}

@Composable
internal fun CommandBar(items: List<BotMenuItem>, enabled: Boolean, send: (BotMenuItem.Command) -> Unit) {
    if (items.isEmpty()) return
    var expanded by remember(items, enabled) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (item in items) key(item.id) {
            Box {
                OutlinedButton(onClick = {
                    when (item) {
                        is BotMenuItem.Group -> expanded = item.id
                        is BotMenuItem.Command -> send(item)
                    }
                }, enabled = enabled, shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                    border = BorderStroke(1.dp, chatColor(0xffdfe1e5, 0xff42454c)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(item.label, Modifier.widthIn(max = 220.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    if (item is BotMenuItem.Group) Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
                if (item is BotMenuItem.Group) DropdownMenu(expanded == item.id, onDismissRequest = { expanded = null }) {
                    for (command in item.commands) DropdownMenuItem(text = { Text(command.label) }, enabled = enabled,
                        onClick = { expanded = null; send(command) })
                }
            }
        }
    }
}
