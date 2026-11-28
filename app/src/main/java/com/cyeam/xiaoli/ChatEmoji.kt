package com.cyeam.xiaoli

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Platform reaction identifiers are case-sensitive. Artwork is bundled for offline use.
internal enum class ChatEmoji(val type: String, val label: String, @DrawableRes val icon: Int) {
    OK("OK", "OK", R.drawable.reaction_ok),
    FACEPALM("FACEPALM", "捂脸", R.drawable.reaction_facepalm),
    LAUGH("LOL", "笑哭", R.drawable.reaction_lol),
    GET("Get", "Get", R.drawable.reaction_get),
    GLANCE("GLANCE", "斜眼笑", R.drawable.reaction_glance),
    TEARS("CRY", "泪目", R.drawable.reaction_cry),
    LIKE("THUMBSUP", "赞", R.drawable.reaction_thumbsup),
    HEART("HEART", "爱心", R.drawable.reaction_heart),
    SMILE("SMILE", "微笑", R.drawable.reaction_smile),
    CLAP("CLAP", "鼓掌", R.drawable.reaction_clap),
    THINK("THINKING", "思考", R.drawable.reaction_thinking),
    TYPING("Typing", "输入中", R.drawable.reaction_typing),
    ON_IT("OnIt", "收到", R.drawable.reaction_onit),
    WAIT("OneSecond", "稍等", R.drawable.reaction_wait),
    GRIN("BLUSH", "咧嘴笑", R.drawable.reaction_grin),
    PARTY("PARTY", "庆祝", R.drawable.reaction_party),
    THANKS("THANKS", "感谢", R.drawable.reaction_thanks);

    companion object {
        val byType = entries.associateBy { it.type }
    }
}

internal val quickReactions = listOf(ChatEmoji.OK, ChatEmoji.FACEPALM, ChatEmoji.LAUGH, ChatEmoji.GET, ChatEmoji.GLANCE, ChatEmoji.TEARS)
internal val moreReactions = listOf(ChatEmoji.LIKE, ChatEmoji.HEART, ChatEmoji.SMILE, ChatEmoji.CLAP, ChatEmoji.THINK)
internal val composerEmoji = listOf("😀" to ChatEmoji.GRIN, "👍" to ChatEmoji.LIKE, "❤️" to ChatEmoji.HEART,
    "🎉" to ChatEmoji.PARTY, "🙏" to ChatEmoji.THANKS, "👏" to ChatEmoji.CLAP)

@Composable
internal fun EmojiArtwork(emoji: ChatEmoji, size: Dp, description: String? = emoji.label) {
    Box(Modifier.size(size)) {
        Image(painterResource(emoji.icon), description, Modifier.fillMaxSize())
        if (emoji == ChatEmoji.GET) {
            val fontSize = with(LocalDensity.current) { (size * 0.27f).toSp() }
            Text("GET", Modifier.align(Alignment.BottomCenter).clearAndSetSemantics {}
                .background(Color(0xff285a85), RoundedCornerShape(2.dp)).padding(horizontal = 2.dp),
                style = TextStyle(color = Color.White, fontSize = fontSize, lineHeight = fontSize,
                    fontWeight = FontWeight.Black, platformStyle = PlatformTextStyle(includeFontPadding = false)))
        }
    }
}
