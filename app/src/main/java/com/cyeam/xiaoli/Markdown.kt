package com.cyeam.xiaoli

import android.content.Intent
import android.net.Uri
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object MarkdownCache {
    val spans =
        object : android.util.LruCache<String, Spanned>(1_000_000) {
            override fun sizeOf(key: String, value: Spanned) = key.length + value.length
        }
}

sealed interface MarkdownPart {
    data class Text(val value: String) : MarkdownPart

    data class Image(val alt: String, val url: String) : MarkdownPart
}

fun markdownParts(markdown: String): List<MarkdownPart> {
    val result = mutableListOf<MarkdownPart>()
    var start = 0
    val regex = Regex("!\\[([^\\]]*)]\\(<?([^\\s)>]+)>?(?:\\s+\"[^\"]*\")?\\)")
    val codeRegions =
        Regex("(?s)```.*?(?:```|$)|~~~.*?(?:~~~|$)|`[^`\\n]*`")
            .findAll(markdown)
            .map { it.range }
            .toList()
    for (match in regex.findAll(markdown)) {
        if (codeRegions.any { match.range.first in it }) continue
        if (match.range.first > start)
            result += MarkdownPart.Text(markdown.substring(start, match.range.first))
        result += MarkdownPart.Image(match.groupValues[1], match.groupValues[2])
        start = match.range.last + 1
    }
    if (start < markdown.length) result += MarkdownPart.Text(markdown.substring(start))
    return result
}

private object MarkdownEngines {
    private val engines=linkedMapOf<String,Markwon>()
    @Synchronized fun get(context:android.content.Context,foreground:Color):Markwon {
        val key="${foreground.toArgb()}/${context.resources.configuration.fontScale}"
        engines[key]?.let{return it}
        return Markwon.builder(context.applicationContext)
            .usePlugin(TablePlugin.create(context.applicationContext))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(16f*context.resources.displayMetrics.scaledDensity){builder->builder.inlinesEnabled(true).executorService(Rendering.formulas);builder.theme().textColor(foreground.toArgb())})
            .usePlugin(
                object : AbstractMarkwonPlugin() {
                    override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                        builder.linkResolver { view, link ->
                            val uri = Uri.parse(link)
                            if (uri.scheme in listOf("https", "http", "mailto"))
                                runCatching {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                        }
                    }
                }
            )
            .build().also{engines[key]=it;if(engines.size>4)engines.remove(engines.keys.first())}
    }
}

@Composable
fun MarkdownBody(text: String, api: Api, onImage: (String) -> Unit) {
    val context = LocalContext.current
    val foreground=MaterialTheme.colorScheme.onSurface
    val engineState=remember(foreground){mutableStateOf<Markwon?>(null)}
    val partsState=remember(text){mutableStateOf<List<MarkdownPart>>(emptyList())}
    LaunchedEffect(text){partsState.value=withContext(Rendering.dispatcher){markdownParts(normalizeMathMarkdown(text))}}
    val parts=partsState.value
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        parts.forEach { part ->
            when (part) {
                is MarkdownPart.Text -> {
                    if (
                        !part.value.contains('|') &&
                            !part.value.contains("```") &&
                            !part.value.contains("~~") && !part.value.contains("$$") &&
                            !Regex("(?m)^\\s*>").containsMatchIn(part.value)
                    ) {
                        NativeMarkdownText(part.value, Modifier.fillMaxWidth())
                    } else {
                        val cacheKey=foreground.toArgb().toString()+"/"+context.resources.configuration.fontScale+"/"+part.value
                        val spannedState=remember(cacheKey){mutableStateOf<Spanned?>(MarkdownCache.spans.get(cacheKey))}
                        LaunchedEffect(cacheKey){
                            val rendered=withContext(Rendering.dispatcher){val engine=MarkdownEngines.get(context.applicationContext,foreground);engine to (MarkdownCache.spans.get(cacheKey)?:engine.toMarkdown(part.value).also{MarkdownCache.spans.put(cacheKey,it)})}
                            engineState.value=rendered.first;spannedState.value=rendered.second
                        }
                        val spanned=spannedState.value
                        AndroidView(
                            onReset = { view ->
                                view.text = ""
                                view.tag = null
                            },
                            factory = {
                                TextView(it).apply {
                                    textSize = 16f
                                    setTextColor(foreground.toArgb())
                                    setLineSpacing(4f, 1.1f)
                                    setTextIsSelectable(true)
                                    movementMethod = LinkMovementMethod.getInstance()
                                }
                            },
                            update = { view ->
                                view.setTextColor(foreground.toArgb())
                                val markwon=engineState.value
                                if (view.tag != cacheKey && spanned != null && markwon!=null) {
                                    markwon.setParsedMarkdown(view, spanned!!)
                                    view.tag = cacheKey
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    val codeBlocks=remember(part.value){Regex("```[^\\n]*\\n([\\s\\S]*?)```").findAll(part.value).map{it.groupValues[1].trimEnd()}.toList()}
                    codeBlocks.forEachIndexed{index,code->TextButton(onClick={context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("代码",code))}){Text(if(codeBlocks.size==1)"复制代码" else "复制代码 ${index+1}")}}
                }
                is MarkdownPart.Image -> ChatPicture(part.url, api, onImage, part.alt)
            }
        }
    }
}

@Composable
fun ChatPicture(url: String, api: Api, onImage: (String) -> Unit, alt: String = "图片") {
    val context = LocalContext.current
    val request = remember(url, api) { runCatching { api.imageRequest(url) }.getOrNull() }
    var failed by remember(url) { mutableStateOf(false) }
    if (request != null) {
        AsyncImage(
            model =
                ImageRequest.Builder(context)
                    .data(request.url.toString())
                    .headers(request.headers)
                    .size(1200)
                    .build(),
            contentDescription = alt.ifBlank { "图片" },
            onError = { failed = true },
            modifier = Modifier.fillMaxWidth().height(220.dp).clickable { onImage(url) },
        )
        if (failed) TextButton(onClick = { onImage(url) }) { Text("图片加载失败，点击重试") }
    }
}

@Composable
fun ImagePreview(url: String, api: Api, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope=rememberCoroutineScope()
    var working by remember{mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)}
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale > 1f) offset + pan else Offset.Zero
                }
            }
        ) {
            val req = remember { runCatching { api.imageRequest(url) }.getOrNull() }
            if (req != null)
                AsyncImage(
                    model =
                        ImageRequest.Builder(context)
                            .data(req.url.toString())
                            .headers(req.headers)
                            .size(2400)
                            .build(),
                    contentDescription = "图片预览",
                    modifier =
                        Modifier.fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                )
            Row {
                TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
                TextButton(enabled=!working,onClick={scope.launch{working=true;try{saveImage(context,api,url);notice="已保存到相册"}catch(e:Exception){notice=e.message?:"保存失败"}finally{working=false}}}){Text("保存",color=Color.White)}
                TextButton(enabled=!working,onClick={scope.launch{working=true;try{shareDownload(context,api,url,"xiaoli-image","image/*")}catch(e:Exception){notice=e.message?:"分享失败"}finally{working=false}}}){Text("分享",color=Color.White)}
            }
            notice?.let{Text(it,color=Color.White,modifier=Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(24.dp))}
        }
    }
}
