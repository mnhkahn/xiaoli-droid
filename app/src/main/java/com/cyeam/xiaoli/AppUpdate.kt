package com.cyeam.xiaoli

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

private const val RELEASES = "https://github.com/mnhkahn/xiaoli-droid/releases"
internal const val APK_MIME = "application/vnd.android.package-archive"

internal data class AppRelease(val name: String, val code: Long, val url: String, val sha256: String)

internal fun newerVersion(manifest: String, installedCode: Long): AppRelease? {
    val data = JSONObject(manifest)
    val code = data.getLong("versionCode")
    val name = data.getString("versionName").trim()
    require(code > 0 && name.isNotBlank()) { "更新清单版本无效" }
    if (code <= installedCode) return null
    require(name.matches(Regex("""\d+\.\d+\.\d+"""))) { "更新版本格式无效" }
    val url = data.getString("apkUrl")
    require(url in listOf("$RELEASES/download/v$name/xiaoli.apk", "$RELEASES/download/$name/xiaoli.apk")) { "更新下载地址无效" }
    val hash = data.getString("sha256").lowercase()
    require(hash.matches(Regex("[0-9a-f]{64}"))) { "更新清单缺少有效的 SHA-256" }
    return AppRelease(name, code, url, hash)
}

internal fun fetchUpdateManifest(): String {
    val connection = URL("$RELEASES/latest/download/update.json").openConnection() as HttpsURLConnection
    return try {
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.useCaches = false
        check(connection.responseCode == 200) { "更新服务返回 HTTP ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
}

internal fun installUpdateIntent(context: Context, apk: File): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

@Composable
internal fun AppUpdateDialog(model: AppUpdateModel) {
    val context = LocalContext.current
    val state = model.state
    var installError by rememberSaveable { mutableStateOf<String?>(null) }
    fun install() {
        runCatching { context.startActivity(model.installIntent()) }
            .onFailure { installError = it.localizedMessage ?: "无法打开系统安装器" }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls() && model.state.stage == UpdateStage.READY) install()
        else if (!context.packageManager.canRequestPackageInstalls()) installError = "需要允许小李安装应用才能继续。"
    }
    if (!model.visible) return
    fun dismiss() { installError = null; model.dismiss() }
    val title = when (state.stage) {
        UpdateStage.CHECKING -> "正在检查更新"
        UpdateStage.CURRENT -> "已是最新版本"
        UpdateStage.AVAILABLE -> "发现新版本 ${state.release?.name}"
        UpdateStage.DOWNLOADING -> "正在下载 ${state.release?.name}"
        UpdateStage.VERIFYING -> "正在校验安装包"
        UpdateStage.READY -> "可以安装 ${state.release?.name}"
        UpdateStage.FAILED -> "更新失败"
    }
    AlertDialog(onDismissRequest = ::dismiss, title = { Text(title) },
        text = {
            Column {
                Text(installError ?: state.message)
                if (state.stage == UpdateStage.DOWNLOADING) {
                    val progress = state.progress
                    if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                UpdateStage.AVAILABLE -> TextButton(onClick = model::download) { Text("下载更新") }
                UpdateStage.READY -> TextButton(onClick = {
                    installError = null
                    if (context.packageManager.canRequestPackageInstalls()) install()
                    else runCatching {
                        permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    }.onFailure { installError = "无法打开安装权限设置" }
                }) { Text("安装更新") }
                UpdateStage.FAILED -> TextButton(onClick = { installError = null; model.check() }) { Text("重试") }
                else -> TextButton(onClick = ::dismiss) { Text(if (state.stage == UpdateStage.CURRENT) "确定" else "关闭") }
            }
        },
        dismissButton = {
            if (state.stage in setOf(UpdateStage.AVAILABLE, UpdateStage.READY, UpdateStage.FAILED))
                TextButton(onClick = ::dismiss) { Text("稍后") }
        })
}
