package com.cyeam.xiaoli

import androidx.compose.runtime.Immutable
import java.net.URI
import org.json.JSONObject

@Immutable
data class ChatImage(val id: String, val url: String, val width: Int = 0, val height: Int = 0)

@Immutable data class ToolSource(val title:String,val url:String)
@Immutable data class ToolRun(val id:String,val name:String,val status:String,val elapsed:Long,val error:String,val sources:List<ToolSource>)

@Immutable data class ChatFile(val id:String,val name:String,val url:String,val mime:String,val bytes:Long)

@Immutable
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String = "",
    val thinking: String = "",
    val images: List<ChatImage> = emptyList(),
    val status: String = "complete",
    val files:List<ChatFile> = emptyList(),
    val tools:List<ToolRun> = emptyList(),
)

@Immutable
data class Interaction(
    val id: String,
    val kind: String,
    val question: String,
    val options: List<String>,
    val command: String = "",
    val answer: String = "",
)

@Immutable data class FreeModel(val id:String,val name:String,val provider:String)

@Immutable data class SessionItem(val id: String, val title: String, val pinned:Boolean=false, val archived:Boolean=false, val deleted:Boolean=false)

data class ChatState(
    val model:String="",
    val sessionId: String = "",
    val title:String="",
    val temporary:Boolean=false,
    val archived:Boolean=false,
    val deleted:Boolean=false,
    val messages: List<ChatMessage> = emptyList(),
    val pending: List<Interaction> = emptyList(),
    val busy: Boolean = false,
    val before: Int = 0,
    val revision: Long = 0,
    val progress: String = "",
)

data class AppState(
    val chat: ChatState = ChatState(),
    val appearance:String="system",
    val notifications:Boolean=false,
    val appLock:Boolean=false,
    val speechRate:Float=1f,
    val searchEnabled:Boolean=false,
    val models:List<FreeModel> = emptyList(),
    val modelsLoading:Boolean = false,
    val modelsError:String? = null,
    val sessions: List<SessionItem> = emptyList(),
    val sessionQuery:String="",
    val sessionView:String="",
    val remoteImages:List<ChatImage> = emptyList(),
    val documents:List<String> = emptyList(),
    val remoteFiles:List<ChatFile> = emptyList(),
    val connected: Boolean = false,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val draft: String = "",
    val attachments: List<String> = emptyList(),
    val preparing: Boolean = false,
    val recording: Boolean = false,
    val voiceMode:Boolean=false,
    val voiceStage:String="",
    val voiceRequestId:String="",
    val transcribing: Boolean = false,
    val paired: Boolean = false,
    val server: String = "https://xiaoli-server.fly.dev",
    val update: UpdateInfo? = null,
    val downloadProgress: Int? = null,
)

fun JSONObject.objects(key: String): List<JSONObject> =
    optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        ?: emptyList()

fun parseSnapshot(o: JSONObject): ChatState =
    ChatState(
        model=o.optString("model"),
        sessionId = o.getString("id"),
        title=o.optString("title"),
        temporary=o.optBoolean("temporary"),
        archived=o.optBoolean("archived"),
        deleted=o.optBoolean("deleted"),
        messages =
            o.objects("messages").map { m ->
                ChatMessage(
                    m.getString("id"),
                    m.getString("role"),
                    m.optString("text"),
                    m.optString("thinking"),
                    m.objects("images").map {
                        ChatImage(
                            it.getString("id"),
                            it.getString("url"),
                            it.optInt("width"),
                            it.optInt("height"),
                        )
                    },
                    m.optString("status", "complete"),
                    m.objects("files").map(::parseFile),
                    m.objects("tools").map(::parseTool),
                )
            },
        pending =
            o.objects("pending").map { p ->
                Interaction(
                    p.getString("id"),
                    p.getString("kind"),
                    p.getString("question"),
                    p.optJSONArray("options")?.let { a ->
                        (0 until a.length()).map { a.getString(it) }
                    } ?: emptyList(),
                    p.optString("command"),
                    p.optString("answer"),
                )
            },
        busy = o.optBoolean("busy"),
        before = o.optInt("before"),
        revision = o.optLong("revision"),
    )

fun reduceEvent(current: ChatState, event: JSONObject): ChatState {
    val data = event.optJSONObject("data") ?: return current
    return when (event.optString("type")) {
        "snapshot" -> {
            val next = parseSnapshot(data)
            val first = next.messages.firstOrNull()?.id
            val index = current.messages.indexOfFirst { it.id == first }
            val older =
                if (next.sessionId == current.sessionId && index > 0) current.messages.take(index)
                else emptyList()
            next.copy(
                messages = older + next.messages,
                before = if (older.isEmpty()) next.before else current.before,
                revision = event.optLong("id"),
            )
        }
        "delta" ->
            if (event.optLong("id") <= current.revision) current
            else
                current.copy(
                    revision = event.optLong("id"),
                    messages =
                        current.messages.map { m ->
                            if (m.id != data.optString("message_id")) m
                            else if (data.optString("field") == "thinking")
                                m.copy(thinking = m.thinking + data.optString("delta"))
                            else m.copy(text = m.text + data.optString("delta"))
                        },
                )
        "tool" -> current.copy(revision=event.optLong("id"),messages=current.messages.map{m->if(m.id==data.optString("message_id"))m.copy(tools=data.objects("tools").map(::parseTool)) else m})
        "progress" ->
            current.copy(
                revision = event.optLong("id"),
                progress =
                    if (data.optString("type") == "agent.tool.started")
                        "正在使用 ${data.optJSONObject("data")?.optString("name").orEmpty()}"
                    else "",
            )
        else -> current
    }
}

fun validateServer(raw: String, debug: Boolean): String {
    val uri =
        runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("服务器地址无效") }
    val local = uri.host in listOf("localhost", "127.0.0.1", "10.0.2.2")
    require(
        uri.host != null &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            uri.path.orEmpty() in listOf("", "/") &&
            (uri.scheme == "https" || debug && local && uri.scheme == "http")
    ) {
        "请输入 HTTPS 服务器地址"
    }
    return raw.trim().trimEnd('/')
}

fun parseFile(o:JSONObject)=ChatFile(o.getString("id"),o.getString("name"),o.getString("url"),o.optString("mime"),o.optLong("bytes"))

fun parseTool(o:JSONObject)=ToolRun(o.getString("id"),o.getString("name"),o.getString("status"),o.optLong("elapsed_ms"),o.optString("error"),o.objects("sources").map{ToolSource(it.optString("title"),it.getString("url"))})
