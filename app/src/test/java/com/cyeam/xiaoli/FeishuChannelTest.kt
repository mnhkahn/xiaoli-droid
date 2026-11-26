package com.cyeam.xiaoli

import org.junit.Assert.*
import org.junit.Test

class FeishuChannelTest {
    @Test fun readsOnlyTheChannelValueFromEmbedCode() {
        assertEquals("abc.ABC_-123", channelFromInput("initAgentChat(root, {channelToken: 'abc.ABC_-123'});"))
        assertEquals("a+/b=", channelFromInput(" a+/b= "))
        assertNull(channelFromInput("https://aily.feishu.cn/"))
        assertNull(channelFromInput("cli_a1"))
        assertNull(channelFromInput("agent_a1"))
        assertNull(channelFromInput("<script>alert(1)</script>"))
    }

    @Test fun savedOrderPreservesTheSelectedChannel() {
        val saved = listOf(FeishuChannel("学习", "one"), FeishuChannel("工作", "two"))
        assertEquals(saved, decodeChannels(encodeChannels(saved)))
        assertTrue(decodeChannels("").isEmpty())
    }
}
