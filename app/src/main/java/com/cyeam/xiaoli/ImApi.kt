package com.cyeam.xiaoli

import java.io.IOException
import java.io.OutputStream
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class Brand(val title: String, val domain: String, val clientPackage: String) {
    FEISHU("飞书", "feishu.cn", "com.ss.android.lark"),
    LARK("Lark", "larksuite.com", "com.larksuite.suite");

    fun verificationUrl(raw: String): HttpUrl = raw.toHttpUrl().also {
        require(it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            it.host in setOf("accounts.$domain", "open.$domain", "passport.$domain", "applink.$domain")) {
            "授权页与所选平台不匹配，请重新登录"
        }
    }
}

internal data class Peer(val id: String, val name: String)
internal data class Resource(val key: String, val type: String, val name: String)
internal data class ImMessage(val id: String, val text: String, val resource: Resource?, val bot: Boolean, val type: String)
internal data class Messages(val items: List<ImMessage>, val next: String?)
internal class ImFailure(val code: Int, reason: String) : IOException("开放平台 $code · $reason")

internal fun json(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply {
    pairs.forEach { (key, value) -> put(key, value) }
}

private fun JSONObject.rows(): List<JSONObject> {
    val list = optJSONArray("items") ?: return emptyList()
    return (0 until list.length()).mapNotNull(list::optJSONObject)
}

private fun JSONObject.nextToken(): String? =
    optString("page_token").takeIf { optBoolean("has_more") && it.isNotBlank() }

private fun readableParts(value: Any?, parts: MutableList<String>) {
    when (value) {
        is JSONArray -> for (i in 0 until value.length()) readableParts(value.opt(i), parts)
        is JSONObject -> for (k in value.keys()) {
            val v = value.opt(k)
            if (v is String && k in setOf("text", "content", "title", "href", "url", "user_name")) {
                if (v.isNotBlank()) parts.add(v)
            } else readableParts(v, parts)
        }
    }
}

internal fun decodeMessage(item: JSONObject): ImMessage {
    val type = item.optString("msg_type")
    val raw = item.optJSONObject("body")?.optString("content").orEmpty()
    val body = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    val resource = when (type) {
        "image" -> body.optString("image_key").takeIf(String::isNotBlank)?.let { Resource(it, "image", "图片.jpg") }
        "audio", "file", "media" -> body.optString("file_key").takeIf(String::isNotBlank)?.let {
            Resource(it, "file", body.optString("file_name").ifBlank { if (type == "audio") "语音.ogg" else "附件" })
        }
        else -> null
    }
    val message = when {
        item.optBoolean("deleted") -> "消息已撤回"
        type == "text" -> body.optString("text")
        type == "location" -> {
            val lat = body.optDouble("latitude")
            val lon = body.optDouble("longitude")
            val label = body.optString("name")
            if (lat in -90.0..90.0 && lon in -180.0..180.0) listOf(label, locationText(lat, lon)).filter(String::isNotBlank).joinToString("\n")
            else label.ifBlank { "位置" }
        }
        resource != null -> resource.name
        else -> buildList { readableParts(body, this) }.distinct().joinToString("\n").ifBlank { "[$type 消息]" }
    }
    return ImMessage(item.getString("message_id"), message, resource.takeUnless { item.optBoolean("deleted") },
        item.optJSONObject("sender")?.optString("sender_type") in setOf("app", "bot"), type)
}

internal class ImApi(
    val brand: Brand,
    private val accounts: HttpUrl = "https://accounts.${brand.domain}".toHttpUrl(),
    private val open: HttpUrl = "https://open.${brand.domain}".toHttpUrl(),
    transport: OkHttpClient = client,
) {
    companion object {
        private val client = OkHttpClient()
    }

    private val http = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    fun form(path: String, params: Map<String, String>, authorization: String? = null): JSONObject {
        val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(accounts.newBuilder().encodedPath(path).build()).post(body)
        authorization?.let { request.header("Authorization", it) }
        return http.newCall(request.build()).execute().use {
            val response = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrElse { _ ->
                throw IOException("授权服务返回 HTTP ${it.code}")
            }
            if (!it.isSuccessful && !response.has("error")) throw IOException("授权服务返回 HTTP ${it.code}")
            response
        }
    }

    private fun request(token: String, path: String, params: Map<String, String>, body: RequestBody? = null): Request {
        val url = open.newBuilder().addPathSegments("open-apis/im/v1").apply {
            path.split('/').forEach { require(it.isNotBlank() && it !in listOf(".", "..")); addPathSegment(it) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("Authorization", "Bearer $token").apply { body?.let(::post) }.build()
    }

    private fun data(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        val result = runCatching { JSONObject(response.body?.string().orEmpty()) }
            .getOrElse { throw IOException("开放平台返回 HTTP ${response.code}") }
        val code = result.optInt("code", -1)
        if (!response.isSuccessful || code != 0) throw ImFailure(code, result.optString("msg", "请求失败").take(220))
        result.getJSONObject("data")
    }

    fun peers(token: String): List<Peer> {
        val result = mutableListOf<Peer>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val params = mutableMapOf("types" to "p2p", "page_size" to "100")
            cursor?.let { params["page_token"] = it }
            val page = data(request(token, "chats", params))
            result += page.rows().filter { it.optString("chat_mode") == "p2p" }.mapNotNull {
                val id = it.optString("chat_id").takeIf { id -> id.startsWith("oc_") } ?: return@mapNotNull null
                Peer(id, it.optString("name").ifBlank { id })
            }
            cursor = page.nextToken()
        } while (cursor != null && cursors.add(cursor))
        return result.distinctBy { it.id }
    }

    fun messages(token: String, peer: Peer, cursor: String? = null): Messages {
        val params = mutableMapOf("container_id_type" to "chat", "container_id" to peer.id,
            "sort_type" to "ByCreateTimeDesc", "page_size" to "50")
        cursor?.let { params["page_token"] = it }
        val page = data(request(token, "messages", params))
        return Messages(page.rows().filter { it.optString("chat_id", peer.id) == peer.id }.map(::decodeMessage), page.nextToken())
    }

    fun send(token: String, peer: Peer, type: String, content: JSONObject, uuid: String) {
        val body = json("receive_id" to peer.id, "msg_type" to type, "content" to content.toString(), "uuid" to uuid)
        data(request(token, "messages", mapOf("receive_id_type" to "chat_id"),
            body.toString().toRequestBody("application/json".toMediaType())))
    }

    fun upload(token: String, name: String, body: RequestBody, type: String, durationMs: Long): JSONObject {
        val image = type == "image"
        val field = if (image) "image" else "file"
        val form = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            if (image) addFormDataPart("image_type", "message")
            else {
                addFormDataPart("file_name", name)
                addFormDataPart("file_type", if (type == "audio") "opus" else "stream")
                if (type == "audio") addFormDataPart("duration", durationMs.toString())
            }
            addFormDataPart(field, name, body)
        }.build()
        val key = "${field}_key"
        return json(key to data(request(token, "${field}s", emptyMap(), form)).getString(key))
    }

    fun download(token: String, message: ImMessage, output: OutputStream) {
        val res = requireNotNull(message.resource)
        require(listOf(message.id, res.key).all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        var req = request(token, "messages/${message.id}/resources/${res.key}", mapOf("type" to res.type))
        repeat(5) {
            http.newCall(req).execute().use { response ->
                if (response.isRedirect) {
                    val next = response.header("Location")?.let { req.url.resolve(it) }
                    val allowed = listOf(brand.domain, "feishucdn.com", "feishucdn.cn", "larksuitecdn.com", "larkoffice.com")
                    require(next != null && next.isHttps && next.username.isEmpty() && next.password.isEmpty() &&
                        (next.host == open.host && next.port == open.port ||
                            next.port == 443 && allowed.any { next.host == it || next.host.endsWith(".$it") })) { "附件下载地址无效" }
                    // The signed download URL carries its own authorization.
                    req = Request.Builder().url(next).build()
                } else {
                    val body = response.body ?: throw IOException("附件为空")
                    if (!response.isSuccessful) throw IOException("附件下载失败（HTTP ${response.code}）")
                    if (response.header("Content-Disposition") == null && body.contentType()?.subtype == "json") {
                        val status = runCatching { JSONObject(response.peekBody(16_384).string()) }.getOrNull()
                        if (status != null && status.has("msg") && status.optInt("code", 0) != 0)
                            throw ImFailure(status.getInt("code"), status.optString("msg").take(220))
                    }
                    body.byteStream().copyTo(output)
                    return
                }
            }
        }
        throw IOException("附件下载重定向次数过多")
    }
}
