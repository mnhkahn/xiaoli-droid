package com.cyeam.xiaoli

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Proxy
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

class PlatformChatTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var base: HttpUrl
    private fun api(brand: Brand) = ImApi(brand, base, base, client)
    private fun success(body: JSONObject) = MockResponse().setBody(json("code" to 0, "data" to body).toString())
    private fun waitText(text: String) = ui.waitUntil(15_000) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Before fun prepare() {
        app.getSharedPreferences("open-platform", Context.MODE_PRIVATE).edit().clear().commit()
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val keys = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(keys.sslSocketFactory(), false)
        server.start()
        base = server.url("/")
        client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).proxy(Proxy.NO_PROXY).build()
    }
    @After fun cleanup() {
        server.shutdown()
        app.getSharedPreferences("open-platform", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun chooseSingleChatAcrossBothAccounts() {
        val active = java.util.concurrent.atomic.AtomicReference(Brand.FEISHU)
        val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String,String>>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val brand = active.get()
                fun approval() = json("device_code" to "dev", "user_code" to "ABCD", "interval" to 1,
                    "verification_uri_complete" to "https://open.${brand.domain}/page/launcher")
                val step = when (request.requestUrl!!.encodedPath) {
                    "/oauth/v1/app/registration" -> if (request.body.readUtf8().contains("action=begin")) approval()
                        else json("client_id" to "id", "client_secret" to "secret")
                    "/oauth/v1/device_authorization" -> approval()
                    "/oauth/v3/token" -> json("access_token" to brand.name, "refresh_token" to "test-refresh")
                    else -> null
                }
                if (step != null) return MockResponse().setBody(step.toString())
                val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
                val peerId = "oc_$token"
                val url = request.requestUrl!!
                return when {
                    url.encodedPath.endsWith("/chats") -> success(json("items" to JSONArray()
                        .put(json("name" to "智能体$token", "chat_id" to peerId, "chat_mode" to "p2p"))
                        .put(json("name" to "群聊", "chat_id" to "oc_group", "chat_mode" to "group"))))
                    request.method == "POST" -> {
                        val body = JSONObject(request.body.readUtf8())
                        sent.add(token to body.getString("receive_id"))
                        success(JSONObject())
                    }
                    url.queryParameter("container_id") == peerId -> success(json("items" to JSONArray()
                        .put(json("message_id" to "om_1", "chat_id" to peerId, "msg_type" to "text",
                            "body" to json("content" to json("text" to "回复$token").toString())))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        lateinit var model: ChatModel
        ui.activity.runOnUiThread { model = ChatModel(app, ::api) }
        val launched = java.util.concurrent.CopyOnWriteArrayList<Brand>()
        ui.setContent { MaterialTheme { ChatScreen(model) { _, brand, _, _ -> launched.add(brand) } } }
        for (brand in Brand.entries) {
            active.set(brand)
            ui.onNodeWithText("使用${brand.title}登录").performClick()
            waitText("前往${brand.title}确认")
            waitText("智能体${brand.name}")
            ui.onNodeWithText("群聊").assertDoesNotExist()
            ui.onNodeWithText("智能体${brand.name}").performClick()
            waitText("回复${brand.name}")
            ui.onNode(hasSetTextAction()).performTextInput("test")
            ui.onNodeWithText("发送").performClick()
            ui.waitUntil(10000) { sent.contains(brand.name to "oc_${brand.name}") && !model.sending }
            ui.onNodeWithText("切换会话").performClick()
            waitText("智能体${brand.name}")
            ui.onNodeWithText("切换平台").performClick()
        }
        ui.runOnIdle { model.home() }
        assertEquals(2, sent.size)
        assertEquals(Brand.entries.toList(), launched.toList())
    }

    @Test fun isolateAndRestoreEncryptedAccountAndPendingLogin() {
        val store = ImStore(app)
        val expiry = System.currentTimeMillis() + 100_000
        val first = LoginState(AppKeys("cli1","secret-f"), UserToken("user-f","refresh-f",expiry), Peer("oc_f","飞书智能体"))
        val second = LoginState(keys = AppKeys("cli2","secret-l"), pending = Approval("https://open.larksuite.com/page/launcher", "CODE", false, "device-l", expiry, 5))
        store.write(Brand.FEISHU,first)
        store.write(Brand.LARK,second)
        val restored = ImStore(app)
        assertEquals(first, restored.read(Brand.FEISHU))
        assertEquals(second, restored.read(Brand.LARK))
        val prefs = app.getSharedPreferences("open-platform", Context.MODE_PRIVATE).all.values.joinToString()
        for (value in listOf("secret-f","user-f","oc_f","device-l","secret-l")) assertFalse(prefs.contains(value))
    }

    private fun roundTrip(brand: Brand, type: String, name: String, mime: String, bytes: ByteArray, duration: Long = 0) {
        val resolver = app.contentResolver
        val input = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE,mime)
        })!!
        try {
            resolver.openOutputStream(input)!!.use { it.write(bytes) }
            val field = if (type == "image") "image" else "file"
            val key = "${field}_key"
            val value = "${field}_test"
            val pipe = api(brand)
            server.enqueue(success(json(key to value)))
            val content = pipe.upload("uat", name, fileBody(app, input), type, duration)
            val upload = server.takeRequest()
            assertEquals("/open-apis/im/v1/${field}s", upload.requestUrl!!.encodedPath)
            assertTrue(upload.body.indexOf(okio.ByteString.of(*bytes)) >= 0)
            if (type == "audio") assertTrue(upload.body.clone().readUtf8().contains("opus"))
            server.enqueue(success(JSONObject()))
            val peer = Peer("oc_target","智能体")
            pipe.send("uat", peer, type, content, UUID.randomUUID().toString())
            val posted = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(peer.id, posted.getString("receive_id"))
            assertEquals(value, JSONObject(posted.getString("content")).getString(key))
            val reply = decodeMessage(json("message_id" to "om_reply", "msg_type" to type,
                "body" to json("content" to content.put("file_name", name).toString())))
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/download/test"))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val output = ByteArrayOutputStream()
            pipe.download("uat", reply, output)
            assertArrayEquals(bytes, output.toByteArray())
            val req = server.takeRequest()
            assertEquals("/open-apis/im/v1/messages/om_reply/resources/$value", req.requestUrl!!.encodedPath)
            assertEquals(field, req.requestUrl!!.queryParameter("type"))
            assertNull(server.takeRequest().getHeader("Authorization"))
        } finally { resolver.delete(input, null, null) }
    }

    @Test fun imagesFilesAndDocumentsBothWays() {
        val samples = mapOf("png" to "image/png", "jpg" to "image/jpeg", "zip" to "application/zip",
            "pdf" to "application/pdf", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "wav" to "audio/wav")
        for (brand in Brand.entries) for ((ext,mime) in samples) {
            val bytes = instrumentation.context.assets.open("media/sample.$ext").use { it.readBytes() }
            roundTrip(brand, if (mime.startsWith("image/")) "image" else "file", "sample.$ext", mime, bytes)
        }
    }

    @Test fun recordOpusSendAndReadPlayableReply() {
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val voice = VoiceRecording(app)
        var file: File? = null
        try {
            voice.start()
            SystemClock.sleep(1250)
            val (clip, duration) = voice.finish()
            file = clip
            val bytes = clip.readBytes()
            assertTrue(bytes.size > 64)
            assertEquals("OggS",String(bytes.copyOf(4)))
            for (brand in Brand.entries) roundTrip(brand,"audio","voice.ogg","audio/ogg",bytes,duration)
            val media = MediaMetadataRetriever()
            try {
                media.setDataSource(clip.path)
                assertTrue(media.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 500)
            } finally { media.release() }
        } finally { file?.delete(); voice.discard() }
    }

    @Suppress("DEPRECATION")
    @Test fun acquireLocationSendAndReadMapReply() = runBlocking {
        val automation = instrumentation.uiAutomation
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
            automation.grantRuntimePermission(app.packageName,permission)
        val mgr = app.getSystemService(LocationManager::class.java)
        val gps = LocationManager.GPS_PROVIDER
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
        shell("appops set ${app.packageName} android:mock_location allow")
        try {
            mgr.addTestProvider(gps,false,true,false,false,true,true,true,Criteria.POWER_LOW,Criteria.ACCURACY_FINE)
            mgr.setTestProviderEnabled(gps,true)
            val location = async(Dispatchers.Main) { getLocation(app) }
            repeat(15) {
                if (!location.isCompleted) {
                    delay(200)
                    mgr.setTestProviderLocation(gps,Location(gps).apply {
                        latitude=39.912345; longitude=116.412345; time=System.currentTimeMillis()
                        elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos(); accuracy=1f
                    })
                }
            }
            val point = requireNotNull(location.await())
            val text = locationText(point.latitude,point.longitude)
            assertTrue(text.contains("39.912345"))
            for (brand in Brand.entries) {
                server.enqueue(success(JSONObject()))
                api(brand).send("uat",Peer("oc_target","agent"),"text", json("text" to text),"map")
                assertTrue(JSONObject(server.takeRequest().body.readUtf8()).getString("content").contains("116.412345"))
                val incoming = decodeMessage(json("message_id" to "om_map","msg_type" to "location",
                    "body" to json("content" to json("latitude" to point.latitude,"longitude" to point.longitude).toString())))
                assertTrue(incoming.text.contains("https://www.openstreetmap.org/"))
            }
        } finally {
            runCatching { mgr.removeTestProvider(gps) }
            shell("appops set ${app.packageName} android:mock_location default")
        }
        Unit
    }
}
