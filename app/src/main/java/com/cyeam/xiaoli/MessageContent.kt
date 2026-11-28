package com.cyeam.xiaoli

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface MessageBlock {
    data class Paragraph(val value: String, val heading: Boolean = false) : MessageBlock
    data class Picture(val resource: Resource) : MessageBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>, val pageSize: Int) : MessageBlock
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

private fun localized(value: JSONObject): JSONObject =
    listOf("zh_cn", "en_us").firstNotNullOfOrNull(value::optJSONObject)
        ?: value.keys().asSequence().filter { it.matches(Regex("[a-z]{2}_[a-z]{2}")) }
            .firstNotNullOfOrNull(value::optJSONObject) ?: value

private fun visibleText(value: Any?): String = when (value) {
    is String, is Number, is Boolean -> value.toString()
    is JSONArray -> (0 until value.length()).map { visibleText(value.opt(it)) }.filter(String::isNotBlank).joinToString(" ")
    is JSONObject -> listOf("content", "text", "name", "user_name", "i18n", "zh_cn", "en_us").firstNotNullOfOrNull {
        visibleText(value.opt(it)).takeIf(String::isNotBlank)
    }.orEmpty()
    else -> ""
}

private fun JSONObject.image(): Resource? = sequenceOf("image_key", "img_key")
    .map { optString(it) }.firstOrNull(String::isNotBlank)?.let { Resource(it, "image", "图片.jpg") }

internal fun messageBlocks(type: String, body: JSONObject): List<MessageBlock> = buildList {
    fun paragraph(text: String, heading: Boolean = false) {
        if (text.isNotBlank()) add(MessageBlock.Paragraph(text, heading))
    }
    fun cardElement(value: Any?) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) cardElement(value.opt(i))
            is JSONObject -> when (value.optString("tag")) {
                "img", "image" -> value.image()?.let { add(MessageBlock.Picture(it)) }
                "a" -> paragraph(listOf(visibleText(value), value.optString("href")).filter(String::isNotBlank).distinct().joinToString(" "))
                "table" -> {
                    val columns = value.optJSONArray("columns").objects()
                    val rows = value.optJSONArray("rows").objects().map { row -> columns.map { column ->
                        visibleText(row.opt(column.optString("name")))
                    } }
                    if (columns.isNotEmpty()) add(MessageBlock.Table(columns.map {
                        visibleText(it.opt("display_name")).ifBlank { it.optString("name") }
                    }, rows, value.optInt("page_size", 5).coerceAtLeast(1)))
                }
                else -> {
                    paragraph(visibleText(value.optJSONObject("header")?.opt("title")), true)
                    paragraph(visibleText(value))
                    val url = value.optString("url").ifBlank { value.optJSONObject("multi_url")?.optString("url").orEmpty() }
                    if (url.startsWith("https://")) paragraph(url)
                    for (key in listOf("columns", "elements", "fields", "actions", "img_list")) cardElement(value.opt(key))
                }
            }
        }
    }
    when (type) {
        "post" -> {
            val post = localized(body)
            paragraph(visibleText(post.opt("title")), true)
            val lines = post.optJSONArray("content")
            for (i in 0 until (lines?.length() ?: 0)) {
                val line = StringBuilder()
                for (node in lines?.optJSONArray(i).objects()) {
                    val image = node.image()
                    if (image != null) {
                        paragraph(line.toString()); line.clear()
                        add(MessageBlock.Picture(image))
                    } else line.append(when (node.optString("tag")) {
                        "a" -> listOf(node.optString("text"), node.optString("href")).filter(String::isNotBlank).distinct().joinToString(" ")
                        "at" -> "@" + node.optString("user_name", node.optString("user_id"))
                        "emotion" -> node.optString("emoji_type")
                        else -> visibleText(node)
                    })
                }
                paragraph(line.toString())
            }
        }
        "interactive" -> {
            val card = listOf("card", "json_card").firstNotNullOfOrNull { key ->
                when (val content = body.opt(key)) {
                    is JSONObject -> content
                    is String -> runCatching { JSONObject(content) }.getOrNull()
                    else -> null
                }
            } ?: body
            val header = card.optJSONObject("header")
            paragraph(visibleText(header?.opt("title") ?: card.opt("title")), true)
            paragraph(visibleText(header?.opt("subtitle")))
            val languages = card.optJSONObject("i18n_elements")
            val translated = languages?.let { it.optJSONArray("zh_cn") ?: it.optJSONArray("en_us") ?:
                it.keys().asSequence().firstNotNullOfOrNull(it::optJSONArray) }
            cardElement(card.optJSONObject("body")?.optJSONArray("elements") ?: card.optJSONArray("elements") ?: translated)
        }
    }
}
