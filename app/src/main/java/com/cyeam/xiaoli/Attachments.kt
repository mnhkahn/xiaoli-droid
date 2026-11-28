package com.cyeam.xiaoli

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source

internal fun fileBody(context: Context, uri: Uri): RequestBody {
    require(uri.scheme == "content") { "请选择手机上的文件" }
    val resolver = context.contentResolver
    return object : RequestBody() {
        override fun contentType() = resolver.getType(uri)?.toMediaTypeOrNull()
        override fun writeTo(sink: BufferedSink) {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法打开文件")
            input.source().use { sink.writeAll(it) }
        }
    }
}

internal fun locationText(lat: Double, lon: Double): String {
    val x = "%.6f".format(Locale.US, lat)
    val y = "%.6f".format(Locale.US, lon)
    return "当前位置：$x, $y\nhttps://www.openstreetmap.org/?mlat=$x&mlon=$y#map=16/$x/$y"
}

@SuppressLint("MissingPermission")
internal suspend fun getLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { manager.isProviderEnabled(it) }
    if (providers.isEmpty()) return null
    var listener: LocationListenerCompat? = null
    return try {
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { result ->
                val callback = LocationListenerCompat { if (result.isActive) result.resume(it) }
                listener = callback
                val request = LocationRequestCompat.Builder(1000).setMaxUpdates(1).build()
                val started = providers.map { provider -> runCatching {
                    LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), callback)
                }.isSuccess }
                if (started.none { it }) result.resume(null)
            }
        }
    } finally { listener?.let { LocationManagerCompat.removeUpdates(manager, it) } }
}

internal class VoiceRecording(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startMs = 0L
    @Suppress("DEPRECATION")
    fun start() {
        val tmp = File.createTempFile("voice-", ".ogg", context.cacheDir)
        file = tmp
        try {
            val recording = MediaRecorder()
            recorder = recording
            recording.setAudioSource(MediaRecorder.AudioSource.MIC)
            recording.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            recording.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            recording.setOutputFile(tmp.absolutePath)
            recording.prepare()
            recording.start()
            startMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) { discard(); throw e }
    }
    fun finish(): Pair<File, Long> {
        try {
            requireNotNull(recorder).stop()
            val result = requireNotNull(file) to (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1)
            file = null
            return result
        } finally { discard() }
    }
    fun discard() {
        recorder?.release()
        recorder = null
        file?.delete()
        file = null
    }
}

@Composable
internal fun MessageComposer(model: ChatModel, peerName: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var emoji by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pickingImage by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "附件"
            model.sendAttachment(name, fileBody(context, uri), if (pickingImage) "image" else "file")
        }.onFailure { model.notice(it.message ?: "无法读取文件") }
    }
    val voice = remember { VoiceRecording(context) }
    var recording by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { voice.discard(); recording = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice.discard() }
    }
    fun start() { runCatching { voice.start(); recording = true }.onFailure { model.notice("无法使用麦克风") } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) start() else model.notice("需要麦克风权限才能发送语音")
    }
    fun locate() { scope.launch {
        try {
            val point = getLocation(context)
            if (point == null) model.notice("暂时无法定位，请检查系统定位后重试")
            else model.edit(listOf(model.draft, locationText(point.latitude, point.longitude)).filter { it.isNotBlank() }.joinToString("\n"))
        } catch (e: SecurityException) { model.notice("需要定位权限") }
    } }
    val gps = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { it }) locate() else model.notice("需要定位权限才能分享位置")
    }
    val toolColor = chatColor(0xff626976, 0xffb7bdc8)
    val accent = chatColor(0xff3267df, 0xff91b6ff)
    Column(Modifier.fillMaxWidth().background(chatColor(0xfff5f6f8, 0xff21242a))) {
        HorizontalDivider(color = chatColor(0xffe9eaed, 0xff363941))
        var editor by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(model.draft)) }
        // Keep the IME selection while accepting location inserts and a successful send from the model.
        val value = if (editor.text == model.draft) editor else TextFieldValue(model.draft, TextRange(model.draft.length))
        SideEffect { if (editor != value) editor = value }
        fun change(next: TextFieldValue) {
            editor = next
            if (model.draft != next.text) model.edit(next.text)
        }
        Surface(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp),
            shape = RoundedCornerShape(14.dp), color = chatColor(0xffffffff, 0xff30333a)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value, ::change, Modifier.weight(1f).padding(start = 14.dp, top = 12.dp, bottom = 12.dp),
                    minLines = if (expanded) 3 else 1, maxLines = if (expanded) 6 else 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(accent),
                    decorationBox = { inner -> Box {
                        if (value.text.isEmpty()) Text("发送给 $peerName", color = toolColor, maxLines = 1)
                        inner()
                    } })
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                        if (expanded) "收起输入框" else "展开输入框", Modifier.size(19.dp), tint = toolColor)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                TextButton(onClick = {
                    recording = false
                    runCatching {
                        val (tmp, duration) = voice.finish()
                        model.sendAttachment("语音.ogg", tmp.asRequestBody("audio/ogg".toMediaTypeOrNull()), "audio", duration) { tmp.delete() }
                    }.onFailure { model.notice("录音过短或麦克风不可用") }
                }, enabled = !model.sending) { Text("结束并发送语音") }
                TextButton(onClick = { voice.discard(); recording = false }) { Text("取消录音") }
            } else {
                IconButton(onClick = { emoji = !emoji }) {
                    Icon(Icons.Outlined.SentimentSatisfiedAlt, "表情", tint = if (emoji) accent else toolColor)
                }
                IconButton(onClick = {
                    emoji = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = !model.sending) { Icon(Icons.Outlined.MicNone, "录制语音") }
                IconButton(onClick = { emoji = false; pickingImage = true; picker.launch(arrayOf("image/*")) },
                    enabled = !model.sending) { Icon(Icons.Outlined.Image, "选择图片") }
                Box {
                    IconButton(onClick = { emoji = false; menu = true }, enabled = !model.sending) { Icon(Icons.Outlined.AddCircleOutline, "更多附件") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("文件或文档") }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                            onClick = { menu = false; pickingImage = false; picker.launch(arrayOf("*/*")) })
                        DropdownMenuItem(text = { Text("当前位置") }, leadingIcon = { Icon(Icons.Outlined.LocationOn, null) }, onClick = {
                            menu = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                            else gps.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        })
                    }
                }
                Spacer(Modifier.weight(1f))
                if (model.sending) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { emoji = false; model.sendText() }, enabled = model.draft.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = accent)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
        if (emoji && !recording) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (symbol in listOf("😀", "👍", "❤️", "🎉", "🙏", "👏")) {
                IconButton(onClick = {
                    val start = value.selection.min
                    val text = value.text.replaceRange(start, value.selection.max, symbol)
                    change(TextFieldValue(text, TextRange(start + symbol.length)))
                }) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
