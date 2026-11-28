package com.cyeam.xiaoli

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    var peers by mutableStateOf(emptyList<Peer>()); private set
    var selected by mutableStateOf<Peer?>(null); private set
    var messages by mutableStateOf(emptyList<ImMessage>()); private set
    var older by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var historyError by mutableStateOf<String?>(null); private set
    var draft by mutableStateOf(""); private set
    private var draftId = UUID.randomUUID().toString()

    init { store.active?.let { enter(it, restore = true) } }

    private fun clearWork() {
        session.cancel()
        session = newScope()
        approval = null
        busy = false
        sending = false
        error = null
        historyError = null
        peers = emptyList()
        messages = emptyList()
        older = null
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
            store.active = value
            if (signedIn && selected == null) listPeers()
            else if (!signedIn && (!restore || account.pending != null)) login(resumePending = restore)
        } catch (e: Exception) { signedIn = false; selected = null; notice(e.message ?: "本机授权不可读") }
    }

    fun logout(resetApp: Boolean = false) {
        clearWork()
        if (resetApp) store.forget(requireNotNull(brand)) else persist(LoginState(keys = account.keys))
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
        val token = withContext(Dispatchers.IO) { flow.signIn(keys, pending?.takeUnless { it.registration }, ::show) }
        approval = null
        persist(LoginState(keys, token))
        peers = withToken { client, access -> client.peers(access) }
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
                        peers = emptyList()
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

    fun listPeers() = work { peers = withToken { client, access -> client.peers(access) } }

    fun select(peer: Peer?) {
        if (sending || (peer != null && peer !in peers)) return
        clearWork()
        persist(account.copy(peer = peer))
        if (peer == null) listPeers()
    }

    private suspend fun loadHistory(peer: Peer, cursor: String? = null) = historyLock.withLock {
        if (selected != peer || !signedIn || (cursor != null && cursor != older)) return@withLock
        val page = withToken { client, access -> client.messages(access, peer, cursor) }
        // The screen's lifecycle coroutine can outlive the selected connection.
        if (selected != peer || !signedIn) return@withLock
        val items = page.items
        if (cursor == null) {
            val old = messages
            val contiguous = old.any { m -> items.any { it.id == m.id } }
            messages = if (contiguous) (items + old).distinctBy { it.id } else items
            if (!contiguous) older = page.next
        } else {
            messages = (messages + items).distinctBy { it.id }
            older = page.next.takeUnless { it == cursor }
        }
        historyError = null
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

    private fun send(block: suspend (Peer) -> Unit) {
        val peer = selected ?: return
        if (sending) return
        sending = true
        error = null
        val own = session
        own.launch {
            try {
                block(peer)
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
        send { peer ->
            withToken { client, access -> client.send(access, peer, "text", json("text" to text), id) }
            if (draftId == id) edit("")
        }
    }

    fun sendAttachment(name: String, body: RequestBody, type: String, durationMs: Long = 0, done: () -> Unit = {}) {
        val id = UUID.randomUUID().toString()
        send { peer ->
            try {
                val content = withToken { client, access -> client.upload(access, name, body, type, durationMs) }
                withToken { client, access -> client.send(access, peer, type, content, id) }
            } finally { done() }
        }
    }

    fun preview(message: ImMessage, ready: (File) -> Unit) {
        if (message !in messages) return
        work {
            val file = File.createTempFile("preview-", ".tmp", getApplication<Application>().cacheDir)
            try {
                withToken { client, token -> file.outputStream().use { client.download(token, message, it) } }
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
