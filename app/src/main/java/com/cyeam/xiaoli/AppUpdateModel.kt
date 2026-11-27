package com.cyeam.xiaoli

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class UpdateStage { CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, FAILED }
internal data class UpdateState(val stage: UpdateStage, val message: String, val release: AppRelease? = null, val progress: Float? = null)

internal fun copyVerifiedUpdate(input: InputStream, target: File, sha256: String) {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, digest).use { source -> target.outputStream().use { source.copyTo(it) } }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    check(actual == sha256) { "安装包校验失败，请重新下载" }
}

internal class AppUpdateModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val apk = File(context.filesDir, "apk-updates/xiaoli.apk")
    private var manifest = ""
    private var active = false
    var state by mutableStateOf(UpdateState(UpdateStage.CURRENT, "当前版本 ${BuildConfig.VERSION_NAME}"))
        private set
    var visible by mutableStateOf(false)
        private set

    init {
        val id = prefs.getLong("id", -1)
        if (id >= 0) {
            manifest = prefs.getString("manifest", "").orEmpty()
            val release = runCatching { newerVersion(manifest, BuildConfig.VERSION_CODE.toLong()) }.getOrNull()
            if (release != null) {
                visible = true
                runUpdate { monitor(id, release) }
            } else {
                clearDownload()
                if (!BuildConfig.DEBUG) check(manual = false)
            }
        } else if (!BuildConfig.DEBUG) check(manual = false)
    }

    fun dismiss() { visible = false }

    fun check(manual: Boolean = true) {
        if (manual) visible = true
        if (active || state.stage in setOf(UpdateStage.AVAILABLE, UpdateStage.READY)) return
        runUpdate {
            clearDownload()
            state = UpdateState(UpdateStage.CHECKING, "正在获取最新版本信息…")
            manifest = withContext(Dispatchers.IO) { fetchUpdateManifest() }
            val release = newerVersion(manifest, BuildConfig.VERSION_CODE.toLong())
            if (release == null) state = UpdateState(UpdateStage.CURRENT, "当前版本 ${BuildConfig.VERSION_NAME}")
            else {
                state = UpdateState(UpdateStage.AVAILABLE, "当前版本 ${BuildConfig.VERSION_NAME}。下载后可在手机上覆盖安装。", release)
                if (!manual) visible = true
            }
        }
    }

    fun download() {
        val release = state.release ?: return
        if (active || state.stage != UpdateStage.AVAILABLE) return
        runUpdate {
            val request = DownloadManager.Request(Uri.parse(release.url))
                .setTitle("小李 ${release.name}")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            val id = downloads.enqueue(request)
            if (!prefs.edit().putLong("id", id).putString("manifest", manifest).commit()) {
                downloads.remove(id)
                error("无法保存下载任务，请重试")
            }
            monitor(id, release)
        }
    }

    private fun runUpdate(block: suspend () -> Unit) {
        active = true
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                state = UpdateState(UpdateStage.FAILED, e.localizedMessage ?: "更新服务暂不可用，请重试")
            } finally { active = false }
        }
    }

    private suspend fun monitor(id: Long, release: AppRelease) {
        state = UpdateState(UpdateStage.DOWNLOADING, "正在准备下载…", release)
        while (true) {
            val status = withContext(Dispatchers.IO) {
                downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    check(c != null && c.moveToFirst()) { "下载任务已失效，请重试" }
                    Triple(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                }
            }
            when (status.first) {
                DownloadManager.STATUS_SUCCESSFUL -> break
                DownloadManager.STATUS_FAILED -> error("安装包下载失败，请检查网络后重试")
                else -> {
                    val fraction = status.third.takeIf { it > 0 }?.let { (status.second.toFloat() / it).coerceIn(0f, 1f) }
                    val message = when {
                        status.first == DownloadManager.STATUS_PAUSED -> "正在等待网络或系统恢复下载"
                        fraction != null -> "已下载 ${(fraction * 100).toInt()}%"
                        else -> "正在下载安装包…"
                    }
                    state = UpdateState(UpdateStage.DOWNLOADING, message, release, fraction)
                }
            }
            delay(600)
        }
        state = UpdateState(UpdateStage.VERIFYING, "正在核对文件、版本和签名…", release)
        withContext(Dispatchers.IO) {
            val dir = checkNotNull(apk.parentFile)
            check(dir.isDirectory || dir.mkdirs()) { "无法创建更新目录" }
            val tmp = File.createTempFile("update-", ".apk", dir)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(downloads.openDownloadedFile(id)).use {
                    copyVerifiedUpdate(it, tmp, release.sha256)
                }
                verifyPackage(tmp, release)
                check(tmp.renameTo(apk)) { "无法保存安装包" }
            } finally { tmp.delete() }
        }
        state = UpdateState(UpdateStage.READY, "点「安装更新」后，在系统安装器中确认。", release)
    }

    private fun verifyPackage(file: File, release: AppRelease) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        @Suppress("DEPRECATION")
        val incoming = checkNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags)) { "无法读取安装包" }
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, flags)
        check(incoming.packageName == installed.packageName && incoming.longVersionCode == release.code &&
            incoming.longVersionCode > installed.longVersionCode) { "安装包的应用或版本不匹配" }
        val signatures = incoming.signingInfo?.apkContentsSigners.orEmpty()
        check(signatures.isNotEmpty() && signatures.toSet() == installed.signingInfo?.apkContentsSigners?.toSet()) {
            "安装包签名与当前小李不同"
        }
    }

    fun installIntent(): Intent {
        check(state.stage == UpdateStage.READY)
        if (!apk.isFile) {
            state = UpdateState(UpdateStage.FAILED, "安装包已失效，请重新下载")
            error(state.message)
        }
        return installUpdateIntent(context, apk)
    }

    private fun clearDownload() {
        val id = prefs.getLong("id", -1)
        if (id >= 0) downloads.remove(id)
        prefs.edit().clear().apply()
        apk.parentFile?.listFiles()?.forEach { it.delete() }
    }
}
