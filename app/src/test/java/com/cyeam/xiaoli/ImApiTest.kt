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
        "expires_in" to 60, "interval" to 1, "verification_uri_complete" to "https://open.$domain/page/launcher?code=ABCD")

    @Test fun registrationUserConsentAndRefreshOnBothPlatforms() = runBlocking {
        for (brand in Brand.entries) MockWebServer().use { server ->
            server.start()
            val domain = brand.domain
            val login = ImLogin(ImApi(brand, server.url("/"), server.url("/")))
            server.reply(begin(domain))
            server.reply(json("error" to "authorization_pending"), 400)
            server.reply(json("client_id" to "cli_a", "client_secret" to "secr", "user_info" to json("tenant_brand" to brand.name.lowercase())))
            val keys = login.register { approval ->
                val link = approval.url.toHttpUrl()
                assertTrue(approval.registration)
                assertEquals("open.$domain", link.host)
                assertEquals("true", link.queryParameter("createOnly"))
                val encoded = Base64.getUrlDecoder().decode(link.queryParameter("addons"))
                val addons = JSONObject(GZIPInputStream(ByteArrayInputStream(encoded)).bufferedReader().readText())
                assertFalse(addons.getBoolean("preset"))
                assertTrue(addons.getJSONObject("scopes").getJSONArray("user").toString().contains("im:resource"))
            }
            assertEquals("/oauth/v1/app/registration", server.takeRequest().path)
            repeat(2) { assertTrue(server.takeRequest().body.readUtf8().contains("action=poll")) }
            server.reply(begin(domain))
            server.reply(json("access_token" to "access", "refresh_token" to "r1", "expires_in" to 100))
            val token = login.signIn(keys) { assertFalse(it.registration) }
            val auth = server.takeRequest()
            assertEquals("/oauth/v1/device_authorization", auth.path)
            assertTrue(auth.getHeader("Authorization")!!.startsWith("Basic "))
            assertTrue(auth.body.readUtf8().contains("offline_access"))
            assertEquals("/oauth/v3/token", server.takeRequest().path)
            server.reply(json("access_token" to "next", "refresh_token" to "r2"))
            assertEquals("r2", login.refresh(keys, token).refresh)
            assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=r1"))
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
                server.reply(begin(brand.domain))
                server.reply(json("error" to "access_denied"), 400)
                assertThrows(OAuthFailure::class.java) { runBlocking { login.register {} } }
            }
        }
    }

    @Test fun listPagesAndIsolateSelectedChatOnBothPlatforms() {
        fun success(data: JSONObject) = json("code" to 0, "data" to data)
        fun row(id: String, mode: String) = json("chat_id" to id, "name" to id, "chat_mode" to mode)
        for (brand in Brand.entries) MockWebServer().use { server ->
            server.start()
            val api = ImApi(brand, server.url("/"), server.url("/"))
            server.reply(success(json("items" to JSONArray().put(row("oc_group","group")).put(row("oc_agent","p2p")),
                "has_more" to true, "page_token" to "page-2")))
            server.reply(success(json("items" to JSONArray().put(row("oc_other","p2p")))))
            val peers = api.peers("uat")
            assertEquals(listOf("oc_agent","oc_other"), peers.map { it.id })
            assertEquals("p2p", server.takeRequest().requestUrl!!.queryParameter("types"))
            assertEquals("page-2", server.takeRequest().requestUrl!!.queryParameter("page_token"))
            val peer = peers.first()
            server.reply(success(JSONObject()))
            api.send("uat", peer, "text", json("text" to "你好"), "unique")
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
}
