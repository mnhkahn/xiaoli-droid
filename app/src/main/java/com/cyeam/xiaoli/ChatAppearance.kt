package com.cyeam.xiaoli

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

@Composable
internal fun chatColor(light: Long, dark: Long): Color =
    Color(if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) light else dark)

@Composable
private fun Avatar(profile: Profile?, fallback: String, own: Boolean) {
    val name = profile?.name?.ifBlank { fallback } ?: fallback
    val label = if (own) "我的头像" else "${name}的头像"
    val url = profile?.avatar
    val context = LocalContext.current
    val image = remember(context, url) {
        url?.let { ImageRequest.Builder(context).data(it).diskCachePolicy(CachePolicy.DISABLED).build() }
    }
    var loaded by remember(image) { mutableStateOf(false) }
    Box(Modifier.size(34.dp).clip(CircleShape)
        .background(if (own) chatColor(0xffdcecff, 0xff233e5a) else chatColor(0xffeee5ff, 0xff443454))
        .clearAndSetSemantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (!loaded) Text(name.take(Character.charCount(name.codePointAt(0))),
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = if (own) chatColor(0xff315a96, 0xffc1d9fc) else chatColor(0xff7044aa, 0xffe3c9ff))
        if (image != null) AsyncImage(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable
internal fun MessageBubble(sender: Sender, profile: Profile?, peerName: String, content: @Composable ColumnScope.() -> Unit) {
    val own = sender == Sender.USER
    val bot = sender == Sender.BOT
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = (maxWidth * 0.76f).coerceAtMost(540.dp)
        Row(Modifier.fillMaxWidth(),
            horizontalArrangement = when (sender) {
                Sender.USER -> Arrangement.End
                Sender.BOT -> Arrangement.Start
                Sender.OTHER -> Arrangement.Center
            }) {
            if (bot) {
                Avatar(profile, peerName, false)
                Spacer(Modifier.width(8.dp))
            }
            Surface(modifier = Modifier.widthIn(max = bubbleWidth),
                shape = RoundedCornerShape(topStart = if (bot) 4.dp else 14.dp,
                    topEnd = if (own) 4.dp else 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                color = if (own) chatColor(0xffd6e5ff, 0xff243e61) else chatColor(0xfff2f3f5, 0xff292c32),
                contentColor = chatColor(0xff20242c, 0xffedf0f5)) {
                Column(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), content = content)
            }
            if (own) {
                Spacer(Modifier.width(8.dp))
                Avatar(profile, "我", true)
            }
        }
    }
}
