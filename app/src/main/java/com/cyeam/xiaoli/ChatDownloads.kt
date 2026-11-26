package com.cyeam.xiaoli

import android.app.AlertDialog
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/** One download prompt per WebView. Bytes become visible in Downloads only after a complete write. */
internal class ChatDownloads(private val activity: MainActivity, private val view: WebView) {
    private val resolver get() = activity.contentResolver
    private var dialog: AlertDialog? = null
    private var blobReply: JavaScriptReplyProxy? = null
    private var blobFile: Uri? = null
    private var blobOutput: OutputStream? = null
    private var expected = 0L
    private var received = 0L
    private var chunkJob: Job? = null

    fun install() {
        view.setDownloadListener { url, userAgent, disposition, mime, _ -> offerUrl(url, userAgent, disposition, mime) }
        view.setOnLongClickListener {
            val hit = view.hitTestResult
            val url = hit.extra
            if (hit.type !in listOf(WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) ||
                url == null || Uri.parse(url).scheme != "https") return@setOnLongClickListener false
            val name = filename(URLUtil.guessFileName(url, null, null))
            confirm(name, { saveUrl(url, view.settings.userAgentString, name, null) })
            true
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)) {
            val origins = setOf("https://aily.feishu.cn")
            WebViewCompat.addWebMessageListener(view, "xiaoliDownload", origins) { _, message, _, _, reply ->
                if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) {
                    if (reply === blobReply && blobOutput != null && chunkJob?.isActive != true) writeChunk(message.arrayBuffer ?: return@addWebMessageListener)
                } else if (message.type == WebMessageCompat.TYPE_STRING) {
                    val meta = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
                    when (meta.optString("op")) {
                        "offer" -> offerBlob(meta, reply)
                        "done" -> if (reply === blobReply && blobOutput != null && received == expected && chunkJob?.isActive != true) {
                            runCatching {
                                blobOutput?.close()
                                publish(checkNotNull(blobFile))
                                blobFile = null
                                notice("已保存到下载目录")
                            }.onFailure { notice("保存失败，请重试") }
                            resetBlob()
                        }
                        "cancel" -> if (reply === blobReply) resetBlob()
                    }
                }
            }
            val script = activity.assets.open("feishu/download.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(view, script, origins)
        }
    }

    internal fun offerUrl(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (Uri.parse(url).scheme != "https") {
            notice("此链接无法直接保存，请打开原文件或更新 Android System WebView")
            return
        }
        val name = filename(URLUtil.guessFileName(url, disposition, mime))
        confirm(name, { saveUrl(url, userAgent, name, mime) })
    }

    private fun confirm(name: String, accept: () -> Unit, cancel: () -> Unit = {}) {
        if (dialog?.isShowing == true || activity.isFinishing || activity.isDestroyed) { cancel(); return }
        dialog = AlertDialog.Builder(activity).setTitle("保存到下载目录？").setMessage(name)
            .setPositiveButton("保存") { _, _ -> accept() }
            .setNegativeButton("取消") { _, _ -> cancel() }.setOnCancelListener { cancel() }.show()
    }

    private fun filename(name: String) = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() }.trim().trimStart('.').take(150).ifBlank { "飞书附件" }

    private fun create(name: String, type: String?): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type?.substringBefore(';')?.takeIf { it.contains('/') } ?: "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("无法创建下载文件")
    }

    private fun publish(uri: Uri) {
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
    }

    private fun saveUrl(address: String, agent: String?, name: String, mime: String?) {
        activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val connection = openDownload(URL(address), agent)
                    var pending: Uri? = null
                    try {
                        val type = connection.contentType
                        if (type?.startsWith("text/html") == true && mime?.startsWith("text/html") != true &&
                            !name.endsWith(".html", true) && !name.endsWith(".htm", true))
                            throw IOException("登录已过期或附件不可用")
                        val uri = create(name, type ?: mime)
                        pending = uri
                        connection.inputStream.use { input ->
                            checkNotNull(resolver.openOutputStream(uri)).use { out ->
                                val buffer = ByteArray(65536)
                                var count = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    out.write(buffer, 0, read)
                                    count += read
                                }
                                val advertised = connection.contentLengthLong
                                if (advertised >= 0 && count != advertised) throw IOException("下载中断")
                            }
                        }
                        publish(uri)
                        pending = null
                    } finally {
                        connection.disconnect()
                        pending?.let { resolver.delete(it, null, null) }
                    }
                }
                notice("已保存到下载目录")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { notice("保存失败，请检查网络和飞书文件权限") }
        }
    }

    private fun openDownload(start: URL, agent: String?): HttpsURLConnection {
        var url = start
        repeat(8) {
            require(url.protocol == "https" && url.userInfo == null)
            val conn = url.openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                if (agent != null) conn.setRequestProperty("User-Agent", agent)
                // Re-evaluate cookies for each destination. Never replay the first host's cookies on redirect.
                if (listOf("feishu.cn", "larkoffice.com", "larksuite.com").any { url.host == it || url.host.endsWith(".$it") }) {
                    CookieManager.getInstance().getCookie(url.toString())?.let { conn.setRequestProperty("Cookie", it) }
                }
                when (conn.responseCode) {
                    in 200..299 -> return conn
                    301, 302, 303, 307, 308 -> url = URL(url, conn.getHeaderField("Location") ?: throw IOException("重定向缺少地址"))
                    else -> throw IOException("附件不可用")
                }
            } catch (e: Exception) { conn.disconnect(); throw e }
            conn.disconnect()
        }
        throw IOException("重定向次数过多")
    }

    private fun offerBlob(meta: JSONObject, reply: JavaScriptReplyProxy) {
        if (blobReply != null) return
        expected = meta.optLong("size", -1)
        if (expected < 0) return
        val name = filename(meta.optString("name"))
        blobReply = reply
        confirm(name, {
            runCatching {
                blobFile = create(name, meta.optString("type"))
                blobOutput = checkNotNull(resolver.openOutputStream(checkNotNull(blobFile)))
                reply.postMessage("next")
            }.onFailure { resetBlob(); notice("保存失败，请重试") }
        }, { resetBlob() })
    }

    private fun writeChunk(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > 65536 || bytes.size > expected - received) { resetBlob(); return }
        val out = blobOutput ?: return
        chunkJob = activity.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { out.write(bytes) }
                received += bytes.size
                blobReply?.postMessage("next")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { resetBlob(); notice("保存失败，请重试") }
        }
    }

    private fun resetBlob() {
        chunkJob?.cancel()
        chunkJob = null
        runCatching { blobOutput?.close() }
        blobOutput = null
        blobFile?.let { resolver.delete(it, null, null) }
        blobFile = null
        blobReply?.let { runCatching { it.postMessage("closed") } }
        blobReply = null
        received = 0
    }

    fun cancelPrompt() {
        dialog?.dismiss()
        dialog = null
        resetBlob()
    }
    private fun notice(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
}
