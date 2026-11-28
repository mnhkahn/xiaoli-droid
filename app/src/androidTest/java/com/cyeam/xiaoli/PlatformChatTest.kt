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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
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

    @Test fun authorizationOpensOnlyTheAppChatAcrossBothAccounts() {
        val imageLoader = coil.ImageLoader.Builder(app).okHttpClient(client).build()
        coil.Coil.setImageLoader(imageLoader)
        val active = java.util.concurrent.atomic.AtomicReference(Brand.FEISHU)
        val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String,String>>()
        val launched = java.util.concurrent.CopyOnWriteArrayList<Pair<Brand, Boolean>>()
        fun avatar(color: Int, initial: String): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color = android.graphics.Color.WHITE
                textSize = 46f
                textAlign = android.graphics.Paint.Align.CENTER
            }
            android.graphics.Canvas(bitmap).drawText(initial, 48f, 64f, paint)
            return ByteArrayOutputStream().apply { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
        }
        val botColor = android.graphics.Color.rgb(69, 117, 112)
        val userColor = android.graphics.Color.rgb(83, 113, 163)
        val images = mapOf("/bot.png" to avatar(botColor, "李"), "/user.png" to avatar(userColor, "嘉"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val brand = active.get()
                images[request.requestUrl!!.encodedPath]?.let {
                    if (request.getHeader("Authorization") != null) return MockResponse().setResponseCode(403)
                    return MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(it))
                }
                fun approval(issuer: Brand) = json("device_code" to "dev", "user_code" to "ABCD", "interval" to 1,
                    "verification_uri_complete" to "https://open.${issuer.domain}/page/launcher")
                val step = when (request.requestUrl!!.encodedPath) {
                    "/oauth/v1/app/registration" -> when {
                        request.body.readUtf8().contains("action=begin") -> approval(Brand.FEISHU)
                        (brand to true) !in launched -> json("error" to "authorization_pending")
                        else -> json("client_id" to "id", "client_secret" to "secret", "user_info" to json("tenant_brand" to brand.name.lowercase()))
                    }
                    "/oauth/v1/device_authorization" -> approval(brand).put("verification_uri_complete", "https://accounts.${brand.domain}/activate?user_code=ABCD")
                    "/oauth/v3/token" -> if ((brand to false) in launched) json("access_token" to brand.name, "refresh_token" to "test-refresh")
                        else json("error" to "authorization_pending")
                    "/open-apis/auth/v3/tenant_access_token/internal" -> json("code" to 0, "tenant_access_token" to "tat")
                    "/open-apis/application/v6/applications/id" -> json("code" to 0, "data" to json("app" to json("online_version_id" to "")))
                    "/open-apis/bot/v3/info" -> json("code" to 0, "bot" to json("open_id" to "ou_agent", "app_name" to "李老师",
                        "avatar_url" to if (brand == Brand.LARK) server.url("/bot.png").toString() else ""))
                    "/open-apis/authen/v1/user_info" -> if (brand == Brand.FEISHU) json("code" to 99991672, "msg" to "unavailable")
                        else json("code" to 0, "data" to json("name" to "嘉嘉", "avatar_thumb" to server.url("/user.png").toString()))
                    else -> null
                }
                if (step != null) return MockResponse().setBody(step.toString())
                val token = request.getHeader("Authorization")!!.removePrefix("Bearer ")
                val peerId = "oc_$token"
                val url = request.requestUrl!!
                return when {
                    url.encodedPath.endsWith("/chats") -> success(json("items" to JSONArray()
                        .put(json("name" to "Admin Assistant", "chat_id" to "oc_admin", "chat_mode" to "p2p",
                            "p2p_target_type" to "bot", "p2p_target_id" to "ou_admin"))
                        .put(json("name" to "李老师", "chat_id" to "oc_contact", "chat_mode" to "p2p",
                            "p2p_target_type" to "user", "p2p_target_id" to "ou_contact"))
                        .put(json("name" to "李老师", "chat_id" to peerId, "chat_mode" to "p2p",
                            "p2p_target_type" to "bot", "p2p_target_id" to "ou_agent"))
                        .put(json("name" to "群聊", "chat_id" to "oc_group", "chat_mode" to "group"))))
                    url.encodedPath.endsWith("/reactions/batch_query") -> success(json("success_msg_reaction_counts" to JSONArray()))
                    request.method == "POST" -> {
                        val body = JSONObject(request.body.readUtf8())
                        sent.add(token to body.getString("receive_id"))
                        success(JSONObject())
                    }
                    url.queryParameter("container_id") == peerId -> {
                        fun message(id: String, sender: String, text: String) = json("message_id" to id, "chat_id" to peerId,
                            "msg_type" to "text", "sender" to json("sender_type" to sender),
                            "body" to json("content" to json("text" to text).toString()))
                        success(json("items" to JSONArray()
                            .put(message("om_2", "app", "可以，我们先从第一道题开始。"))
                            .put(message("om_1", "user", "李老师，今天的作业可以一起看看吗？"))))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        lateinit var model: ChatModel
        ui.activity.runOnUiThread { model = ChatModel(app, ::api) }
        ui.setContent { MaterialTheme { ChatScreen(model, Brand.entries, onCheckUpdate = {}) { _, brand, ticket ->
            val inner = android.net.Uri.parse(requireNotNull(approvalIntent(brand, ticket).data?.getQueryParameter("url")))
            assertFalse("createOnly" in inner.queryParameterNames)
            assertEquals(ticket.code, inner.getQueryParameter("user_code"))
            launched.add(brand to ticket.registration)
        } } }
        for (brand in Brand.entries) {
            active.set(brand)
            ui.onNodeWithText("使用${brand.title}登录").performClick()
            waitText("请确认授权小李读取你与这个智能体的单聊，并以你的身份发消息。")
            ui.onNodeWithText("打开${brand.title}确认").performClick()
            waitText("可以，我们先从第一道题开始。")
            ui.onNodeWithText("李老师").assertExists()
            ui.onNodeWithText("Admin Assistant").assertDoesNotExist()
            ui.onNodeWithText("选择单聊").assertDoesNotExist()
            val botAvatar = ui.onNodeWithContentDescription("李老师的头像")
            val selfAvatar = ui.onNodeWithContentDescription("我的头像")
            botAvatar.assertIsDisplayed()
            selfAvatar.assertIsDisplayed()
            assertTrue(botAvatar.getUnclippedBoundsInRoot().right < selfAvatar.getUnclippedBoundsInRoot().left)
            if (brand == Brand.LARK) {
                fun shown(node: SemanticsNodeInteraction, color: Int): Boolean {
                    val pixels = node.captureToImage().toPixelMap()
                    return pixels[pixels.width / 2, pixels.height / 6].toArgb() == color
                }
                ui.waitUntil(10_000) { shown(botAvatar, botColor) && shown(selfAvatar, userColor) }
                if (InstrumentationRegistry.getArguments().getString("captureChat") == "true") {
                    val screen = ui.onRoot().captureToImage().asAndroidBitmap()
                    File(app.cacheDir, "chat-preview.png").outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            ui.onNodeWithContentDescription("发送").assertIsNotEnabled()
            ui.onNodeWithContentDescription("展开输入框").performClick()
            ui.onNodeWithContentDescription("收起输入框").performClick()
            ui.onNodeWithContentDescription("更多附件").performClick()
            ui.onNodeWithText("文件或文档").assertIsDisplayed()
            ui.onNodeWithText("当前位置").assertIsDisplayed()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.onNode(hasSetTextAction()).performClick().performTextInput("test")
            ui.onNodeWithContentDescription("表情").performClick()
            ui.onNodeWithText("👍").performClick()
            ui.runOnIdle { assertEquals("test👍", model.draft) }
            ui.onNodeWithContentDescription("发送").assertIsDisplayed().performClick()
            ui.waitUntil(10000) { sent.contains(brand.name to "oc_${brand.name}") && !model.sending }
            ui.onNodeWithContentDescription("更多").performClick()
            ui.onNodeWithText("切换平台").performClick()
        }
        ui.runOnIdle { model.home() }
        assertEquals(2, sent.size)
        assertEquals(Brand.entries.flatMap { listOf(it to true, it to false) }, launched.toList())
        imageLoader.shutdown()
        coil.Coil.reset()
    }

    @Test fun publishedMenuSendsOnlyToBoundChatAndRefreshesWithoutChangingDraft() {
        val store = ImStore(app)
        val generation = java.util.concurrent.atomic.AtomicInteger(0)
        val sent = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, String>>()
        val args = InstrumentationRegistry.getArguments()
        // An optional sanitized platform menu exercises the same UI against a real configuration.
        val fixture = args.getString("menuFixture")?.let {
            JSONArray(String(android.util.Base64.decode(it, android.util.Base64.DEFAULT), Charsets.UTF_8))
        }
        fun rows(brand: Brand): JSONArray {
            if (fixture != null && brand == Brand.LARK) return fixture
            return JSONArray().put(json("menu_id" to "g", "default_name" to "工具 ${brand.title}", "menu_content_type" to 3))
                .put(json("menu_id" to "c", "parent_menu_id" to "g", "default_name" to "/lookup ${brand.name}", "menu_content_type" to 4))
        }
        for (brand in Brand.entries) store.write(brand, LoginState(AppKeys(brand.name, "secret"),
            UserToken("user-${brand.name}", "refresh", System.currentTimeMillis() + 3_600_000), Peer("oc_${brand.name}", "李老师")))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val bearer = request.getHeader("Authorization").orEmpty().removePrefix("Bearer ")
                fun raw(value: JSONObject) = MockResponse().setBody(value.toString())
                if (path.endsWith("/tenant_access_token/internal")) {
                    val appId = JSONObject(request.body.readUtf8()).getString("app_id")
                    return raw(json("code" to 0, "tenant_access_token" to "app-$appId"))
                }
                if (path == "/open-apis/bot/v3/info") return raw(json("code" to 0, "bot" to json("app_name" to "李老师", "open_id" to "ou_bot")))
                if (path == "/open-apis/authen/v1/user_info") return success(json("name" to "我"))
                if (path.startsWith("/open-apis/application/")) {
                    val brand = Brand.entries.find { bearer == "app-${it.name}" } ?: return MockResponse().setResponseCode(401)
                    val root = "/open-apis/application/v6/applications/${brand.name}"
                    val revision = generation.get()
                    if (revision == 3) return raw(json("code" to 99991672, "msg" to "permission denied"))
                    if (path == root) return success(json("app" to json("online_version_id" to "online$revision", "unaudit_version_id" to "draft")))
                    if (path != "$root/app_versions/online$revision") return MockResponse().setResponseCode(404)
                    val entries = if (revision == 0) rows(brand) else JSONArray().put(json("menu_id" to "new",
                        "default_name" to "/新版", "menu_content_type" to 4))
                    return success(json("app_version" to json("app_id" to brand.name, "version_id" to "online$revision",
                        "ability" to json("bot" to json("bot_menu_enable" to (revision != 2), "bot_menus" to entries)))))
                }
                if (!path.endsWith("/im/v1/messages")) return MockResponse().setResponseCode(404)
                if (request.method == "POST") {
                    val body = JSONObject(request.body.readUtf8())
                    if (body.optString("msg_type") != "text" || request.requestUrl!!.queryParameter("receive_id_type") != "chat_id")
                        return MockResponse().setResponseCode(400)
                    sent.add(Triple(bearer, body.getString("receive_id"), JSONObject(body.getString("content")).getString("text")))
                }
                return success(json("items" to JSONArray()))
            }
        }
        val model = ui.runOnUiThread { ChatModel(app, ::api) }
        ui.setContent { MaterialTheme { ChatScreen(model, Brand.entries, onCheckUpdate = {}) } }
        fun refresh() {
            ui.onNodeWithContentDescription("更多").performClick()
            ui.onNodeWithText("刷新快捷菜单").performClick()
        }
        try {
            for (brand in Brand.entries) {
                generation.set(0)
                ui.runOnIdle { model.enter(brand, restore = true); model.edit("未发送的草稿") }
                val entries = rows(brand).let { list -> (0 until list.length()).map(list::getJSONObject) }
                val groups = entries.filter { it.getInt("menu_content_type") == 3 }
                var capture = brand == Brand.LARK && args.getString("captureChat") == "true"
                for (group in groups) {
                    val label = group.getString("default_name")
                    waitText(label)
                    for (command in entries.filter { it.optString("parent_menu_id") == group.getString("menu_id") && it.getInt("menu_content_type") == 4 }) {
                        val text = command.getString("default_name")
                        val count = sent.size
                        ui.onNodeWithText(label).performClick()
                        ui.onNodeWithText(text).assertIsDisplayed()
                        if (capture) {
                            capture = false
                            val shot = instrumentation.uiAutomation.takeScreenshot()
                            File(app.cacheDir, "menu-preview.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                            shot.recycle()
                        }
                        ui.onNodeWithText(text).performClick()
                        ui.waitUntil(10_000) { sent.size == count + 1 && !model.sending }
                        assertEquals(Triple("user-${brand.name}", "oc_${brand.name}", text), sent.last())
                        ui.runOnIdle { assertEquals("未发送的草稿", model.draft) }
                    }
                }
                generation.set(1)
                refresh()
                waitText("/新版")
                ui.onNodeWithText(groups.first().getString("default_name")).assertDoesNotExist()
                ui.onNodeWithText("/新版").performClick()
                ui.waitUntil(10_000) { sent.last() == Triple("user-${brand.name}", "oc_${brand.name}", "/新版") && !model.sending }
                generation.set(2)
                refresh()
                ui.waitUntil(10_000) { model.menu.isEmpty() }
                ui.onNodeWithText("/新版").assertDoesNotExist()
                generation.set(3)
                refresh()
                waitText("应用缺少读取快捷菜单的权限 · 重试")
                ui.onNodeWithContentDescription("发送").assertIsEnabled()
                generation.set(1)
                ui.onNodeWithText("应用缺少读取快捷菜单的权限 · 重试").performClick()
                waitText("/新版")
            }
        } finally { ui.runOnIdle { model.home() } }
    }

    private fun existingConversation(): ChatModel {
        val store = ImStore(app)
        store.write(Brand.LARK, LoginState(AppKeys("app", "test"), UserToken("uat", "refresh",
            System.currentTimeMillis() + 3_600_000), Peer("oc_agent", "李老师")))
        store.active = Brand.LARK
        lateinit var model: ChatModel
        ui.runOnIdle { model = ChatModel(app, ::api) }
        ui.setContent { MaterialTheme { ChatScreen(model, listOf(Brand.LARK), onCheckUpdate = {}) } }
        return model
    }

    @Test fun historyShowsCardPagesAndLoadsBothOlderImagesInline() {
        val headers = JSONArray().put(json("name" to "c0", "display_name" to "作业"))
            .put(json("name" to "c1", "display_name" to "状态"))
        val rows = JSONArray().apply { for (i in 1..8) put(json("c0" to "练习 $i", "c1" to if (i < 3) "待完成" else "已完成")) }
        val summary = "今天一共有 8 项作业，还有 2 项没完成，另外 6 项已经完成了。"
        val card = json("schema" to "2.0", "header" to json("title" to json("content" to "今天的作业情况")),
            "body" to json("elements" to JSONArray().put(json("tag" to "column_set", "columns" to JSONArray().put(
                json("tag" to "column", "elements" to JSONArray().put(json("tag" to "markdown", "content" to summary))))))
                .put(json("tag" to "table", "page_size" to 5, "columns" to headers, "rows" to rows))))
        fun item(id: String, type: String, content: JSONObject) = json("message_id" to id, "msg_type" to type,
            "chat_id" to "oc_agent", "sender" to json("sender_type" to "app"), "body" to json("content" to content.toString()))
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val imageAvailable = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (request.getHeader("Authorization") != "Bearer uat") return MockResponse().setResponseCode(403)
                return when {
                    url.encodedPath.endsWith("/im/v1/messages") && url.queryParameter("card_msg_content_type") == "user_card_content" ->
                        if (url.queryParameter("page_token") == null) success(json("has_more" to true, "page_token" to "older",
                            "items" to JSONArray().put(item("om_card","interactive",card))))
                        else success(json("items" to JSONArray().put(item("om_photo","image",json("image_key" to "img_one")))
                            .put(item("om_post","post",json("title" to "作业图", "content" to JSONArray().put(JSONArray()
                                .put(json("tag" to "img", "image_key" to "img_two"))))))))
                    url.encodedPath.endsWith("/resources/img_one") || url.encodedPath.endsWith("/resources/img_two") -> {
                        requests.add(url.encodedPath)
                        if (url.encodedPath.endsWith("/img_two") && !imageAvailable.get())
                            return MockResponse().setResponseCode(503)
                        if (url.queryParameter("type") != "image") return MockResponse().setResponseCode(400)
                        val bytes = instrumentation.context.assets.open("media/sample.png").use { it.readBytes() }
                        MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes))
                    }
                    url.encodedPath.endsWith("/reactions/batch_query") -> success(JSONObject())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val model = existingConversation()
        try {
            waitText(summary)
            ui.onNodeWithText("今天的作业情况").assertIsDisplayed()
            ui.onNodeWithText("练习 5").assertExists()
            ui.onNodeWithText("下一页").performClick()
            ui.onNodeWithText("2/2").assertIsDisplayed()
            ui.onNodeWithText("练习 8").assertIsDisplayed()
            ui.onNodeWithText("上一页").performClick()
            ui.onNodeWithText("1/2").assertExists()
            ui.onNodeWithTag("chat-history").performScrollToNode(hasText("查看更早的消息"))
            ui.onNodeWithText("查看更早的消息").performClick()
            ui.waitUntil(10_000) { model.messages.size == 3 }
            for ((id, key) in listOf("om_photo" to "img_one", "om_post" to "img_two")) {
                ui.onNodeWithTag("chat-history").performScrollToKey(id)
                ui.waitUntil(10_000) { requests.contains("/open-apis/im/v1/messages/$id/resources/$key") }
                if (key == "img_two") {
                    val retry = "重新加载图片 · 附件下载失败（HTTP 503）"
                    waitText(retry)
                    imageAvailable.set(true)
                    ui.onNodeWithText(retry).performScrollTo().performClick()
                }
                val node = ui.onNodeWithTag("image-$id-$key")
                ui.waitUntil(10_000) { ui.onAllNodesWithTag("image-$id-$key").fetchSemanticsNodes().isNotEmpty() }
                node.performScrollTo().assertIsDisplayed().performClick()
                waitText("关闭")
                ui.onNodeWithText("关闭").performClick()
            }
        } finally { ui.runOnIdle { model.home() } }
    }

    @Test fun botReactionAppearsAndDisappearsAndUsersCanToggleTheirOwn() {
        val working = java.util.concurrent.atomic.AtomicBoolean(false)
        val liked = java.util.concurrent.atomic.AtomicBoolean(false)
        val requestCount = java.util.concurrent.atomic.AtomicInteger()
        val heart = json("operator" to json("operator_type" to "user", "operator_id" to "ou_self"),
            "reaction_id" to "mine/+=", "emoji_type" to "HEART")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer uat") return MockResponse().setResponseCode(403)
                val path = request.requestUrl!!.encodedPath
                return when {
                    path.endsWith("/user_info") -> success(json("name" to "我", "open_id" to "ou_self"))
                    path.endsWith("/im/v1/messages") -> success(json("items" to JSONArray().put(json(
                        "message_id" to "om_work", "msg_type" to "text", "sender" to json("sender_type" to "user"),
                        "body" to json("content" to json("text" to "今天还有啥作业没完成？").toString())))))
                    path.endsWith("/reactions/batch_query") -> {
                        val counts = JSONArray()
                        if (working.get()) counts.put(json("reaction_type" to "Typing", "count" to "1"))
                        if (liked.get()) counts.put(json("reaction_type" to "HEART", "count" to "1"))
                        requestCount.incrementAndGet()
                        success(json("success_msg_reaction_counts" to JSONArray().put(json("message_id" to "om_work", "reaction_count" to counts)),
                            "success_msg_reaction_details" to JSONArray().put(json("message_id" to "om_work", "message_reaction_items" to
                                if (liked.get()) JSONArray().put(heart) else JSONArray()))))
                    }
                    path.endsWith("/om_work/reactions") && request.method == "GET" ->
                        success(json("items" to if (liked.get()) JSONArray().put(heart) else JSONArray()))
                    path.endsWith("/om_work/reactions") && request.method == "POST" -> {
                        if (JSONObject(request.body.readUtf8()).getJSONObject("reaction_type").getString("emoji_type") != "HEART")
                            return MockResponse().setResponseCode(400)
                        liked.set(true); success(heart)
                    }
                    request.method == "DELETE" && request.requestUrl!!.pathSegments.last() == "mine/+=" -> {
                        liked.set(false); success(JSONObject())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val model = existingConversation()
        try {
            waitText("今天还有啥作业没完成？")
            ui.waitUntil(8000) { requestCount.get() > 0 }
            working.set(true); waitText("⌨️ 输入中 1")
            working.set(false)
            ui.waitUntil(8000) { ui.onAllNodesWithText("⌨️ 输入中 1").fetchSemanticsNodes().isEmpty() }
            ui.onNodeWithContentDescription("回复表情").performClick()
            ui.onNodeWithText("❤️").performClick()
            waitText("❤️ 1")
            ui.onNodeWithText("❤️ 1").performClick()
            ui.waitUntil(8000) { !liked.get() && ui.onAllNodesWithText("❤️ 1").fetchSemanticsNodes().isEmpty() }
        } finally { ui.runOnIdle { model.home() } }
    }

    @Test fun upgradeKeepsLoginAndReplacesLegacyManualChatOnlyAfterMatchingTheBot() {
        val brand = Brand.LARK
        val store = ImStore(app)
        val token = UserToken("existing-user", "refresh", System.currentTimeMillis() + 3_600_000)
        val keys = AppKeys("cli_agent", "test-secret")
        // Seed the encrypted record in the format shipped in 0.0.13.
        store.write(brand, LoginState(keys, token))
        val legacy = json("app" to json("id" to keys.id, "secret" to keys.secret),
            "user" to json("access" to token.access, "refresh" to token.refresh, "until" to token.until),
            "chat" to json("name" to "李老师", "id" to "oc_wrong"))
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, keyStore.getKey("xiaoli-open-platform", null))
        }
        val record = android.util.Base64.encodeToString(cipher.iv + cipher.doFinal(legacy.toString().toByteArray()), android.util.Base64.NO_WRAP)
        app.getSharedPreferences("open-platform", Context.MODE_PRIVATE).edit().putString(brand.name, record).commit()
        store.active = brand
        val history = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ready = java.util.concurrent.atomic.AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/open-apis/auth/v3/tenant_access_token/internal" -> MockResponse().setBody("""{"code":0,"tenant_access_token":"tat"}""")
                    "/open-apis/bot/v3/info" -> MockResponse().setBody("""{"code":0,"bot":{"open_id":"ou_agent","app_name":"李老师"}}""")
                    "/open-apis/im/v1/chats" -> {
                        val rows = JSONArray().put(json("chat_id" to "oc_wrong", "chat_mode" to "p2p", "name" to "李老师",
                            "p2p_target_type" to "bot", "p2p_target_id" to "ou_someone_else"))
                        if (ready.get()) rows.put(json("chat_id" to "oc_right", "chat_mode" to "p2p", "name" to "李老师",
                            "p2p_target_type" to "bot", "p2p_target_id" to "ou_agent"))
                        success(json("items" to rows))
                    }
                    "/open-apis/im/v1/messages" -> {
                        history.add(url.queryParameter("container_id").orEmpty())
                        success(json("items" to JSONArray()))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val model = ui.runOnUiThread { ChatModel(app, ::api) }
        ui.setContent { ChatScreen(model, listOf(brand), onCheckUpdate = {}) }
        waitText("重新连接")
        ui.onNodeWithContentDescription("发送").assertDoesNotExist()
        assertNull(store.read(brand).peer)
        assertEquals(token, store.read(brand).token)
        assertTrue(history.isEmpty())
        ready.set(true)
        ui.onNodeWithText("重新连接").performClick()
        waitText("李老师")
        ui.waitUntil(10_000) { history.isNotEmpty() }
        assertEquals(setOf("oc_right"), history.toSet())
        val restored = ui.runOnUiThread { ChatModel(app, ::api) }
        ui.runOnIdle {
            assertEquals(Peer("oc_right", "李老师"), restored.selected)
            restored.home()
            model.home()
        }
    }

    @Test fun loginOnlyOffersInstalledClients() {
        val clients = androidx.compose.runtime.mutableStateOf(emptyList<Brand>())
        val model = ui.runOnUiThread { ChatModel(app, ::api) }
        ui.setContent { ChatScreen(model, clients.value, onCheckUpdate = {}) }
        ui.onNodeWithText("请先安装或启用 Lark 或飞书，再返回小李登录。").assertExists()
        for (available in listOf(emptyList(), listOf(Brand.LARK), listOf(Brand.FEISHU), Brand.entries)) {
            ui.runOnIdle { clients.value = available }
            for (brand in Brand.entries) {
                val login = ui.onNodeWithText("使用${brand.title}登录")
                if (brand in available) login.assertExists() else login.assertDoesNotExist()
            }
        }
    }

    @Test fun handoffTargetsTheSelectedClientAndPreservesTheOfficialUrl() {
        for (brand in Brand.entries) {
            val url = "https://open.${brand.domain}/page/launcher?user_code=A-B&extra=%2B%26%3D"
            val ticket = Approval(url, "A-B", true, "device", Long.MAX_VALUE, 1)
            val client = approvalIntent(brand, ticket)
            val link = requireNotNull(client.data)
            assertEquals(android.content.Intent.ACTION_VIEW, client.action)
            assertTrue(client.hasCategory(android.content.Intent.CATEGORY_BROWSABLE))
            assertEquals(brand.clientPackage, client.`package`)
            assertEquals(brand.appScheme, link.scheme)
            assertEquals("applink.${brand.domain}", link.host)
            assertEquals("/client/web_url/open", link.path)
            assertEquals(url, link.getQueryParameter("url"))
            assertNull(client.selector)
            val supplied = "https://applink.${brand.domain}/client/example?extra=%2B%26"
            val direct = approvalIntent(brand, ticket.copy(url = supplied))
            assertEquals(supplied.replaceFirst("https:", "${brand.appScheme}:"), direct.dataString)
        }
    }

    @Test fun renewPendingUserAuthorizationKeepsTheRegisteredAppAndReplacesTheCode() {
        val store = ImStore(app)
        val brand = Brand.LARK
        val original = Approval("https://accounts.larksuite.com/activate?user_code=OLD", "OLD", false,
            "old-device", System.currentTimeMillis() + 60_000, 5)
        val keys = AppKeys("cli_existing", "secret")
        store.write(brand, LoginState(keys = keys, pending = original))
        store.active = brand
        val enrollments = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.requestUrl!!.encodedPath != "/oauth/v1/device_authorization")
                    return MockResponse().setResponseCode(400).setBody(json("error" to "authorization_pending").toString())
                enrollments.incrementAndGet()
                return MockResponse().setBody(json("device_code" to "new-device", "user_code" to "NEW", "interval" to 5,
                    "verification_uri_complete" to "https://accounts.larksuite.com/activate?user_code=NEW").toString())
            }
        }
        lateinit var model: ChatModel
        ui.activity.runOnUiThread { model = ChatModel(app, ::api) }
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        ui.setContent { MaterialTheme { ChatScreen(model, listOf(brand), onCheckUpdate = {}) { _, _, ticket ->
            opened.add(ticket.code)
            if (ticket.code == "OLD") throw android.content.ActivityNotFoundException()
        } } }
        try {
            waitText("确认码  OLD")
            assertEquals(0, enrollments.get())
            ui.onNodeWithText("打开Lark确认").performClick()
            waitText("无法打开Lark，请确认客户端已启用并更新到最新版本。")
            assertEquals(listOf("OLD"), opened.toList())
            ui.onNodeWithText("用浏览器打开").assertDoesNotExist()
            ui.onNodeWithText("获取新链接").performClick()
            waitText("确认码  NEW")
            ui.waitUntil(10000) { opened.toList() == listOf("OLD", "NEW") }
            assertEquals(1, enrollments.get())
            val saved = store.read(brand)
            assertEquals(keys, saved.keys)
            assertEquals("new-device", saved.pending?.device)
        } finally { ui.runOnIdle { model.home() } }
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
        val discovered = LoginState(pending = Approval("https://open.larksuite.com/page/launcher?user_code=CODE", "CODE", true, "device-l", expiry, 5, Brand.LARK))
        store.write(Brand.LARK, discovered)
        assertEquals(discovered, restored.read(Brand.LARK))
        val prefs = app.getSharedPreferences("open-platform", Context.MODE_PRIVATE).all.values.joinToString()
        for (value in listOf("secret-f","user-f","oc_f","device-l","secret-l")) assertFalse(prefs.contains(value))
    }

    private fun roundTrip(brand: Brand, type: String, name: String, mime: String, bytes: ByteArray, duration: Long = 0) {
        val resolver = app.contentResolver
        val input = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${UUID.randomUUID()}-$name")
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
