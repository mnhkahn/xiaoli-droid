package com.cyeam.xiaoli

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.platform.testTag
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

// Observe the message without intercepting taps on images, links or page controls.
private fun Modifier.messageLongPress(open: () -> Unit): Modifier = semantics {
    onLongClick(label = "消息操作") { open(); true }
}.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val timeout = viewConfiguration.longPressTimeoutMillis
        val held = withTimeoutOrNull(timeout) {
            var pointer: PointerInputChange
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                pointer = event.changes.singleOrNull() ?: return@withTimeoutOrNull false
                if (pointer.id != down.id || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop)
                    return@withTimeoutOrNull false
            } while (pointer.pressed)
            val longPress = pointer.uptimeMillis - down.uptimeMillis >= timeout
            if (longPress) pointer.consume()
            longPress
        }
        if (held != false) {
            open()
            // A child can own the initial tap (e.g. a truncated cell). Consume the release
            // in the Initial pass so its click cannot also open the cell after a long press.
            while (currentEvent.changes.any { it.pressed }) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
}

@Composable
internal fun MessageBubble(model: ChatModel, message: ImMessage, peerName: String, onActions: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val own = message.sender == Sender.USER
    val bot = message.sender == Sender.BOT
    val card = message.type == "interactive" && message.blocks.isNotEmpty()
    val profile = if (own) model.userProfile else model.botProfile
    val openActions by rememberUpdatedState(onActions)
    val feedback = LocalHapticFeedback.current
    val gesture = if (message.deleted) Modifier else Modifier.messageLongPress {
        feedback.performHapticFeedback(HapticFeedbackType.LongPress)
        openActions()
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleWidth = (maxWidth * (if (card) 0.82f else 0.76f)).coerceAtMost(540.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = when (message.sender) {
            Sender.USER -> Arrangement.End
            Sender.BOT -> Arrangement.Start
            Sender.OTHER -> Arrangement.Center
        }) {
            if (bot) {
                Avatar(profile, peerName, false)
                Spacer(Modifier.width(8.dp))
            }
            Surface(modifier = gesture.widthIn(max = bubbleWidth).testTag("bubble-${message.id}"), shape = if (card) RoundedCornerShape(10.dp) else RoundedCornerShape(
                topStart = if (bot) 4.dp else 14.dp, topEnd = if (own) 4.dp else 14.dp,
                bottomStart = 14.dp, bottomEnd = 14.dp),
                color = when {
                    card -> chatColor(0xffffffff, 0xff23262c)
                    own -> chatColor(0xffd6e5ff, 0xff243e61)
                    else -> chatColor(0xfff2f3f5, 0xff292c32)
                }, border = if (card) BorderStroke(1.dp, chatColor(0xffdfe1e4, 0xff494d55)) else null,
                contentColor = chatColor(0xff20242c, 0xffedf0f5)) {
                Column(if (card) Modifier.fillMaxWidth() else Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) {
                    content()
                    if (!message.deleted) MessageReactions(model, message,
                        if (card) Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp) else Modifier)
                }
            }
            if (own) {
                Spacer(Modifier.width(8.dp))
                Avatar(profile, "我", true)
            }
        }
    }
}
