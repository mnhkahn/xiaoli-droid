package com.cyeam.xiaoli

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun decodeImage(file: File, maxSide: Int): android.graphics.Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, options)
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSide) options.inSampleSize *= 2
    return BitmapFactory.decodeFile(file.path, options)
}

@Composable
internal fun MediaPreview(file: File, type: String, notice: (String) -> Unit, close: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var image by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var playing by remember(file) { mutableStateOf(false) }
    var player by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    val onClose by rememberUpdatedState(close)
    val onError by rememberUpdatedState(notice)
    DisposableEffect(file, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onClose() }
        lifecycle.addObserver(observer)
        val media = if (type == "audio") MediaPlayer() else null
        if (media != null) {
            runCatching {
                media.setDataSource(file.absolutePath)
                media.setOnPreparedListener { player = it; it.start(); playing = true }
                media.setOnCompletionListener { playing = false }
                media.setOnErrorListener { _, _, _ -> onError("无法播放此录音"); onClose(); true }
                media.prepareAsync()
            }.onFailure { onError("无法播放此录音"); onClose() }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            media?.release()
            file.delete()
        }
    }
    LaunchedEffect(file) {
        if (type == "image") {
            image = withContext(Dispatchers.IO) {
                decodeImage(file, 2048)
            }
            if (image == null) { onError("无法预览这种图片格式，可保存原文件"); onClose() }
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text(if (type == "image") "图片" else "语音") },
        text = {
            if (type == "image") image?.let { Image(it.asImageBitmap(), "图片", Modifier.fillMaxWidth()) }
            else TextButton(onClick = {
                player?.let { if (playing) it.pause() else it.start(); playing = !playing }
            }, enabled = player != null) { Text(if (playing) "暂停" else "播放") }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
