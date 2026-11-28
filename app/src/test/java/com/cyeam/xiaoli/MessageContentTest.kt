package com.cyeam.xiaoli

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageContentTest {
    @Test fun rawCardComponentsAndLegacyRichMessages() {
        val body = JSONObject("""{"schema":"2.0","header":{"title":{"content":"进度"}},"body":{"elements":[
          {"tag":"column_set","columns":[{"tag":"column","elements":[{"tag":"markdown","content":"八项作业"}]}]},
          {"tag":"table","columns":[{"name":"b","display_name":"作业"},{"name":"a","display_name":"状态"}],
           "page_size":5,"rows":[{"a":"待完成","b":"阅读"},{"a":"已完成","b":"默写"}]}]}}""")
        val card = messageBlocks("interactive", body)
        assertEquals(MessageBlock.Paragraph("进度", true), card[0])
        assertEquals(MessageBlock.Paragraph("八项作业"), card[1])
        assertEquals(MessageBlock.Table(listOf("作业","状态"), listOf(listOf("阅读","待完成"),listOf("默写","已完成")),5),card[2])
        val legacy = JSONObject("""{"title":"旧卡片","elements":[[{"tag":"text","text":"正文"}]]}""")
        assertEquals(listOf(MessageBlock.Paragraph("旧卡片",true),MessageBlock.Paragraph("正文")), messageBlocks("interactive",legacy))
        val post = JSONObject("""{"zh_cn":{"content":[[{"tag":"text","text":"图前"},{"tag":"img","image_key":"img_sample"},{"tag":"text","text":"图后"}]]}}""")
        val parsed = messageBlocks("post",post)
        assertEquals(listOf(MessageBlock.Paragraph("图前"),MessageBlock.Picture(Resource("img_sample","image","图片.jpg")),MessageBlock.Paragraph("图后")),parsed)
        val deleted = decodeMessage(json("message_id" to "om_deleted","msg_type" to "post","deleted" to true,"body" to json("content" to post.toString())))
        assertTrue(deleted.blocks.isEmpty())
        assertEquals("消息已撤回",deleted.text)
    }

    @Test fun partialBatchErrorsDoNotEraseReactionsAndOpaqueIdsAreEscaped() {
        for (brand in Brand.entries) MockWebServer().use { server ->
            val api = ImApi(brand,server.url("/"),server.url("/"))
            fun queue(data: String) { server.enqueue(MockResponse().setBody("""{"code":0,"data":$data}""")) }
            queue("""{"success_msg_reaction_counts":[{"message_id":"om_a","reaction_count":[{"reaction_type":"Typing","count":"1"}]}],
              "fail_msg_reaction_details":[{"message_id":"om_b","fail_reason":"no_permission"}]}""")
            val states = api.reactions("token",listOf("om_a","om_b","om_c"),"ou_self")
            assertEquals(listOf(EmojiReaction("Typing",1)),states["om_a"])
            assertFalse(states.containsKey("om_b"))
            assertEquals(emptyList<EmojiReaction>(),states["om_c"])
            assertEquals("/open-apis/im/v1/messages/reactions/batch_query",server.takeRequest().path)
            queue("""{"items":[{"reaction_id":"bot-id","operator":{"operator_type":"app","operator_id":"cli_bot"}},
                {"reaction_id":"own/a+b=","operator":{"operator_type":"user","operator_id":"ou_self"}}]}""")
            queue("{}")
            api.toggleReaction("token","om_a","Typing","ou_self")
            assertEquals("Typing",server.takeRequest().requestUrl?.queryParameter("reaction_type"))
            val deletion = server.takeRequest()
            assertEquals("DELETE",deletion.method)
            assertEquals("own/a+b=",deletion.requestUrl?.pathSegments?.last())
        }
    }
}
