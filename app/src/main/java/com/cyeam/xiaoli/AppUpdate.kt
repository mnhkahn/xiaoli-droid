package com.cyeam.xiaoli

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val RELEASE_MANIFEST = "https://github.com/mnhkahn/xiaoli-droid/releases/latest/download/update.json"
private const val DOWNLOAD_PAGE = "https://www.pgyer.com/xiaoli-android-2"

internal fun newerVersion(manifest: String, installedCode: Int): String? {
    val release = JSONObject(manifest)
    val code = release.getLong("versionCode")
    val name = release.getString("versionName").trim()
    require(code > 0 && name.isNotEmpty())
    return name.takeIf { code > installedCode }
}

private fun fetchNewerVersion(): String? {
    val connection = URL(RELEASE_MANIFEST).openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.useCaches = false
        val manifest = connection.inputStream.bufferedReader().use { it.readText() }
        return newerVersion(manifest, BuildConfig.VERSION_CODE)
    } finally {
        connection.disconnect()
    }
}

@Composable
internal fun AppUpdateDialog(manualRequest: Int) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    var version by rememberSaveable { mutableStateOf<String?>(null) }
    var failed by rememberSaveable { mutableStateOf(false) }
    var handledRequest by rememberSaveable { mutableIntStateOf(-1) }
    val manual = manualRequest > 0
    LaunchedEffect(manualRequest) {
        if (handledRequest == manualRequest || (!manual && BuildConfig.DEBUG)) return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { runCatching { fetchNewerVersion() } }
        handledRequest = manualRequest
        version = result.getOrNull()
        failed = manual && result.isFailure
        if (manual && result.isSuccess && version == null) {
            Toast.makeText(context, "当前已是最新版本（${BuildConfig.VERSION_NAME}）", Toast.LENGTH_SHORT).show()
        }
    }
    if (version == null && !failed) return
    fun dismiss() { version = null; failed = false }
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(if (failed) "暂时无法检查更新" else "发现新版本 $version") },
        text = { Text("当前版本 ${BuildConfig.VERSION_NAME}。可前往蒲公英下载并覆盖安装。") },
        confirmButton = {
            TextButton(onClick = {
                try {
                    links.openUri(DOWNLOAD_PAGE)
                    dismiss()
                } catch (_: IllegalArgumentException) {
                    Toast.makeText(context, "请用浏览器打开 $DOWNLOAD_PAGE", Toast.LENGTH_LONG).show()
                }
            }) { Text("打开蒲公英") }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("稍后") } }
    )
}
