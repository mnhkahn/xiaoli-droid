package com.cyeam.xiaoli

import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.parser.Parser

/** Native Compose text for common replies. Tables/complex blocks retain Markwon. */
fun nativeMarkdown(text: String): AnnotatedString {
    val out = AnnotatedString.Builder()
    fun children(node: Node, visit: (Node) -> Unit) {
        var child = node.firstChild
        while (child != null) {
            visit(child)
            child = child.next
        }
    }
    fun visit(node: Node) {
        when (node) {
            is org.commonmark.node.Text -> out.append(node.literal)
            is SoftLineBreak -> out.append(" ")
            is HardLineBreak -> out.append("\n")
            is StrongEmphasis -> {
                out.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                children(node, ::visit)
                out.pop()
            }
            is Emphasis -> {
                out.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                children(node, ::visit)
                out.pop()
            }
            is Code -> {
                out.pushStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFF0EDE7),color=Color(0xFF262320))
                )
                out.append(node.literal)
                out.pop()
            }
            is Link -> {
                out.pushStringAnnotation("URL", node.destination)
                out.pushStyle(
                    SpanStyle(color = Color(0xFFDD452F), textDecoration = TextDecoration.Underline)
                )
                children(node, ::visit)
                out.pop()
                out.pop()
            }
            is Heading -> {
                out.pushStyle(
                    SpanStyle(fontSize = (28 - node.level * 2).sp, fontWeight = FontWeight.Bold)
                )
                children(node, ::visit)
                out.pop()
                out.append("\n\n")
            }
            is org.commonmark.node.Paragraph -> {
                children(node, ::visit)
                out.append(if (node.parent is ListItem) "\n" else "\n\n")
            }
            is ListItem -> {
                var depth = 0
                var ancestor = node.parent?.parent
                while (ancestor != null) {
                    if (ancestor is ListItem) depth++
                    ancestor = ancestor.parent
                }
                repeat(depth) { out.append("  ") }
                val parent = node.parent
                if (parent is OrderedList) {
                    var index = parent.startNumber
                    var previous = node.previous
                    while (previous != null) {
                        index++
                        previous = previous.previous
                    }
                    out.append("$index. ")
                } else out.append("• ")
                children(node, ::visit)
            }
            is ThematicBreak -> out.append("────────────\n\n")
            is IndentedCodeBlock -> {
                out.pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                out.append(node.literal)
                out.pop()
            }
            else -> children(node, ::visit)
        }
    }
    visit(Parser.builder().build().parse(text))
    return out.toAnnotatedString().let { it.subSequence(0, it.text.trimEnd().length) }
}

private object NativeMarkdownCache {
    val values =
        object : android.util.LruCache<String, AnnotatedString>(1_000_000) {
            override fun sizeOf(key: String, value: AnnotatedString) = key.length + value.length
        }
}

@Composable
fun NativeMarkdownText(text: String, modifier: Modifier = Modifier) {
    val renderedState=remember(text){mutableStateOf(NativeMarkdownCache.values.get(text)?:AnnotatedString(text))}
    LaunchedEffect(text){renderedState.value=withContext(Rendering.dispatcher){NativeMarkdownCache.values.get(text)?:nativeMarkdown(text).also{NativeMarkdownCache.values.put(text,it)}}}
    val rendered=renderedState.value
    val uriHandler = LocalUriHandler.current
    rendered?.let { value ->
        SelectionContainer {
            ClickableText(
                text = value,
                style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, lineHeight = 26.sp),
                modifier = modifier,
                onClick = { offset ->
                    value.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { link ->
                        if (
                            link.item.startsWith("https://") ||
                                link.item.startsWith("http://") ||
                                link.item.startsWith("mailto:")
                        )
                            runCatching { uriHandler.openUri(link.item) }
                    }
                },
            )
        }
    }
}
