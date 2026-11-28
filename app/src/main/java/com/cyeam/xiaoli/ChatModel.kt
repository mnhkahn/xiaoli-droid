package com.cyeam.xiaoli

import android.app.Application
import android.net.Uri
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.RequestBody

internal data class TaskDraft(val sourceId: String, val title: String, val clientToken: String = UUID.randomUUID().toString(),
    val needsAuthorization: Boolean = false)

internal class ChatModel @JvmOverloads constructor(app: Application, private val platform: (Brand) -> ImApi = { ImApi(it) }) : AndroidViewModel(app) {
    private val store = ImStore(app)
    private var session = newScope()
    private fun newScope() = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
    private val refreshLock = Mutex()
    private val historyLock = Mutex()
    private var account = LoginState()
    var brand by mutableStateOf<Brand?>(null); private set
    private lateinit var api: ImApi
    var approval by mutableStateOf<Approval?>(null); private set
    var signedIn by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var completedSends by mutableIntStateOf(0); private set
    var selected by mutableStateOf<Peer?>(null); private set
    var userProfile by mutableStateOf<Profile?>(null); private set
    var botProfile by mutableStateOf<Profile?>(null); private set
    var menu by mutableStateOf(emptyList<BotMenuItem>()); private set
    var menuError by mutableStateOf<String?>(null); private set
    private var menuJob: Job? = null
    var messages by mutableStateOf(emptyList<ImMessage>()); private set
    private val awaitingHistory = mutableSetOf<String>()
    var reactions by mutableStateOf(emptyMap<String, List<EmojiReaction>>()); private set
    var reactionError by mutableStateOf<String?>(null); private set
    var changingReaction by mutableStateOf(false); private set
    private val reactionLock = Mutex()
    private val imageSlots = Semaphore(3)
    private val thumbnails = object : LruCache<Pair<String, String>, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Pair<String, String>, value: Bitmap) = value.byteCount
    }
    var older by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var historyError by mutableStateOf<String?>(null); private set
    var draft by mutableStateOf(""); private set
    var replyTo by mutableStateOf<ImMessage?>(null); private set
    var topId by mutableStateOf<String?>(null); private set
    var taskDraft by mutableStateOf<TaskDraft?>(null); private set
    private var taskAuthorization: Job? = null
    private var draftId = UUID.randomUUID().toString()

    init { store.active?.let { enter(it, restore = true) } }

    private fun clearWork() {
        session.cancel()
        session = newScope()
        approval = null
        busy = false
        sending = false
        completedSends = 0
        error = null
        historyError = null
        userProfile = null
        botProfile = null
        menu = emptyList()
        menuError = null
        menuJob = null
        messages = emptyList()
        awaitingHistory.clear()
        older = null
        reactions = emptyMap()
        reactionError = null
        changingReaction = false
        thumbnails.evictAll()
        replyTo = null
        topId = null
        taskDraft = null
        taskAuthorization = null
        edit("")
    }

    fun home() {
        clearWork()
        brand = null
        signedIn = false
        selected = null
        store.active = null
    }

    private fun persist(state: LoginState) {
        store.write(requireNotNull(brand), state)
        account = state
        signedIn = state.token != null
        selected = state.peer
        topId = state.topId
    }

    fun enter(value: Brand, restore: Boolean = false) {
        clearWork()
        brand = value
        account = LoginState()
        signedIn = false
        selected = null
        api = platform(value)
        try {
            account = store.read(value)
            signedIn = account.token != null
            selected = account.peer.takeIf { signedIn }
            topId = account.topId.takeIf { selected != null }
            store.active = value
            if (selected != null) loadProfiles()
            else if (signedIn) connect()
            else if (!signedIn && (!restore || account.pending != null)) login(resumePending = restore)
        } catch (e: Exception) { signedIn = false; selected = null; notice(e.message ?: "本机授权不可读") }
    }

    fun disconnect() {
        store.forget(requireNotNull(brand))
        home()
    }

    fun renewLoginLink() {
        if (signedIn || approval == null) return
        clearWork()
        login()
    }

    fun login(resumePending: Boolean = false) = work {
        val flow = ImLogin(api)
        suspend fun show(it: Approval) = withContext(Dispatchers.Main.immediate) { persist(account.copy(pending = it)); approval = it }
        val pending = account.pending?.takeIf { resumePending && it.until > System.currentTimeMillis() }
        if (pending == null && account.pending != null) persist(account.copy(pending = null))
        val keys = account.keys ?: withContext(Dispatchers.IO) {
            flow.register(platform(Brand.FEISHU), pending?.takeIf { it.registration }, ::show)
        }.also {
            persist(LoginState(keys = it))
        }
        approval = null
        val token = withContext(Dispatchers.IO) { flow.signIn(keys, pending?.takeUnless { it.registration }, show = ::show) }
        approval = null
        persist(LoginState(keys, token))
        connectAppChat()
    }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        val own = session
        own.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: OAuthFailure) {
                if (session !== own) return@launch
                if (account.pending != null) persist(account.copy(pending = null))
                notice(e.message ?: "授权失败")
            }
            catch (e: Exception) { if (session === own) notice(e.message ?: "操作失败") }
            finally { if (session === own) { busy = false; approval = null } }
        }
    }

    private suspend fun access(rejected: String? = null): String = refreshLock.withLock {
        val owner = requireNotNull(brand)
        val client = api
        val token = requireNotNull(account.token) { "请重新登录" }
        if (token.until > System.currentTimeMillis() + 60_000 && token.access != rejected) return@withLock token.access
        val keys = requireNotNull(account.keys)
        // A refresh can consume the old token even if the user leaves this screen mid-request.
        val fresh = withContext(NonCancellable) {
            try {
                val renewed = withContext(Dispatchers.IO) { ImLogin(client).refresh(keys, token) }
                val latest = store.read(owner)
                if (latest.token == token) {
                    val updated = latest.copy(token = renewed)
                    store.write(owner, updated)
                    if (brand == owner && account.token == token) account = updated
                }
                renewed
            } catch (e: OAuthFailure) {
                if (e.errorCode in setOf("invalid_grant", "invalid_token", "expired_token") && store.read(owner).token == token) {
                    val reset = LoginState(keys)
                    if (brand == owner && account.token == token) {
                        persist(reset)
                        messages = emptyList()
                        awaitingHistory.clear()
                        edit("")
                    } else store.write(owner, reset)
                }
                throw e
            }
        }
        fresh.access
    }

    private suspend fun <T> withToken(action: (ImApi, String) -> T): T {
        val client = api
        val token = access()
        try { return withContext(Dispatchers.IO) { action(client, token) } }
        catch (e: ImFailure) {
            if (e.code !in setOf(99991663, 99991668, 99991677)) throw e
            val renewed = access(token)
            return withContext(Dispatchers.IO) { action(client, renewed) }
        }
    }

    fun connect() = work { connectAppChat() }

    private suspend fun connectAppChat() {
        val keys = requireNotNull(account.keys)
        val client = api
        val bot = withContext(Dispatchers.IO) { client.appBot(keys) }
        val peer = withToken { im, access -> im.appChat(access, bot) }
        persist(account.copy(peer = peer))
        botProfile = Profile(bot.name, bot.avatar, bot.openId)
        loadProfiles()
    }

    // Profile requests are optional and run independently of message polling and sending.
    private fun loadProfiles() {
        val client = api
        val keys = requireNotNull(account.keys)
        if (botProfile == null) session.launch {
            try {
                val bot = withContext(Dispatchers.IO) { client.appBot(keys) }
                botProfile = Profile(bot.name, bot.avatar, bot.openId)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The chat name supplies the fallback avatar. */ }
        }
        session.launch {
            try { userProfile = withToken { im, token -> im.userProfile(token) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Keep the local avatar while the profile is unavailable. */ }
        }
    }

    private suspend fun loadHistory(peer: Peer, cursor: String? = null) = historyLock.withLock {
        if (selected != peer || !signedIn || (cursor != null && cursor != older)) return@withLock
        val page = withToken { client, access -> client.messages(access, peer, cursor) }
        // The screen's lifecycle coroutine can outlive the selected connection.
        if (selected != peer || !signedIn) return@withLock
        val items = page.items
        if (cursor == null) {
            val (acknowledged, old) = messages.partition { it.id in awaitingHistory }
            val received = items.mapTo(mutableSetOf()) { it.id }
            awaitingHistory.removeAll(received)
            val contiguous = old.any { it.id in received }
            val history = if (contiguous) (items + old).distinctBy { it.id } else items
            // A successful send can reach this device before it reaches the history endpoint.
            messages = if (awaitingHistory.isEmpty()) history else history.toMutableList().apply {
                for (sent in acknowledged.asReversed()) {
                    if (sent.id !in awaitingHistory) continue
                    val index = if (sent.createdAt == 0L) 0 else indexOfFirst { it.createdAt <= sent.createdAt }
                    add(if (index < 0) size else index, sent)
                }
            }
            if (!contiguous) older = page.next
        } else {
            messages = (messages + items).distinctBy { it.id }
            older = page.next.takeUnless { it == cursor }
        }
        historyError = null
        if (items.any { it.deleted && it.id == topId }) persist(account.copy(topId = null))
    }

    fun refreshMenu() {
        val keys = account.keys ?: return
        val peer = selected ?: return
        if (menuJob?.isActive == true) return
        val client = api
        menuJob = session.launch {
            try {
                val items = withContext(Dispatchers.IO) { client.commandMenu(keys) }
                if (selected == peer) { menu = items; menuError = null }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (selected == peer) {
                    menu = emptyList()
                    menuError = if (e is ImFailure && e.code == 99991672) "应用缺少读取快捷菜单的权限" else "快捷菜单加载失败"
                }
            }
        }
    }

    fun startPolling(): Job {
        val peer = requireNotNull(selected)
        return session.launch {
            while (selected == peer && signedIn) {
                try { loadHistory(peer) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { historyError = e.message ?: "获取消息失败" }
                delay(5000)
            }
        }
    }
    fun loadOlder() {
        val cursor = older ?: return
        val peer = selected ?: return
        work { loadHistory(peer, cursor) }
    }
    fun edit(value: String) { draft = value; draftId = UUID.randomUUID().toString() }
    fun notice(message: String) { error = message }

    private fun message(id: String) = messages.firstOrNull { it.id == id && !it.deleted }
        ?: throw IllegalStateException("这条消息已撤回或不在当前会话中")

    fun setReply(id: String?) {
        replyTo = id?.let { key -> messages.firstOrNull { it.id == key && !it.deleted } ?: return notice("这条消息已不可用") }
        draftId = UUID.randomUUID().toString()
    }

    fun setTop(id: String?) {
        if (id != null && messages.none { it.id == id && !it.deleted }) return notice("这条消息已不可用")
        persist(account.copy(topId = id))
    }

    suspend fun locateTop(id: String): Int? {
        val peer = selected ?: return null
        val seen = mutableSetOf<String>()
        while (topId == id && selected == peer) {
            val index = messages.indexOfFirst { it.id == id }
            if (index >= 0) return index
            val cursor = older ?: break
            if (!seen.add(cursor)) break
            loadHistory(peer, cursor)
        }
        return null
    }

    fun loadPin(id: String, result: (Result<Boolean>) -> Unit): Job = session.launch {
        try {
            message(id)
            val peer = requireNotNull(selected)
            val pinned = withToken { client, token -> client.isPinned(token, peer, id) }
            result(Result.success(pinned))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { result(Result.failure(e)) }
    }

    fun setPin(id: String, pinned: Boolean) = work {
        message(id)
        withToken { client, token -> client.setPin(token, id, pinned) }
        feedback(if (pinned) "已 Pin 消息" else "已移除 Pin")
    }

    private fun feedback(text: String) =
        android.widget.Toast.makeText(getApplication<Application>(), text, android.widget.Toast.LENGTH_SHORT).show()

    fun startTask(id: String) {
        val source = messages.firstOrNull { it.id == id && !it.deleted } ?: return notice("这条消息已不可用")
        error = null
        taskDraft = TaskDraft(id, source.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(120))
    }

    fun editTask(title: String) {
        if (!busy && title.length <= 3000) taskDraft = taskDraft?.copy(title = title, clientToken = UUID.randomUUID().toString())
    }

    fun cancelTask() {
        if (busy && taskAuthorization == null) return
        taskAuthorization?.cancel()
        approval = null
        taskDraft = null
        error = null
    }

    private suspend fun ownId(): String = requireNotNull((userProfile?.openId
        ?: withToken { client, token -> client.userProfile(token) }.also { userProfile = it }.openId)?.takeIf(String::isNotBlank)) {
        "无法确认当前用户，请重新登录后再试"
    }

    fun createTask() = work {
        val draft = taskDraft ?: return@work
        val source = message(draft.sourceId)
        val title = draft.title.trim()
        if (title.isEmpty()) return@work
        val assignee = ownId()
        try {
            withToken { client, token -> client.createTask(token, title, source.text.take(3000), assignee, draft.clientToken) }
        } catch (e: ImFailure) {
            if (e.code !in setOf(99991672, 99991679)) throw e
            taskDraft = draft.copy(needsAuthorization = true)
            notice(if (e.code == 99991672) "请在应用后台开通并发布用户权限 $taskScope，然后授权添加任务。"
                else "需要你授权小李以当前账号创建任务。")
            return@work
        }
        taskDraft = null
        feedback("已添加到我的任务")
    }

    fun authorizeTasks() = work {
        val editor = taskDraft ?: return@work
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        taskAuthorization = job
        try { grantTaskAccess(editor) }
        finally { if (taskAuthorization === job) taskAuthorization = null }
    }

    private suspend fun grantTaskAccess(editor: TaskDraft) {
        val keys = requireNotNull(account.keys)
        val identity = ownId()
        val client = api
        val token = withContext(Dispatchers.IO) {
            ImLogin(client).signIn(keys, scopes = userScopes + taskScope) { ticket ->
                withContext(Dispatchers.Main.immediate) { approval = ticket }
            }
        }
        val profile = withContext(Dispatchers.IO) { client.userProfile(token.access) }
        require(profile.openId == identity) { "请使用当前聊天账号授权任务" }
        persist(account.copy(token = token))
        taskDraft = editor.copy(needsAuthorization = false)
    }

    private fun send(block: suspend (Peer) -> ImMessage?) {
        val peer = selected ?: return
        if (sending) return
        sending = true
        error = null
        val own = session
        own.launch {
            try {
                val sent = block(peer)
                if (sent != null && messages.none { it.id == sent.id }) {
                    awaitingHistory.add(sent.id)
                    messages = listOf(sent) + messages
                }
                completedSends++
                try { loadHistory(peer) } catch (e: java.io.IOException) { historyError = e.message }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(e.message ?: "发送失败") }
            finally { if (session === own) sending = false }
        }
    }

    fun sendText() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val id = draftId
        val target = replyTo
        sendText(text, id, target) {
            if (draftId == id) { edit(""); setReply(null) }
        }
    }

    fun sendCommand(command: BotMenuItem.Command) {
        if (menu.none { it == command || (it is BotMenuItem.Group && command in it.commands) }) return
        sendText(command.label, UUID.randomUUID().toString())
    }

    private fun sendText(text: String, id: String, target: ImMessage? = null, onSent: () -> Unit = {}) {
        send { peer ->
            val parent = target?.let { message(it.id).id }
            withToken { client, access -> client.send(access, peer, "text", json("text" to text), id, parent) }
                .also { onSent() }
        }
    }

    fun sendAttachment(name: String, body: RequestBody, type: String, durationMs: Long = 0, done: () -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        val target = replyTo
        send { peer ->
            try {
                val parent = target?.let { message(it.id).id }
                val content = withToken { client, access -> client.upload(access, name, body, type, durationMs) }
                withToken { client, access -> client.send(access, peer, type, content, id, parent) }.also {
                    if (target != null && replyTo == target) setReply(null)
                }
            } finally { done() }
        }
    }

    fun reactionByline(entry: EmojiReaction): String {
        val names = entry.actors.mapNotNull { actor ->
            when {
                actor.type == "user" && actor.id == userProfile?.openId -> userProfile?.name ?: "我"
                (actor.type == "app" && actor.id == account.keys?.id) ||
                    ((actor.type == "user" || actor.type == "app") && actor.id == botProfile?.openId) -> botProfile?.name ?: selected?.name
                else -> null
            }
        }
        if (names.isEmpty()) return "${entry.count} 个回应"
        val label = names.joinToString("、")
        return if (names.size >= entry.count) label else "$label +${entry.count - names.size}"
    }

    private suspend fun updateReactions(ids: List<String>) {
        val owner = session
        val openId = userProfile?.openId
        for (batch in ids.chunked(20)) {
            val changes = withToken { client, token -> client.reactions(token, batch, openId) }
            if (owner !== session) return
            reactions = (reactions + changes).filterValues { it.isNotEmpty() }
        }
        reactionError = null
    }

    suspend fun observeReactions(visible: List<String>) {
        val owner = session
        while (owner === session && signedIn) {
            val ids = visible.filter { id -> messages.any { it.id == id && !it.deleted } }
            if (ids.isEmpty()) return
            try { reactionLock.withLock { updateReactions(ids) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (owner === session) reactionError = if (e is ImFailure && e.code == 99991672)
                    "当前授权无法查看消息表情" else "表情同步暂时不可用"
            }
            delay(if (reactionError == null) 1500L else 10000L)
        }
    }

    fun toggleReaction(messageId: String, type: String) {
        if (changingReaction || messages.none { it.id == messageId && !it.deleted }) return
        changingReaction = true
        val owner = session
        owner.launch {
            try {
                reactionLock.withLock {
                    val id = userProfile?.openId ?: withToken { client, token -> client.userProfile(token) }.also { userProfile = it }.openId
                    require(!id.isNullOrEmpty()) { "无法确认当前用户，请重新登录后再试" }
                    withToken { client, token -> client.toggleReaction(token, messageId, type, id) }
                    updateReactions(listOf(messageId))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice(e.message ?: "更新表情失败") }
            finally { if (owner === session) changingReaction = false }
        }
    }

    suspend fun thumbnail(message: ImMessage, resource: Resource): Bitmap = imageSlots.withPermit {
        require(resource.type == "image" && messages.any { it.id == message.id && it.contains(resource) })
        val key = message.id to resource.key
        thumbnails.get(key)?.let { return@withPermit it }
        val owner = session
        val bitmap = withToken { client, token ->
            val file = File.createTempFile("thumb-", ".tmp", getApplication<Application>().cacheDir)
            try {
                file.outputStream().use { client.download(token, message, it, resource) }
                decodeImage(file, 768) ?: throw java.io.IOException("图片格式无法预览")
            } finally { file.delete() }
        }
        if (session !== owner) throw CancellationException()
        thumbnails.put(key, bitmap)
        bitmap
    }

    fun preview(message: ImMessage, resource: Resource, ready: (File) -> Unit) {
        if (messages.none { it.id == message.id && it.contains(resource) }) return
        work {
            val file = File.createTempFile("preview-", ".tmp", getApplication<Application>().cacheDir)
            try {
                withToken { client, token -> file.outputStream().use { client.download(token, message, it, resource) } }
                ready(file)
            } catch (e: Exception) { file.delete(); throw e }
        }
    }

    fun save(message: ImMessage, uri: Uri) {
        if (message !in messages) return
        work {
            withToken { client, access ->
                val resolver = getApplication<Application>().contentResolver
                requireNotNull(resolver.openOutputStream(uri, "wt")).use { client.download(access, message, it) }
            }
        }
    }
}
