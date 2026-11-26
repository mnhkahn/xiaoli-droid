package com.cyeam.xiaoli

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun snapshot(text: String = "hello") =
        JSONObject(
            """{"id":"s1","messages":[{"id":"m1","role":"assistant","text":"$text","status":"streaming"}],"pending":[{"id":"p1","kind":"permission","question":"执行吗？","options":["允许","拒绝"]}],"busy":true,"revision":1} """
        )

    @Test
    fun snapshotPreservesInteractivePermission() {
        val state = parseSnapshot(snapshot())
        assertEquals("p1", state.pending.single().id)
        assertEquals(listOf("允许", "拒绝"), state.pending.single().options)
        assertTrue(state.busy)
    }

    @Test
    fun duplicateDeltaIsNotAppliedTwice() {
        val start = parseSnapshot(snapshot())
        val event =
            JSONObject(
                """{"id":2,"type":"delta","data":{"message_id":"m1","field":"text","delta":"!"}}"""
            )
        val once = reduceEvent(start, event)
        assertEquals("hello!", once.messages.single().text)
        assertEquals(once, reduceEvent(once, event))
    }

    @Test
    fun thinkingIsSeparateFromAnswer() {
        val event =
            JSONObject(
                """{"id":2,"type":"delta","data":{"message_id":"m1","field":"thinking","delta":"思考中"}}"""
            )
        val state = reduceEvent(parseSnapshot(snapshot()), event)
        assertEquals("hello", state.messages.single().text)
        assertEquals("思考中", state.messages.single().thinking)
    }

    @Test
    fun reconnectReplacesPartialAnswer() {
        val event =
            JSONObject().put("id", 30).put("type", "snapshot").put("data", snapshot("final"))
        val state = reduceEvent(parseSnapshot(snapshot("partial")), event)
        assertEquals("final", state.messages.single().text)
        assertEquals(30, state.revision)
    }

    @Test
    fun addressValidationRejectsCredentialsAndCleartext() {
        for (url in
            listOf(
                "http://example.com",
                "https://user:secret@example.com",
                "https://example.com/foo",
                "https://example.com?token=x",
            )) {
            assertThrows(IllegalArgumentException::class.java) { validateServer(url, false) }
        }
        assertEquals("https://example.com", validateServer("https://example.com/", false))
        assertEquals("http://10.0.2.2:8080", validateServer("http://10.0.2.2:8080", true))
    }

    @Test
    fun credentialsAreOnlySentToSameOrigin() {
        assertTrue(sameOrigin("https://server.test/media/a", "https://server.test"))
        assertFalse(sameOrigin("https://server.test.evil/media", "https://server.test"))
        assertFalse(sameOrigin("http://server.test/media", "https://server.test"))
        assertFalse(sameOrigin("https://server.test:444/media", "https://server.test"))
    }

    @Test
    fun updateRequiresTrustedUrlAndSha() {
        val json =
            JSONObject()
                .put("versionCode", 2)
                .put("versionName", "0.2.0")
                .put(
                    "apkUrl",
                    "https://github.com/mnhkahn/xiaoli-droid/releases/download/v0.2.0/xiaoli.apk",
                )
                .put("sha256", "a".repeat(64))
        assertNotNull(parseUpdate(json, 1))
        assertNull(parseUpdate(json, 2))
        json.put("apkUrl", "https://evil.test/app.apk")
        assertThrows(IllegalArgumentException::class.java) { parseUpdate(json, 1) }
    }

    @Test
    fun markdownSplitsImagesWithoutLosingText() {
        val parts = markdownParts("前文 ![小李](https://example.com/a.png)\n\n**后文**")
        assertEquals(3, parts.size)
        assertEquals("https://example.com/a.png", (parts[1] as MarkdownPart.Image).url)
        assertEquals("\n\n**后文**", (parts[2] as MarkdownPart.Text).value)
    }

    @Test
    fun nativeMarkdownPreservesListAndLinks() {
        val result = nativeMarkdown("# 标题\n\n**重点**和[链接](https://example.com)\n\n- 第一项\n- 第二项")
        assertTrue(result.text.contains("标题"))
        assertTrue(result.text.contains("重点和链接"))
        assertTrue(result.text.contains("• 第一项"))
        assertTrue(result.text.contains("• 第二项"))
        assertEquals(
            "https://example.com",
            result.getStringAnnotations("URL", 0, result.length).single().item,
        )
    }
}
