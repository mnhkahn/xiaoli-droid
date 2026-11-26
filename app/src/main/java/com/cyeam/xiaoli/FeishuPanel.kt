package com.cyeam.xiaoli

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Message
import android.text.TextUtils
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.ByteArrayInputStream

/** Web SDK host. Sign-in windows share WebView cookies with the embedded Feishu frame. */
internal class FeishuPanel(context: Context, private val channel: FeishuChannel?, private val onReturnFromLogin: () -> Unit = {}) : FrameLayout(context) {
    private val origin = "https://$FEISHU_PANEL_HOST"
    private val home = "$origin/panel.html"
    private var visitedPassport = false

    init {
        val main = browser()
        addView(main)
        main.loadUrl(if (channel == null) "https://aily.feishu.cn/" else home)
    }

    private fun isFeishu(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (uri.scheme != "https") return false
        return host == "accounts.doubao.com" || listOf("feishu.cn", "larkoffice.com", "larksuite.com").any {
            host == it || host.endsWith(".$it")
        }
    }

    private fun localAsset(uri: Uri): WebResourceResponse? {
        if (uri.host != FEISHU_PANEL_HOST) return null
        val asset = when (uri.toString()) {
            home -> {
                val selected = channel ?: return WebResourceResponse("text/plain", "UTF-8", "请选择智能体".byteInputStream())
                val template = context.assets.open("feishu/panel.html").bufferedReader().use { it.readText() }
                WebResourceResponse("text/html", "UTF-8", template.replace("__CHANNEL__", TextUtils.htmlEncode(selected.token)).byteInputStream())
            }
            "$origin/panel.js" -> WebResourceResponse("application/javascript", "UTF-8", context.assets.open("feishu/panel.js"))
            else -> return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
        asset.responseHeaders = mapOf("Cache-Control" to "no-store", "Referrer-Policy" to "strict-origin",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; frame-src https://accounts.doubao.com https://*.feishu.cn https://*.larkoffice.com https://*.larksuite.com; base-uri 'none'; form-action 'none'")
        return asset
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun browser(): WebView = WebView(context).apply {
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (channel != null) return
                val host = Uri.parse(url).host
                if (host == "accounts.feishu.cn" || host == "accounts.doubao.com") visitedPassport = true
                if (visitedPassport && host == "aily.feishu.cn") {
                    visitedPassport = false
                    onReturnFromLogin()
                }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = localAsset(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return request.url.scheme !in setOf("https", "about", "blob")
                return request.url.toString() != home && !isFeishu(request.url) && request.url.toString() != "about:blank"
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, dialog: Boolean, userGesture: Boolean, result: Message): Boolean {
                if (!userGesture || this@FeishuPanel.childCount >= 3) return false
                val popup = browser()
                this@FeishuPanel.addView(popup)
                (result.obj as WebView.WebViewTransport).webView = popup
                result.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                if (this@FeishuPanel.indexOfChild(window) > 0) dispose(window)
            }
        }
    }

    private fun dispose(view: WebView) {
        removeView(view)
        view.stopLoading()
        view.webChromeClient = null
        view.destroy()
    }

    fun back(): Boolean {
        val view = getChildAt(childCount - 1) as? WebView ?: return false
        if (view.canGoBack()) { view.goBack(); return true }
        if (childCount > 1) { dispose(view); return true }
        return false
    }

    fun close() {
        while (childCount > 0) dispose(getChildAt(childCount - 1) as WebView)
        CookieManager.getInstance().flush()
    }
}
