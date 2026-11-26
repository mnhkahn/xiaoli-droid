package com.cyeam.xiaoli

import org.json.JSONArray
import org.json.JSONObject

internal const val FEISHU_PANEL_HOST = "xiaoli-droid.cyeam.com"

internal data class FeishuChannel(val name: String, val token: String)

internal fun channelFromInput(input: String): String? {
    val raw = input.trim()
    val token = Regex("""["']?channelToken["']?\s*:\s*["']([^"'\s]+)["']""")
        .find(raw)?.groupValues?.get(1) ?: raw
    return token.takeIf {
        it.length in 1..4096 && it.all { ch -> ch.isLetterOrDigit() || ch in "._~+-/=" } &&
            !it.startsWith("cli_") && !it.startsWith("agent_") && it != "your_channel_token"
    }
}

internal fun encodeChannels(channels: List<FeishuChannel>): String =
    JSONArray(channels.map { JSONObject().put("name", it.name).put("token", it.token) }).toString()

internal fun decodeChannels(json: String): List<FeishuChannel> {
    if (json.isEmpty()) return emptyList()
    val rows = JSONArray(json)
    return (0 until rows.length()).map {
        val item = rows.getJSONObject(it)
        FeishuChannel(item.getString("name"), item.getString("token"))
    }
}
