package com.cyeam.xiaoli

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Process
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class ChatMedia(private val activity: MainActivity) {
    private val downloads = mutableMapOf<WebView, ChatDownloads>()
    fun attach(view: WebView) {
        downloads[view] = ChatDownloads(activity, view).also { it.install() }
    }

    fun pageChanged(view: WebView) { downloads[view]?.cancelPrompt() }

    private var fileOwner: WebView? = null
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private var picking = false
    private var fileGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val picker = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picking = false
        val callback = fileResult
        fileResult = null
        fileOwner = null
        val intent = result.data
        val chosen = if (result.resultCode == Activity.RESULT_OK && intent != null) {
            val clips = intent.clipData
            val uris = if (clips == null) listOfNotNull(intent.data) else (0 until clips.itemCount).mapNotNull { clips.getItemAt(it).uri }
            uris.distinct().filter { uri ->
                uri.scheme == "content" && uri.authority != null &&
                    activity.packageManager.resolveContentProvider(uri.authority!!, 0)?.applicationInfo?.uid != Process.myUid() &&
                    activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), fileGrant) == PackageManager.PERMISSION_GRANTED
            }.toTypedArray().takeIf { it.isNotEmpty() }
        } else null
        callback?.onReceiveValue(chosen)
    }

    fun choose(view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        if (picking || params.mode !in listOf(WebChromeClient.FileChooserParams.MODE_OPEN, WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE, WebChromeClient.FileChooserParams.MODE_SAVE)) {
            callback.onReceiveValue(null)
            return true
        }
        val types = params.acceptTypes.flatMap { it.split(',') }.mapNotNull { value ->
            val type = value.trim().lowercase(Locale.ROOT)
            if (type.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(type.drop(1))
            else type.takeIf { it.matches(Regex("""[\w.+*-]+/[\w.+*-]+""")) }
        }.flatMap { type ->
            when (type) {
                "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
                "application/zip", "application/x-zip-compressed" -> listOf("application/zip", "application/x-zip-compressed")
                else -> listOf(type)
            }
        }.distinct()
        val saving = params.mode == WebChromeClient.FileChooserParams.MODE_SAVE
        fileGrant = if (saving) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION
        val intent = Intent(if (saving) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (saving) params.filenameHint?.let { putExtra(Intent.EXTRA_TITLE, it.substringAfterLast('/').substringAfterLast('\\')) }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }
        fileResult = callback
        fileOwner = view
        picking = true
        try { picker.launch(intent) } catch (_: ActivityNotFoundException) {
            picking = false
            fileOwner = null
            fileResult = null
            callback.onReceiveValue(null)
            notice("手机没有可用的文件选择器")
        }
        return true
    }

    private var micOwner: WebView? = null
    private var microphone: PermissionRequest? = null
    private var askingMic = false
    private val micPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        askingMic = false
        microphone?.let {
            if (granted) it.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else it.deny()
        }
        microphone = null
        micOwner = null
    }

    fun requestMic(view: WebView, request: PermissionRequest) {
        val uri = request.origin
        if (uri.scheme != "https" || uri.host != "aily.feishu.cn" || uri.port !in listOf(-1, 443) ||
            PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources || askingMic) {
            request.deny()
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            micOwner = view
            microphone = request
            askingMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun cancelMic(request: PermissionRequest) {
        if (microphone === request) {
            microphone = null
            micOwner = null
        }
    }

    fun detach(view: WebView) {
        downloads.remove(view)?.cancelPrompt()
        if (micOwner === view) { microphone?.deny(); microphone = null; micOwner = null }
        if (fileOwner === view) {
            fileOwner = null
            fileResult?.onReceiveValue(null)
            fileResult = null
        }
    }

    private var locationJob: Job? = null
    private var locationDialog: android.app.AlertDialog? = null
    private var locationOwner: FeishuPanel? = null
    private var askingLocation = false
    private val locationPermission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        askingLocation = false
        if (locationOwner == null) return@registerForActivityResult
        if (hasLocation()) locate() else notice("需要定位权限才能获取当前位置")
    }
    private fun hasLocation() = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun shareLocation(panel: FeishuPanel) {
        if (askingLocation || locationJob?.isActive == true) return
        locationOwner = panel
        if (hasLocation()) locate() else {
            askingLocation = true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun locate() {
        val manager = activity.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { manager.isProviderEnabled(it) }
        if (providers.isEmpty()) { notice("请先打开手机定位"); return }
        locationJob = activity.lifecycleScope.launch {
            notice("正在获取当前位置")
            var listener: LocationListenerCompat? = null
            val location = try {
                withTimeoutOrNull(20000) {
                    suspendCancellableCoroutine<android.location.Location?> { result ->
                        val callback = LocationListenerCompat { fix -> if (result.isActive) result.resume(fix) }
                        listener = callback
                        val request = LocationRequestCompat.Builder(1000).setMaxUpdates(1).build()
                        val executor = ContextCompat.getMainExecutor(activity)
                        val started = providers.map { provider ->
                            runCatching { LocationManagerCompat.requestLocationUpdates(manager, provider, request, executor, callback) }.isSuccess
                        }
                        if (started.none { it }) result.resume(null)
                    }
                }
            } finally {
                listener?.let { LocationManagerCompat.removeUpdates(manager, it) }
            }
            if (location == null) { notice("暂时无法定位，请稍后重试"); return@launch }
            if (locationOwner == null) return@launch
            val lat = "%.6f".format(Locale.US, location.latitude)
            val lon = "%.6f".format(Locale.US, location.longitude)
            val text = "当前位置：$lat, $lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon"
            locationDialog = android.app.AlertDialog.Builder(activity)
                .setTitle("当前位置").setMessage(text)
                .setPositiveButton("复制到会话") { _, _ ->
                    activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("当前位置", text))
                    notice("已复制，请在飞书输入框粘贴并发送")
                }.setNegativeButton("取消", null).show()
        }
    }

    fun closePanel(panel: FeishuPanel) {
        if (locationOwner !== panel) return
        locationOwner = null
        locationJob?.cancel()
        locationDialog?.dismiss()
        locationDialog = null
    }

    fun openLink(uri: Uri) {
        if (uri.scheme !in listOf("https", "geo")) return
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        try { activity.startActivity(intent) } catch (_: ActivityNotFoundException) { notice("没有可打开此链接的应用") }
    }
    private fun notice(message: String) = Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
}
