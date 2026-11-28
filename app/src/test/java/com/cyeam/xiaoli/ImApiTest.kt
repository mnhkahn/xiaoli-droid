package com.cyeam.xiaoli

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImApiTest {
    private fun MockWebServer.reply(body: JSONObject, status: Int = 200) {
        enqueue(MockResponse().setResponseCode(status).setBody(body.toString()))
    }
    private fun begin(domain: String) = json("device_code" to "device", "user_code" to "ABCD",
        "expires_in" to 60, "interval" to 1, "verification_uri_complete" to "https://open.$domain/page/launcher?user_code=ABCD")

    @Test fun registrationUserConsentAndRefreshOnBothPlatforms() = runBlocking {
        for (brand in Brand.entries) MockWebServer().use { server ->
            server.start()
            val domain = brand.domain
            val login = ImLogin(ImApi(brand, server.url("/"), server.url("/")))
            val bootstrap = ImApi(Brand.FEISHU, server.url("/"), server.url("/"))
            server.reply(begin(Brand.FEISHU.domain))
            server.reply(json("error" to "authorization_pending"), 400)
            if (brand == Brand.LARK) server.reply(json("error" to "authorization_pending", "user_info" to json("tenant_brand" to "lark")), 400)
            server.reply(json("client_id" to "cli_a", "client_secret" to "secr", "user_info" to json("tenant_brand" to brand.name.lowercase())))
            val keys = login.register(bootstrap) { approval ->
                val link = approval.url.toHttpUrl()
                assertTrue(approval.registration)
                assertEquals("ABCD", link.queryParameter("user_code"))
                assertEquals("open.$domain", link.host)
                assertFalse("createOnly" in link.queryParameterNames)
                val encoded = Base64.getUrlDecoder().decode(link.queryParameter("addons"))
                val addons = JSONObject(GZIPInputStream(ByteArrayInputStream(encoded)).bufferedReader().readText())
                assertFalse(addons.getBoolean("preset"))
                assertTrue(addons.getJSONObject("scopes").getJSONArray("user").toString().contains("im:resource"))
                assertTrue(addons.getJSONObject("scopes").getJSONArray("user").toString().contains(taskScope))
                assertEquals("application:application:self_manage", addons.getJSONObject("scopes").getJSONArray("tenant").getString(0))
                assertFalse("application:application:self_manage" in userScopes)
            }
            assertEquals("/oauth/v1/app/registration", server.takeRequest().path)
            repeat(if (brand == Brand.LARK) 3 else 2) { assertTrue(server.takeRequest().body.readUtf8().contains("action=poll")) }
            server.reply(begin(domain))
            server.reply(json("access_token" to "access", "refresh_token" to "r1", "expires_in" to 100))
            val token = login.signIn(keys) { assertFalse(it.registration) }
            val auth = server.takeRequest()
            assertEquals("/oauth/v1/device_authorization", auth.path)
            assertTrue(auth.getHeader("Authorization")!!.startsWith("Basic "))
            val scopes = java.net.URLDecoder.decode(auth.body.readUtf8(), "UTF-8")
            assertTrue(scopes.contains("offline_access"))
            assertFalse(scopes.contains(taskScope))
            assertEquals("/oauth/v3/token", server.takeRequest().path)
            server.reply(json("access_token" to "next", "refresh_token" to "r2"))
            assertEquals("r2", login.refresh(keys, token).refresh)
            assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=r1"))
        }
    }

    @Test fun registrationUsesTheReturnedLifetimeAndCodeWithOnlyABaseUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = ImApi(Brand.FEISHU, server.url("/"), server.url("/"))
            server.reply(json("device_code" to "dev", "user_code" to "A-B", "expire_in" to 22, "interval" to 1,
                "verification_uri" to "https://open.feishu.cn/page/launcher?from=sdk"))
            server.reply(json("client_id" to "app", "client_secret" to "secret"))
            val before = System.currentTimeMillis()
            ImLogin(api).register(api) { ticket ->
                val link = ticket.url.toHttpUrl()
                assertEquals(listOf("A-B"), link.queryParameterValues("user_code"))
                assertEquals(listOf("sdk"), link.queryParameterValues("from"))
                assertTrue(ticket.until in (before + 22_000)..(System.currentTimeMillis() + 22_000))
            }
            Unit
        }
    }

    @Test fun legacyLarkTicketIsReplacedAndDiscoveryCanResumeOnLark() = runBlocking {
        MockWebServer().use { feishu -> MockWebServer().use { lark ->
            val bootstrap = ImApi(Brand.FEISHU, feishu.url("/"), feishu.url("/"))
            val login = ImLogin(ImApi(Brand.LARK, lark.url("/"), lark.url("/")))
            val old = Approval("https://open.larksuite.com/page/launcher?from=sdk", "OLD", true, "old-device", Long.MAX_VALUE, 1)
            val fresh = begin("feishu.cn").put("verification_uri_complete", "https://open.feishu.cn/page/launcher?from=sdk")
            feishu.reply(fresh)
            feishu.reply(json("error" to "authorization_pending", "user_info" to json("tenant_brand" to "lark")), 400)
            val issued = json("client_id" to "lark-app", "client_secret" to "secret")
            lark.reply(issued)
            val shown = mutableListOf<Approval>()
            assertEquals("lark-app", login.register(bootstrap, old) { shown.add(it) }.id)
            assertEquals(listOf(Brand.FEISHU, Brand.LARK), shown.map { it.registrationPollBrand })
            assertEquals("open.larksuite.com", shown.first().url.toHttpUrl().host)
            for (ticket in shown) assertEquals(listOf("ABCD"), ticket.url.toHttpUrl().queryParameterValues("user_code"))
            assertTrue(feishu.takeRequest().body.readUtf8().contains("action=begin"))
            assertEquals("action=poll&device_code=device", feishu.takeRequest().body.readUtf8())
            assertEquals("action=poll&device_code=device", lark.takeRequest().body.readUtf8())
            for (url in listOf(old.url, shown.last().url)) {
                lark.reply(issued)
                val restored = shown.last().copy(url = "$url&createOnly=true")
                login.register(bootstrap, restored) {
                    val link = it.url.toHttpUrl()
                    assertFalse("createOnly" in link.queryParameterNames)
                    assertEquals(listOf("ABCD"), link.queryParameterValues("user_code"))
                    assertEquals("sdk", link.queryParameter("from"))
                    assertEquals(restored.device, it.device)
                }
            }
            assertEquals(2, feishu.requestCount)
            assertEquals(3, lark.requestCount)
        } }
    }

    @Test fun launcherWithoutAnyRegistrationCodeNeverOpens() {
        MockWebServer().use { server ->
            val api = ImApi(Brand.FEISHU, server.url("/"), server.url("/"))
            server.reply(json("device_code" to "private", "verification_uri_complete" to "https://open.feishu.cn/page/launcher?from=sdk"))
            val error = assertThrows(OAuthFailure::class.java) {
                runBlocking { ImLogin(api).register(api) { fail("opened an unusable link") } }
            }
            assertEquals("invalid_response", error.errorCode)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun denyWrongPlatformAndRejectForeignUrls() {
        for (brand in Brand.entries) {
            val other = Brand.entries.first { it != brand }
            for (url in listOf("https://open.${other.domain}/", "https://open.${brand.domain}.invalid/",
                "http://open.${brand.domain}/", "https://open.${brand.domain}:4433/"))
                assertThrows(IllegalArgumentException::class.java) { brand.verificationUrl(url) }
            MockWebServer().use { server ->
                server.start()
                val login = ImLogin(ImApi(brand, server.url("/"), server.url("/")))
                val bootstrap = ImApi(Brand.FEISHU, server.url("/"), server.url("/"))
                server.reply(begin(Brand.FEISHU.domain))
                server.reply(json("error" to "access_denied"), 400)
                assertThrows(OAuthFailure::class.java) { runBlocking { login.register(bootstrap) {} } }
            }
        }
    }

    @Test fun findOnlyTheAuthorizedBotAcrossPagesOnBothPlatforms() {
        fun success(data: JSONObject) = json("code" to 0, "data" to data)
        fun row(id: String, mode: String, type: String, target: String) = json("chat_id" to id, "name" to "李老师",
            "chat_mode" to mode, "p2p_target_type" to type, "p2p_target_id" to target)
        for (brand in Brand.entries) MockWebServer().use { server ->
            server.start()
            val api = ImApi(brand, server.url("/"), server.url("/"))
            server.reply(json("code" to 0, "tenant_access_token" to "tat"))
            server.reply(json("code" to 0, "bot" to json("open_id" to "ou_agent", "app_name" to "李老师")))
            val bot = api.appBot(AppKeys("cli_agent", "test-secret"))
            val grant = server.takeRequest()
            assertEquals("/open-apis/auth/v3/tenant_access_token/internal", grant.path)
            assertEquals("cli_agent", JSONObject(grant.body.readUtf8()).getString("app_id"))
            val info = server.takeRequest()
            assertEquals("/open-apis/bot/v3/info", info.path)
            assertEquals("Bearer tat", info.getHeader("Authorization"))
            server.reply(success(json("items" to JSONArray()
                .put(row("oc_group","group","bot",bot.openId))
                .put(row("oc_user","p2p","user",bot.openId))
                .put(row("oc_other","p2p","bot","ou_other")), "has_more" to true, "page_token" to "page-2")))
            val target = if (brand == Brand.FEISHU) bot.openId else bot.appId
            server.reply(success(json("items" to JSONArray().put(row("oc_agent","p2p","bot",target)))))
            val peer = api.appChat("uat", bot)
            assertEquals(Peer("oc_agent", "李老师"), peer)
            val list = server.takeRequest()
            assertEquals("Bearer uat", list.getHeader("Authorization"))
            assertEquals("p2p", list.requestUrl!!.queryParameter("types"))
            assertEquals("open_id", list.requestUrl!!.queryParameter("user_id_type"))
            assertEquals("page-2", server.takeRequest().requestUrl!!.queryParameter("page_token"))
            server.reply(success(json("message_id" to "om_sent", "chat_id" to peer.id, "create_time" to "1700000000000",
                "msg_type" to "text", "body" to json("content" to json("text" to "你好").toString()))))
            val acknowledged = requireNotNull(api.send("uat", peer, "text", json("text" to "你好"), "unique"))
            assertEquals("om_sent", acknowledged.id)
            assertEquals("你好", acknowledged.text)
            assertEquals(Sender.USER, acknowledged.sender)
            assertEquals(1700000000000L, acknowledged.createdAt)
            val send = server.takeRequest()
            assertEquals("Bearer uat", send.getHeader("Authorization"))
            assertEquals("oc_agent", JSONObject(send.body.readUtf8()).getString("receive_id"))
            fun message(chat: String) = json("chat_id" to chat, "message_id" to "om_1", "msg_type" to "post",
                "body" to json("content" to """{"title":"答案","content":[[{"tag":"text","text":"你好"}]]}"""))
            server.reply(success(json("items" to JSONArray().put(message("oc_other")).put(message(peer.id)))))
            val messages = api.messages("uat", peer)
            assertEquals(1, messages.items.size)
            assertTrue(messages.items.single().text.contains("你好"))
            assertEquals(peer.id, server.takeRequest().requestUrl!!.queryParameter("container_id"))
        }
    }

    @Test fun refuseUnidentifiedAmbiguousAndIncompleteChatResults() {
        MockWebServer().use { server ->
            server.start()
            val api = ImApi(Brand.LARK, server.url("/"), server.url("/"))
            val bot = AppBot("cli_target", "ou_target", "李老师")
            fun chat(id: String) = json("chat_mode" to "p2p", "chat_id" to id, "name" to bot.name,
                "p2p_target_type" to "bot", "p2p_target_id" to bot.openId)
            val first = chat("oc_one")
            val pages = listOf(
                json("items" to JSONArray().put(chat("oc_other").apply { remove("p2p_target_id") })),
                json("items" to JSONArray().put(first).put(chat("oc_two"))),
                json("items" to JSONArray().put(first), "has_more" to true),
            )
            for (page in pages) {
                server.reply(json("code" to 0, "data" to page))
                assertThrows(java.io.IOException::class.java) { api.appChat("uat", bot) }
            }
            assertEquals(pages.size, server.requestCount)
        }
    }

    @Test fun menuUsesPlatformOrderAndOnlySendsConfiguredTextActions() {
        fun node(id: String, type: Int, title: String, sort: Int, parent: String = "") =
            json("menu_id" to id, "menu_content_type" to type, "default_name" to title, "sort" to sort, "parent_menu_id" to parent)
        val rows = JSONArray().put(node("cmd2", 4, "/two", 2, "group"))
            .put(node("link", 1, "打开网页", 1, "group")).put(node("group", 3, "Tools", 1)
                .put("i18n_name", json("zh_cn" to "工具")))
            .put(node("cmd1", 4, "/one now", 1, "group"))
            .put(node("root", 4, "/direct", 0).put("parent_menu_id", JSONObject.NULL))
            .put(node("unused", 3, "通知", 2)).put(node("event", 2, "事件", 0, "unused"))
            .put(node("orphan", 4, "/gone", 0, "absent"))
        val config = json("bot_menu_enable" to true, "bot_menus" to rows)
        assertEquals(listOf(BotMenuItem.Command("root", "/direct"), BotMenuItem.Group("group", "工具",
            listOf(BotMenuItem.Command("cmd1", "/one now"), BotMenuItem.Command("cmd2", "/two")))), decodeBotMenu(config))
        assertTrue(decodeBotMenu(config.put("bot_menu_enable", false)).isEmpty())
        assertTrue(decodeBotMenu(null).isEmpty())
    }
}
