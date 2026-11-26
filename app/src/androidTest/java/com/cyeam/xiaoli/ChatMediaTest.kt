package com.cyeam.xiaoli

import android.Manifest
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real SDK creates the cross-origin iframe; only the response from the tenant is replaced. */
class ChatMediaTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context = ins.targetContext
    private val ui = ins.uiAutomation
    private lateinit var activity: MainActivity
    private lateinit var panel: FeishuPanel
    private lateinit var view: WebView
    private lateinit var scenario: ActivityScenario<MainActivity>
    private var uploadServer: okhttp3.mockwebserver.MockWebServer? = null
    private var uploadCert: java.security.cert.X509Certificate? = null
    private val files = mutableListOf<Uri>()
    private val assets get() = ins.context.assets

    @Before fun openChat() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {
            activity = it
            panel = FeishuPanel(it, FeishuChannel("Test", "media-fixture"), it.media)
            view = panel.getChildAt(0) as WebView
            val production = view.webViewClient
            val fixture = assets.open("media-panel.html").use { stream -> stream.readBytes() }
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    if (r.url.host == "aily.feishu.cn" && r.url.path == "/custom_agent/cui")
                        return WebResourceResponse("text/html","utf-8", ByteArrayInputStream(fixture))
                    if (r.url.host == "aily.feishu.cn" && r.url.path == "/docx/device-fixture") {
                        val page = """<h1>Document preview</h1><script>parent.postMessage({fixture:{documentPreview:true}},'https://xiaoli-droid.cyeam.com')</script>"""
                        return WebResourceResponse("text/html", "utf-8", page.byteInputStream())
                    }
                    return production.shouldInterceptRequest(v, r)
                }
                override fun onReceivedSslError(v: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                    // Only the loopback instrumentation server with its ephemeral self-signed certificate.
                    val uri = Uri.parse(error.url)
                    val certificate = android.net.http.SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate")
                    if (uri.host == "localhost" && uri.port == uploadServer?.port &&
                        error.primaryError == android.net.http.SslError.SSL_UNTRUSTED && certificate != null &&
                        uploadCert?.encoded?.contentEquals(certificate) == true) handler.proceed()
                    else handler.cancel()
                }
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = production.shouldOverrideUrlLoading(v, r)
            }
            it.setContentView(panel)
            view.reload()
        }
        waitFor("SDK fixture frame") {
            js("""if (!window.probing) {window.probing=true; addEventListener('message',e=>{if(e.origin==='https://aily.feishu.cn' && e.data.fixture) window.report=e.data.fixture})}; document.querySelector('iframe')?.contentWindow.postMessage('status','https://aily.feishu.cn'); !!window.report""") == "true"
        }
    }

    @After fun close() {
        if (::scenario.isInitialized) {
            scenario.onActivity { if (::panel.isInitialized) panel.close() }
            scenario.close()
        }
        uploadServer?.shutdown()
        files.forEach { context.contentResolver.delete(it, null, null) }
    }

    private fun js(script: String): String {
        val result = ArrayBlockingQueue<String>(1)
        ins.runOnMainSync { view.evaluateJavascript(script) { result.offer(it) } }
        return result.poll(4, TimeUnit.SECONDS) ?: "null"
    }
    private fun report() = JSONObject(js("window.report || {}"))
    private fun waitFor(label: String, timeout: Long = 18000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            if (SystemClock.elapsedRealtime() >= end) throw AssertionError("Timed out: $label (iframe " + js("window.report || {}") + ", window " + ui.rootInActiveWindow?.packageName + ")")
            Thread.sleep(150)
        }
    }
    private fun tapRow(row: Int) {
        val point = IntArray(2)
        ins.runOnMainSync { view.getLocationOnScreen(point) }
        val scale = context.resources.displayMetrics.density
        val x = point[0] + 50 * scale
        val y = point[1] + (row * 54 + 38) * scale
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(now, SystemClock.uptimeMillis(), action,x,y,0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            ui.injectInputEvent(event, true)
            event.recycle()
        }
    }
    private fun clickNode(label: String): Boolean {
        val root = ui.rootInActiveWindow ?: return false
        var node: AccessibilityNodeInfo? = root.findAccessibilityNodeInfosByText(label).firstOrNull {
            it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label,true) == true ||
                (label.startsWith("xiaoli-") && it.contentDescription?.contains(label) == true)
        } ?: return false
        if (label.startsWith("xiaoli-")) {
            // The preview thumbnail is a separate DocumentsUI action. Tap the filename itself.
            val matches = root.findAccessibilityNodeInfosByText(label)
            node = matches.firstOrNull { it.text?.toString() == label }
                ?: matches.firstOrNull { it.contentDescription?.startsWith(label) == true } ?: return false
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) return false
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(
                "input tap " + rect.centerX() + " " + rect.centerY()
            )).use { it.readBytes() }
            return true
        }
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }
    private fun collectionFile(name: String): Uri? {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        return context.contentResolver.query(collection, arrayOf("_id"), "_display_name=? AND is_pending=0",arrayOf(name),null)?.use {
            if (it.moveToFirst()) android.content.ContentUris.withAppendedId(collection,it.getLong(0)) else null
        }
    }
    private fun roundTrip(ext: String, mime: String) {
        val name = "xiaoli-" + UUID.randomUUID().toString().take(6) + "." + ext
        val bytes = assets.open("media/sample.$ext").use { it.readBytes() }
        val resolver = context.contentResolver
        val input = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name); put(MediaStore.Downloads.MIME_TYPE,mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/XiaoliMediaTest/")
            put(MediaStore.Downloads.IS_PENDING,1)
        })!!
        files += input
        resolver.openOutputStream(input)!!.use { it.write(bytes) }
        resolver.update(input, ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
        val tls = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        uploadCert = tls.certificate
        val uploaded = ArrayBlockingQueue<ByteArray>(1)
        val server = okhttp3.mockwebserver.MockWebServer()
        uploadServer = server
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(tls).build().sslSocketFactory(), false)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(req: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val reply = okhttp3.mockwebserver.MockResponse()
                    .addHeader("Access-Control-Allow-Origin", "https://aily.feishu.cn")
                    .addHeader("Access-Control-Allow-Methods", "POST, OPTIONS")
                    .addHeader("Access-Control-Allow-Headers", "Content-Type")
                    .addHeader("Access-Control-Allow-Private-Network", "true")
                if (req.method == "OPTIONS") return reply.setResponseCode(204)
                if (req.method != "POST" || req.path != "/attachment") return reply.setResponseCode(404)
                val body = req.body.readByteArray()
                uploaded.offer(body)
                return reply.addHeader("Content-Type", mime).setBody(okio.Buffer().write(body))
            }
        }
        server.start()
        val config = JSONObject().put("accept", mime).put("endpoint", server.url("/attachment").toString())
        js("document.querySelector('iframe').contentWindow.postMessage($config,'https://aily.feishu.cn')")
        // Keep the real picker and grant flow, but start in the fixture directory on every OS.
        val initialFolder = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_OPEN_DOCUMENT) intent.putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/XiaoliMediaTest")
                )
                return null
            }
        }
        ins.addMonitor(initialFolder)
        try {
            tapRow(0)
            waitFor("system picker") { ui.rootInActiveWindow?.packageName?.toString()?.endsWith("documentsui") == true }
            waitFor("picker result $name") {
                ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(name)?.isNotEmpty() == true
            }
            ui.waitForIdle(350, 3000)
            if (clickNode("List view")) ui.waitForIdle(350, 3000)
            waitFor("select $name") { clickNode(name) }
        } finally { ins.removeMonitor(initialFolder) }
        waitFor("file selection") { report().optString("name") == name }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        waitFor("iframe uploaded $ext and received the reply") {
            val state = report()
            assertFalse(state.toString(),state.has("error"))
            state.optString("hash") == digest
        }
        assertArrayEquals(bytes, uploaded.poll(2,TimeUnit.SECONDS))
        if (mime.startsWith("image/")) waitFor("image preview") { report().optInt("imageWidth") == 32 }
        tapRow(1)
        waitFor("native save confirmation") { clickNode("保存") }
        val savedName = "reply-$name"
        waitFor("published $savedName") { collectionFile(savedName) != null }
        val saved = checkNotNull(collectionFile(savedName))
        files += saved
        assertArrayEquals(bytes,resolver.openInputStream(saved)!!.use { it.readBytes() })
        if (ext == "wav") waitFor("play returned audio") { report().optDouble("audioReplyPlayed") > 0 }
    }

    @Test fun picturePickerPreviewAndDownload() = roundTrip("png","image/png")
    @Test fun jpegPickerPreviewAndDownload() = roundTrip("jpg","image/jpeg")
    @Test fun archivePickerAndDownload() = roundTrip("zip","application/zip")
    @Test fun pdfPickerAndDownload() = roundTrip("pdf","application/pdf")
    @Test fun wordPickerAndDownload() = roundTrip("docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    @Test fun spreadsheetPickerAndDownload() = roundTrip("xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @Test fun audioFilePickerAndDownload() = roundTrip("wav","audio/wav")

    @Test fun documentLinkOpensInFrame() {
        tapRow(5)
        waitFor("cloud document navigation") { report().optBoolean("documentPreview") }
    }

    @Test fun httpsAttachmentFollowsRedirectAndSavesOriginalBytes() {
        val tls = okhttp3.tls.HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverKeys = okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(tls).build()
        val clientTrust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(tls.certificate).build()
        val originalFactory = javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
        val bytes = assets.open("media/sample.pdf").use { it.readBytes() }
        val name = "reply-" + UUID.randomUUID() + ".pdf"
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.useHttps(serverKeys.sslSocketFactory(), false)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(302).addHeader("Location", "/result"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().addHeader("Content-Type", "application/pdf")
                .setBody(okio.Buffer().write(bytes)))
            server.start()
            try {
                javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(clientTrust.sslSocketFactory())
                val url = server.url("/download").toString()
                ins.runOnMainSync {
                    ChatDownloads(activity, view).offerUrl(url, "Xiaoli device test",
                        "attachment; filename=\"$name\"", "application/pdf")
                }
                waitFor("confirm HTTPS attachment") { clickNode("保存") }
                waitFor("HTTPS attachment saved") { collectionFile(name) != null }
                val saved = checkNotNull(collectionFile(name))
                files += saved
                assertArrayEquals(bytes,context.contentResolver.openInputStream(saved)!!.use { it.readBytes() })
                assertEquals("/download", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                assertEquals("/result", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            } finally { javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(originalFactory) }
        }
    }

    @Test fun voiceRecordingAndReplyPlayback() {
        tapRow(2)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            waitFor("microphone permission prompt") {
                val root = ui.rootInActiveWindow ?: return@waitFor false
                val allow = root.findAccessibilityNodeInfosByViewId(root.packageName.toString() + ":id/permission_allow_foreground_only_button").firstOrNull()
                allow?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || clickNode("While using the app")
            }
        }
        waitFor("record and play audio") {
            val state = report()
            assertFalse(state.toString(),state.has("error"))
            state.optInt("recordBytes") > 0 && state.optDouble("played") > 0
        }
    }

    @Suppress("DEPRECATION")
    @Test fun locationCopyPasteAndOpenMapReply() {
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) ui.grantRuntimePermission(context.packageName,permission)
        val mgr = context.getSystemService(LocationManager::class.java)
        val gps = LocationManager.GPS_PROVIDER
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).use { it.readBytes() }
        shell("appops set " + context.packageName + " android:mock_location allow")
        try {
            mgr.addTestProvider(gps,false,true,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
            mgr.setTestProviderEnabled(gps,true)
            ins.runOnMainSync { activity.media.shareLocation(panel) }
            waitFor("location confirmation") {
                mgr.setTestProviderLocation(gps, Location(gps).apply {
                    latitude=39.123456; longitude=116.654321; accuracy=3f;time=System.currentTimeMillis();elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()
                })
                clickNode("复制到会话")
            }
            waitFor("location clipboard") {
                var text = ""
                ins.runOnMainSync { text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString() }
                text.contains("39.123456") && text.contains("mlon=116.654321")
            }
            tapRow(4)
            ins.sendKeySync(android.view.KeyEvent(0,0,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_V,0,android.view.KeyEvent.META_CTRL_ON))
            ins.sendKeySync(android.view.KeyEvent(0,0,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_V,0,android.view.KeyEvent.META_CTRL_ON))
            waitFor("paste in iframe") { report().optString("text").contains("mlon=116.654321") }
            var opened: Uri? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action == Intent.ACTION_VIEW) {
                        opened = intent.data
                        return Instrumentation.ActivityResult(0,null)
                    }
                    return null
                }
            }
            ins.addMonitor(monitor)
            try {
                tapRow(3)
                waitFor("open map reply") { opened?.host == "www.openstreetmap.org" }
            } finally { ins.removeMonitor(monitor) }
        } finally {
            mgr.removeTestProvider(gps)
            shell("appops set " + context.packageName + " android:mock_location default")
        }
    }
}
