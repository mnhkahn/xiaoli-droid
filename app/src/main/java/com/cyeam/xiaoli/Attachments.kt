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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
internal fun AttachmentActions(model: ChatModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
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
    Row {
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
            TextButton(onClick = { menu = true }, enabled = !model.sending) { Text("添加附件") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for ((label, image) in listOf("图片" to true, "文件或文档" to false)) {
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menu = false
                        pickingImage = image
                        picker.launch(arrayOf(if (image) "image/*" else "*/*"))
                    })
                }
                DropdownMenuItem(text = { Text("录制语音") }, onClick = {
                    menu = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                })
                DropdownMenuItem(text = { Text("当前位置") }, onClick = {
                    menu = false
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) locate()
                    else gps.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                })
            }
        }
        if (model.sending) { Spacer(Modifier.width(8.dp)); Text("正在发送…") }
    }
}
