package com.cyeam.xiaoli

import java.io.IOException
import java.io.OutputStream
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal enum class Brand(val title: String, val domain: String, val appScheme: String, val clientPackage: String) {
    FEISHU("飞书", "feishu.cn", "feishu", "com.ss.android.lark"),
    LARK("Lark", "larksuite.com", "lark", "com.larksuite.suite");

    fun verificationUrl(raw: String): HttpUrl = raw.toHttpUrl().also {
        require(it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            it.host in setOf("accounts.$domain", "open.$domain", "passport.$domain", "applink.$domain")) {
            "授权页与所选平台不匹配，请重新登录"
        }
    }
}

internal data class Peer(val id: String, val name: String)
internal data class Profile(val name: String, val avatar: String?, val openId: String? = null)
internal data class AppBot(val appId: String, val openId: String, val name: String, val avatar: String? = null)
internal data class Resource(val key: String, val type: String, val name: String)
internal enum class Sender { USER, BOT, OTHER }
internal data class ImMessage(val id: String, val text: String, val resource: Resource?, val sender: Sender, val type: String,
    val blocks: List<MessageBlock> = emptyList(), val deleted: Boolean = false, val parentId: String? = null,
    val createdAt: Long = 0) {
    fun contains(resource: Resource) = !deleted && (this.resource == resource || MessageBlock.Picture(resource) in blocks)
}
internal data class ReactionActor(val type: String, val id: String)
internal data class EmojiReaction(val type: String, val count: Int, val ownId: String? = null,
    val actors: List<ReactionActor> = emptyList())
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

private fun JSONObject.avatarUrl(vararg fields: String): String? =
    fields.firstNotNullOfOrNull { field ->
        optString(field).toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    }

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
    val blocks = if (item.optBoolean("deleted")) emptyList() else messageBlocks(type, body)
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
        blocks.isNotEmpty() -> blocks.joinToString("\n") { block -> when (block) {
            is MessageBlock.CardHeader -> listOf(block.title, block.subtitle).filter(String::isNotBlank).joinToString("\n")
            is MessageBlock.Paragraph -> block.value
            is MessageBlock.Table -> (listOf(block.headers) + block.rows).joinToString("\n") { it.joinToString("\t") }
            is MessageBlock.Picture -> block.resource.name
        } }
        else -> buildList { readableParts(body, this) }.distinct().joinToString("\n").ifBlank { "[$type 消息]" }
    }
    val sender = when (item.optJSONObject("sender")?.optString("sender_type")) {
        "user" -> Sender.USER
        "app", "bot" -> Sender.BOT
        else -> Sender.OTHER
    }
    return ImMessage(item.getString("message_id"), message, resource.takeUnless { item.optBoolean("deleted") }, sender, type, blocks, item.optBoolean("deleted"),
        (item.opt("parent_id") as? String)?.takeIf(String::isNotBlank), item.optLong("create_time", 0))
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

    private fun response(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        val result = runCatching { JSONObject(response.body?.string().orEmpty()) }
            .getOrElse { throw IOException("开放平台返回 HTTP ${response.code}") }
        val code = result.optInt("code", -1)
        if (!response.isSuccessful || code != 0) throw ImFailure(code, result.optString("msg", "请求失败").take(220))
        result
    }

    private fun data(request: Request) = response(request).getJSONObject("data")

    private fun tenantToken(keys: AppKeys): String {
        val credentials = json("app_id" to keys.id, "app_secret" to keys.secret)
        val grant = Request.Builder().url(open.newBuilder().encodedPath("/open-apis/auth/v3/tenant_access_token/internal").build())
            .post(credentials.toString().toRequestBody("application/json".toMediaType())).build()
        return response(grant).getString("tenant_access_token")
    }

    fun appBot(keys: AppKeys): AppBot {
        val info = Request.Builder().url(open.newBuilder().encodedPath("/open-apis/bot/v3/info").build())
            .header("Authorization", "Bearer ${tenantToken(keys)}").build()
        val bot = response(info).getJSONObject("bot")
        val openId = bot.optString("open_id")
        if (!openId.startsWith("ou_") || openId.length <= 3) throw IOException("无法确认所选应用的机器人身份，请检查应用是否已启用机器人。")
        return AppBot(keys.id, openId, bot.optString("app_name").ifBlank { "智能体" }, bot.avatarUrl("avatar_url"))
    }

    fun commandMenu(keys: AppKeys): List<BotMenuItem> {
        val token = tenantToken(keys)
        val app = open.newBuilder().addPathSegments("open-apis/application/v6/applications")
            .addPathSegment(keys.id).addQueryParameter("lang", "zh_cn").build()
        fun get(url: HttpUrl) = data(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        val info = get(app).getJSONObject("app")
        if (info.isNull("online_version_id")) return emptyList()
        val versionId = info.getString("online_version_id")
        if (versionId.isBlank()) return emptyList()
        val version = get(app.newBuilder().addPathSegment("app_versions").addPathSegment(versionId).build())
            .getJSONObject("app_version")
        if (version.getString("app_id") != keys.id || version.getString("version_id") != versionId)
            throw IOException("快捷菜单与当前应用版本不匹配")
        return decodeBotMenu(version.optJSONObject("ability")?.optJSONObject("bot"))
    }

    fun userProfile(token: String): Profile {
        val request = Request.Builder().url(open.newBuilder().encodedPath("/open-apis/authen/v1/user_info").build())
            .header("Authorization", "Bearer $token").build()
        val user = data(request)
        return Profile(user.optString("name").ifBlank { "我" }, user.avatarUrl("avatar_middle", "avatar_thumb", "avatar_url"), user.optString("open_id").takeIf(String::isNotBlank))
    }

    fun appChat(token: String, bot: AppBot): Peer {
        var match: Peer? = null
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val params = mutableMapOf("types" to "p2p", "user_id_type" to "open_id", "page_size" to "100")
            cursor?.let { params["page_token"] = it }
            val page = data(request(token, "chats", params))
            for (row in page.rows()) {
                if (row.optString("chat_mode") != "p2p" || row.optString("p2p_target_type") != "bot") continue
                val target = row.optString("p2p_target_id")
                if (target != bot.openId && target != bot.appId) continue
                val id = row.optString("chat_id")
                if (!id.startsWith("oc_") || id.length <= 3) throw IOException("平台返回的单聊 ID 无效，请重试。")
                if (match != null && match.id != id) throw IOException("平台返回了多个与「${bot.name}」匹配的单聊，暂时无法确认。")
                match = Peer(id, row.optString("name").ifBlank { bot.name })
            }
            cursor = page.nextToken()
            if (page.optBoolean("has_more") && (cursor == null || !cursors.add(cursor)))
                throw IOException("单聊列表未加载完整，请重试。")
        } while (cursor != null)
        return match ?: throw IOException("找不到与「${bot.name}」的单聊。请在${brand.title}打开这个机器人的对话，发一条消息后重试。")
    }

    fun messages(token: String, peer: Peer, cursor: String? = null): Messages {
        val params = mutableMapOf("container_id_type" to "chat", "container_id" to peer.id,
            "sort_type" to "ByCreateTimeDesc", "page_size" to "50", "card_msg_content_type" to "user_card_content")
        cursor?.let { params["page_token"] = it }
        val page = data(request(token, "messages", params))
        return Messages(page.rows().filter { it.optString("chat_id", peer.id) == peer.id }.map(::decodeMessage), page.nextToken())
    }

    fun reactions(token: String, ids: List<String>, openId: String?): Map<String, List<EmojiReaction>> {
        require(ids.size in 1..20)
        val queries = JSONArray().apply { ids.forEach { put(json("message_id" to it)) } }
        val body = json("queries" to queries, "page_size_per_message" to 10)
        val result = data(request(token, "messages/reactions/batch_query", emptyMap(),
            body.toString().toRequestBody("application/json".toMediaType())))
        val failed = result.optJSONArray("fail_msg_reaction_details").objects().map { it.optString("message_id") }.toSet()
        val details = result.optJSONArray("success_msg_reaction_details").objects().associateBy { it.optString("message_id") }
        val counts = result.optJSONArray("success_msg_reaction_counts").objects().associateBy { it.optString("message_id") }
        return ids.filterNot(failed::contains).associateWith { id ->
            val records = details[id]?.optJSONArray("message_reaction_items").objects()
            val totals = counts[id]?.optJSONArray("reaction_count")?.objects()?.associate {
                it.optString("reaction_type") to it.optInt("count")
            } ?: records.groupingBy { it.optString("emoji_type") }.eachCount()
            totals.mapNotNull { (type, total) ->
                if (type.isBlank() || total <= 0) return@mapNotNull null
                val matching = records.filter { it.optString("emoji_type") == type }
                val own = matching.firstOrNull {
                    val operator = it.optJSONObject("operator")
                    operator?.optString("operator_type") == "user" &&
                        operator.optString("operator_id") == openId
                }
                val actors = matching.mapNotNull actor@{ record ->
                    val operator = record.optJSONObject("operator") ?: return@actor null
                    val id = operator.optString("operator_id").takeIf(String::isNotBlank) ?: return@actor null
                    ReactionActor(operator.optString("operator_type"), id)
                }.distinct()
                EmojiReaction(type, total, own?.optString("reaction_id")?.takeIf(String::isNotBlank), actors)
            }
        }
    }

    fun toggleReaction(token: String, messageId: String, type: String, openId: String) {
        val path = "messages/$messageId/reactions"
        val params = mapOf("reaction_type" to type, "user_id_type" to "open_id", "page_size" to "50")
        // The bound single chat has two participants. Re-read before toggling, including after a reconnect.
        val records = data(request(token, path, params)).rows()
        val mine = records.firstOrNull { row ->
            val op = row.optJSONObject("operator")
            op?.optString("operator_type") == "user" && op.optString("operator_id") == openId
        }
        if (mine == null) {
            val body = json("reaction_type" to json("emoji_type" to type))
            response(request(token, path, emptyMap(), body.toString().toRequestBody("application/json".toMediaType())))
        } else {
            val base = request(token, path, emptyMap())
            val url = base.url.newBuilder().addPathSegment(mine.getString("reaction_id")).build()
            response(base.newBuilder().url(url).delete().build())
        }
    }

    fun send(token: String, peer: Peer, type: String, content: JSONObject, uuid: String, replyTo: String? = null): ImMessage? {
        val body = json("msg_type" to type, "content" to content.toString(), "uuid" to uuid)
        val sent = if (replyTo == null) {
            body.put("receive_id", peer.id)
            data(request(token, "messages", mapOf("receive_id_type" to "chat_id"), body.asBody()))
        } else {
            body.put("reply_in_thread", false)
            data(request(token, "messages/$replyTo/reply", emptyMap(), body.asBody()))
        }
        if (sent.optString("message_id").isBlank() || sent.optString("chat_id", peer.id) != peer.id) return null
        if (!sent.has("msg_type")) sent.put("msg_type", type)
        if (!sent.has("body")) sent.put("body", json("content" to content.toString()))
        if (replyTo != null && !sent.has("parent_id")) sent.put("parent_id", replyTo)
        return decodeMessage(sent).copy(sender = Sender.USER)
    }

    private fun JSONObject.asBody() = toString().toRequestBody("application/json".toMediaType())

    fun isPinned(token: String, peer: Peer, messageId: String): Boolean {
        val params = mutableMapOf("chat_id" to peer.id, "page_size" to "50")
        val visited = mutableSetOf<String>()
        while (true) {
            val page = data(request(token, "pins", params))
            if (page.rows().any { it.optString("message_id") == messageId && it.optString("chat_id", peer.id) == peer.id }) return true
            if (!page.optBoolean("has_more")) return false
            val next = page.nextToken()
            if (next == null || !visited.add(next)) throw IOException("无法读取完整的 Pin 列表")
            params["page_token"] = next
        }
    }

    fun setPin(token: String, messageId: String, pin: Boolean) {
        val req = if (pin) request(token, "pins", emptyMap(), json("message_id" to messageId).asBody())
            else request(token, "pins/$messageId", emptyMap()).newBuilder().delete().build()
        response(req)
    }

    fun createTask(token: String, title: String, description: String, assignee: String, clientToken: String) {
        val url = open.newBuilder().addPathSegments("open-apis/task/v2/tasks").addQueryParameter("user_id_type", "open_id").build()
        val body = json("summary" to title, "description" to description, "client_token" to clientToken,
            "members" to JSONArray().put(json("id" to assignee, "type" to "user", "role" to "assignee")))
        val task = data(Request.Builder().url(url).header("Authorization", "Bearer $token").post(body.asBody()).build())
        require(task.getJSONObject("task").getString("guid").isNotBlank()) { "开放平台未返回任务 ID" }
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

    fun download(token: String, message: ImMessage, output: OutputStream, res: Resource = requireNotNull(message.resource)) {
        require(message.contains(res))
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
                    if (response.header("Content-Disposition") == null && body.contentType()?.subtype == "json") {
                        val status = runCatching { JSONObject(response.peekBody(16_384).string()) }.getOrNull()
                        if (status != null && status.has("msg") && status.optInt("code", 0) != 0)
                            throw ImFailure(status.getInt("code"), status.optString("msg").take(220))
                    }
                    if (!response.isSuccessful) throw IOException("附件下载失败（HTTP ${response.code}）")
                    body.byteStream().copyTo(output)
                    return
                }
            }
        }
        throw IOException("附件下载重定向次数过多")
    }
}
