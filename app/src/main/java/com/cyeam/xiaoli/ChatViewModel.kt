package com.cyeam.xiaoli

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val preferences=app.getSharedPreferences("preferences",0)
    private val credentials = Credentials(app)
    private val drafts = Drafts(app)
    private var draftJob:Job?=null
    private var historyJob:Job?=null
    private fun draftOwner()="${credentials.server}/${credentials.device}"
    private suspend fun saveDraft() {
        val s=state.value; val request=pendingId;val owner=draftOwner()
        if(s.chat.temporary)return
        withContext(Dispatchers.IO) { drafts.save(owner,s.chat.sessionId,s.draft,s.attachments,s.remoteImages,request,s.documents,s.remoteFiles,s.searchEnabled) }
    }
    private fun persistDraft() {
        draftJob?.cancel()
        draftJob=viewModelScope.launch { delay(200); saveDraft() }
    }
    private val mutable =
        MutableStateFlow(
            AppState(paired = credentials.token().isNotEmpty(), server = credentials.server,appearance=preferences.getString("appearance","system")?:"system",notifications=preferences.getBoolean("notifications",false),appLock=preferences.getBoolean("app_lock",false),speechRate=preferences.getFloat("speech_rate",1f))
        )
    val state = mutable.asStateFlow()
    var api = Api(credentials.server, credentials.device, credentials.token())
        private set

    private var streamJob: Job? = null
    private var initialLoad:Job?=null
    private val incoming = Channel<Pair<String, JSONObject>>(1024)
    private var pendingId: String? = null
    private val updater = Updates(app)
    var installation: File? = null
        private set

    init {
        viewModelScope.launch {
            while (isActive) {
                val first = incoming.receive()
                delay(40)
                val batch = mutableListOf(first)
                while (true) {
                    val event = incoming.tryReceive().getOrNull() ?: break
                    batch += event
                }
                mutable.update { old ->
                    var chat = old.chat
                    for ((id, event) in batch) {
                        if (chat.sessionId == id) chat = reduceEvent(chat, event)
                    }
                    old.copy(chat = chat, connected = true)
                }
            }
        }
        if (state.value.paired)
            initialLoad=action {
                refreshSessions()
                val id = credentials.session()
                if (id.isNotEmpty()) select(id)
            }
        viewModelScope.launch {
            delay(1500)
            runCatching { updater.check() }
                .getOrNull()
                ?.let { u -> mutable.update { it.copy(update = u) } }
        }
    }

    private fun action(block: suspend () -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update {
                    it.copy(error = e.message ?: "操作失败，请重试", loading = false, sending = false,voiceStage=if(it.voiceMode) "paused" else it.voiceStage)
                }
            }
        }

    fun speechRate(value:Float){val rate=value.coerceIn(0.5f,2f);preferences.edit().putFloat("speech_rate",rate).apply();mutable.update{it.copy(speechRate=rate)}}
    fun lockPreference(value:Boolean){mutable.update{it.copy(appLock=value)}}
    fun notificationPreference(value:Boolean) {preferences.edit().putBoolean("notifications",value).apply();mutable.update{it.copy(notifications=value)}}
    fun appearance(value:String) {
        require(value in listOf("system","light","dark"))
        preferences.edit().putString("appearance",value).apply()
        mutable.update{it.copy(appearance=value)}
    }

    fun search(value:Boolean){mutable.update{it.copy(searchEnabled=value&&!it.chat.temporary)};persistDraft()}
    fun error(message: String?) {
        mutable.update { it.copy(error = message) }
    }

    fun draft(text: String) {
        pendingId = null
        mutable.update { it.copy(draft = text) }
        persistDraft()
    }

    fun pair(server: String, rawCode: String) = action {
        mutable.update { it.copy(loading = true) }
        val base = validateServer(server, BuildConfig.DEBUG)
        val code =
            if (rawCode.trim().startsWith("{"))
                JSONObject(rawCode).let { json ->
                    val pairUrl = json.optString("pair_url")
                    require(sameOrigin(pairUrl, base)) { "配对码与服务器不匹配" }
                    json.getString("code")
                }
            else rawCode.trim()
        require(code.isNotEmpty()) { "请输入配对码" }
        val token = Api(base, credentials.device, "").pair(code, android.os.Build.MODEL)
        val connectedApi = Api(base, credentials.device, token)
        require(connectedApi.json("/mobile/v1/capabilities").optInt("protocol") == 1) {
            "服务端协议版本不兼容"
        }
        withContext(Dispatchers.IO) { credentials.save(base, token) }
        api = connectedApi
        mutable.update { it.copy(paired = true, server = base, loading = false) }
        refreshSessions()
        newSession()
    }

    fun disconnect() {
        notificationPreference(false)
        getApplication<Application>().stopService(android.content.Intent(getApplication<Application>(),TaskNotifications::class.java))
        streamJob?.cancel()
        credentials.clear()
        mutable.update { AppState(server = credentials.server) }
    }

    suspend fun refreshSessions() {
        val filter=state.value
        val json = api.json("/mobile/v1/sessions?q=${URLEncoder.encode(filter.sessionQuery, "UTF-8")}&view=${filter.sessionView}")
        mutable.update {
            it.copy(
                sessions =
                    json.objects("sessions").map { s ->
                        SessionItem(s.getString("id"), s.getString("title"),s.optBoolean("pinned"),s.optBoolean("archived"),s.optBoolean("deleted"))
                    }
            )
        }
    }

    fun history() = action { refreshSessions() }
    fun filterHistory(query:String=state.value.sessionQuery, view:String=state.value.sessionView) {
        mutable.update{it.copy(sessionQuery=query,sessionView=view)}
        historyJob?.cancel()
        historyJob=action { delay(200); refreshSessions() }
    }
    fun manageSession(session:SessionItem, field:String, value:Any)=action {
        val snapshot=api.json("/mobile/v1/sessions/${session.id}","PATCH",JSONObject().put(field,value))
        if(state.value.chat.sessionId==session.id) mutable.update{it.copy(chat=parseSnapshot(snapshot))}
        refreshSessions()
    }
    fun fork(message:ChatMessage,text:String?=null)=action {
        require(!state.value.chat.busy && !state.value.sending){"请先完成当前任务"}
        val body=JSONObject().put("message_id",message.id).put("request_id",UUID.randomUUID().toString())
        if(text!=null)body.put("text",text)
        val snapshot=api.json("/mobile/v1/sessions/${state.value.chat.sessionId}/fork","POST",body)
        select(snapshot.getString("id"),snapshot)
        refreshSessions()
    }
    fun restoreCurrent()=action{val snapshot=api.json("/mobile/v1/sessions/${state.value.chat.sessionId}","PATCH",JSONObject().put("archived",false).put("deleted",false));mutable.update{it.copy(chat=parseSnapshot(snapshot))};refreshSessions()}
    suspend fun export():String = api.json("/mobile/v1/sessions/${state.value.chat.sessionId}/export").getString("markdown")
    fun removeRemoteImage(id:String) { pendingId=null; mutable.update{it.copy(remoteImages=it.remoteImages.filterNot{image->image.id==id})};persistDraft() }


    fun newSession(temporary:Boolean=false) = action {
        if(state.value.loading||state.value.sending)return@action
        require(!state.value.voiceMode&&!state.value.recording&&!state.value.transcribing){"请先结束当前语音输入"}
        mutable.update { it.copy(loading = true) }
        val snapshot = api.json("/mobile/v1/sessions", "POST",JSONObject().put("temporary",temporary))
        select(snapshot.getString("id"), snapshot)
    }

    fun loadModels() = viewModelScope.launch {
        if(state.value.modelsLoading)return@launch
        mutable.update{it.copy(modelsLoading=true,modelsError=null)}
        try {
            val response=api.json("/mobile/v1/models")
            val models=response.objects("models").map{FreeModel(it.getString("id"),it.getString("name"),it.getString("provider"))}.distinctBy{it.id}
            mutable.update{it.copy(models=models,modelsLoading=false)}
        } catch(e:CancellationException){throw e}
        catch(e:Exception){mutable.update{it.copy(modelsLoading=false,modelsError=e.message?:"无法获取模型")}}
    }

    fun defaultModel(model:String)=action {api.json("/mobile/v1/profile","PATCH",JSONObject().put("default_model",model));error("已保存新对话默认模型")}
    fun selectModel(model:String)=action {
        if(state.value.chat.sessionId.isBlank()){val snapshot=api.json("/mobile/v1/sessions","POST");select(snapshot.getString("id"),snapshot)}
        val id=state.value.chat.sessionId
        val snapshot=api.json("/mobile/v1/sessions/$id","PATCH",JSONObject().put("model",model.trim()))
        mutable.update{it.copy(chat=parseSnapshot(snapshot))}
    }

    fun openSession(id:String):Job {credentials.session(id);return action{initialLoad?.join();select(id)}}
    fun receiveShare(text:String?,uris:List<Uri>)=action {
        initialLoad?.join()
        if(!text.isNullOrBlank())draft((state.value.draft+"\n"+text).trim())
        val resolver=getApplication<Application>().contentResolver
        val images=uris.filter{resolver.getType(it)?.startsWith("image/")==true}
        if(images.isNotEmpty())addImages(images).join()
        val documents=uris-images.toSet()
        if(documents.isNotEmpty())addDocuments(documents).join()
    }

    private suspend fun select(id: String, snapshot: JSONObject? = null) {
        require(!state.value.sending && !state.value.recording && !state.value.transcribing && !state.value.voiceMode){"请先完成当前输入或结束语音对话"}
        draftJob?.cancelAndJoin()
        saveDraft()
        streamJob?.cancelAndJoin()
        val previous=state.value.chat
        mutable.update {
            it.copy(chat = ChatState(sessionId = id), loading = true, connected = false)
        }
        val data = snapshot ?: api.json("/mobile/v1/sessions/$id")
        val next=parseSnapshot(data)
        if(previous.temporary && previous.sessionId!=id){
            api.json("/mobile/v1/sessions/${previous.sessionId}/discard","POST")
            (state.value.attachments+state.value.documents).forEach{File(it).delete()}
        }
        credentials.session(if(next.temporary) "" else id)
        if(TaskNotifications.visibleSession!=null)TaskNotifications.visibleSession=id
        var saved=if(next.temporary)null else withContext(Dispatchers.IO){drafts.load(draftOwner(),id)}
        if(saved?.request!=null && next.messages.any{it.id==saved?.request}){
            val accepted=saved!!;val owner=draftOwner()
            withContext(Dispatchers.IO){(accepted.photos+accepted.documents).forEach{File(it).delete()};drafts.save(owner,id,"",emptyList(),emptyList(),null,emptyList(),emptyList(),false)}
            saved=null
        }
        val branch=data.optJSONObject("draft")
        val remote=saved?.remote ?: branch?.objects("images")?.map{ChatImage(it.getString("id"),it.getString("url"))}.orEmpty()
        pendingId=saved?.request
        mutable.update { it.copy(chat = parseSnapshot(data), loading = false,draft=saved?.text?:branch?.optString("text").orEmpty(),attachments=saved?.photos.orEmpty(),remoteImages=remote,documents=saved?.documents.orEmpty(),remoteFiles=saved?.files?:branch?.objects("files")?.map(::parseFile).orEmpty(),searchEnabled=!next.temporary&&(saved?.search?:false)) }
        listen(id)
    }

    private fun listen(id: String) {
        streamJob?.cancel()
        streamJob =
            viewModelScope.launch {
                var failures = 0
                while (isActive && state.value.chat.sessionId == id) {
                    try {
                        api.events(id, state.value.chat.revision) { ev ->
                            if (!incoming.trySend(id to ev).isSuccess)
                                throw java.io.IOException("重新同步会话")
                        }
                        failures = 0
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        mutable.update { it.copy(connected = false) }
                        if (e is ApiError && e.status == 401) {
                            error(e.message)
                            break
                        }
                        failures++
                    }
                    delay((1000L shl failures.coerceAtMost(4)).coerceAtMost(15000))
                }
            }
    }

    fun resume() {
        val id = state.value.chat.sessionId
        if (id.isNotEmpty() && streamJob?.isActive != true) listen(id)
    }

    fun pause() {
        action {draftJob?.cancelAndJoin();saveDraft()}
        streamJob?.cancel()
        mutable.update { it.copy(connected = false) }
    }

    fun older() = action {
        val chat = state.value.chat
        if (chat.before <= 0) return@action
        val page =
            parseSnapshot(api.json("/mobile/v1/sessions/${chat.sessionId}?before=${chat.before}"))
        mutable.update { old ->
            if (old.chat.sessionId != chat.sessionId) old
            else
                old.copy(
                    chat =
                        old.chat.copy(
                            messages = (page.messages + old.chat.messages).distinctBy { it.id },
                            before = page.before,
                        )
                )
        }
    }

    fun send() = action {
        val start = state.value
        if (start.sending || start.chat.busy || start.preparing) return@action
        if (start.draft.isBlank() && start.attachments.isEmpty() && start.remoteImages.isEmpty() && start.documents.isEmpty() && start.remoteFiles.isEmpty()) return@action
        mutable.update { it.copy(sending = true) }
        if (start.chat.sessionId.isEmpty()) {
            val s = api.json("/mobile/v1/sessions", "POST")
            // Preserve the unsent composer while establishing its first session.
            mutable.update { it.copy(sending=false) }
            select(s.getString("id"), s)
            mutable.update { it.copy(sending=true,draft=start.draft,attachments=start.attachments,remoteImages=start.remoteImages,documents=start.documents,remoteFiles=start.remoteFiles) }
        }
        val id = state.value.chat.sessionId
        val requestId = pendingId ?: UUID.randomUUID().toString().also { pendingId = it }
        saveDraft()
        if(state.value.voiceMode) mutable.update{it.copy(voiceRequestId=requestId,voiceStage="thinking")}
        api.multipart(
            "/mobile/v1/sessions/$id/messages",
            mapOf("text" to start.draft, "request_id" to requestId,"reuse_images" to JSONArray(start.remoteImages.map{it.id}).toString(),"reuse_files" to JSONArray(start.remoteFiles.map{it.id}).toString(),"search" to start.searchEnabled.toString()),
            start.attachments.map { "images" to File(it) }+start.documents.map{"files" to File(it)},
        )
        (start.attachments+start.documents).forEach { File(it).delete() }
        pendingId = null
        mutable.update {
            it.copy(
                draft = if (it.draft == start.draft) "" else it.draft,
                attachments = it.attachments - start.attachments.toSet(),
                remoteImages=it.remoteImages.filterNot{image->start.remoteImages.any{sent->sent.id==image.id}},
                documents=it.documents-start.documents.toSet(),
                remoteFiles=it.remoteFiles.filterNot{file->start.remoteFiles.any{sent->sent.id==file.id}},
                sending = false,
            )
        }
        saveDraft()
        refreshSessions()
    }

    fun reply(interaction: Interaction, text: String) = action {
        if (state.value.sending || text.isBlank()) return@action
        mutable.update { it.copy(sending = true) }
        try {
            api.json(
                "/mobile/v1/sessions/${state.value.chat.sessionId}/reply",
                "POST",
                JSONObject()
                    .put("interaction_id", interaction.id)
                    .put("text", text)
                    .put("request_id", "reply-" + interaction.id),
            )
            mutable.update { it.copy(sending = false) }
        } finally {
            mutable.update { it.copy(sending = false) }
        }
    }

    fun stop() = action {
        api.json("/mobile/v1/sessions/${state.value.chat.sessionId}/cancel", "POST")
    }

    fun addDocuments(uris:List<Uri>)=action {
        if(state.value.preparing)return@action
        mutable.update{it.copy(preparing=true)}
        val copied=mutableListOf<String>()
        try {
            val room=(4-state.value.documents.size-state.value.remoteFiles.size).coerceAtLeast(0)
            withContext(Dispatchers.IO){
                val app=getApplication<Application>()
                for(uri in uris.take(room)) {
                    require(uri.scheme=="content"){"文件来源不受支持"}
                    val name=app.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{cursor->if(cursor.moveToFirst())cursor.getString(0) else null}?:"document.txt"
                    val safe=File(name.replace('\\','/')).name.take(150)
                    val directory=File(if(state.value.chat.temporary) app.cacheDir else app.filesDir,"${if(state.value.chat.temporary) "temporary" else "attachments"}/${UUID.randomUUID()}").apply{mkdirs()}
                    val target=File(directory,safe)
                    try {app.contentResolver.openInputStream(uri)!!.use{input->target.outputStream().use{output->
                        val buffer=ByteArray(65536);var count=0L
                        while(true){val n=input.read(buffer);if(n<0)break;count+=n;require(count<=8*1024*1024){"单个文档不能超过 8 MB"};output.write(buffer,0,n)}
                    }};copied+=target.path} catch(e:Exception){target.delete();directory.delete();throw e}
                }
            }
            pendingId=null
            mutable.update{it.copy(documents=it.documents+copied)}
            persistDraft()
        } catch(e:Exception){copied.forEach{File(it).delete()};throw e}
        finally {mutable.update{it.copy(preparing=false)}}
    }
    fun removeDocument(path:String){if(state.value.sending)return;File(path).delete();pendingId=null;mutable.update{it.copy(documents=it.documents-path)};persistDraft()}
    fun removeRemoteFile(id:String){pendingId=null;mutable.update{it.copy(remoteFiles=it.remoteFiles.filterNot{f->f.id==id})};persistDraft()}

    fun addImages(uris: List<Uri>) = action {
        if (state.value.preparing) return@action
        mutable.update { it.copy(preparing = true) }
        try {
            val room = (4 - state.value.attachments.size-state.value.remoteImages.size).coerceAtLeast(0)
            val paths =
                withContext(Dispatchers.IO) { uris.take(room).map { uri -> prepareImage(uri) } }
            pendingId = null
            mutable.update { it.copy(attachments = it.attachments + paths) }
            persistDraft()
        } finally {
            mutable.update { it.copy(preparing = false) }
        }
    }

    private fun prepareImage(uri: Uri): String {
        require(uri.scheme == "content") { "图片来源不受支持" }
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        val dir = File(if(state.value.chat.temporary) context.cacheDir else context.filesDir, if(state.value.chat.temporary) "temporary" else "attachments").apply { mkdirs() }
        val source = File.createTempFile("source-", ".image", dir)
        try {
            resolver.openInputStream(uri)!!.use { input ->
                source.outputStream().use { out ->
                    val buffer = ByteArray(65536)
                    var count = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        require(count < 40_000_000) { "图片过大" }
                        out.write(buffer, 0, n)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取图片" }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            var bitmap =
                BitmapFactory.decodeFile(
                    source.path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: throw IllegalArgumentException("无法读取图片")
            val exif = androidx.exifinterface.media.ExifInterface(source)
            val orientation =
                exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
            val matrix = android.graphics.Matrix()
            when (orientation) {
                2 -> matrix.setScale(-1f, 1f)
                3 -> matrix.setRotate(180f)
                4 -> matrix.setScale(1f, -1f)
                5 -> {
                    matrix.setRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                6 -> matrix.setRotate(90f)
                7 -> {
                    matrix.setRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                8 -> matrix.setRotate(270f)
            }
            if (!matrix.isIdentity) {
                val rotated =
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            try {
                val file = File.createTempFile("image-", ".jpg", dir)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                require(file.length() <= 3 * 1024 * 1024) { "图片压缩后仍过大" }
                return file.path
            } finally {
                bitmap.recycle()
            }
        } finally {
            source.delete()
        }
    }

    fun removeImage(path: String) {
        if(state.value.sending)return
        File(path).delete()
        pendingId = null
        mutable.update { it.copy(attachments = it.attachments - path) }
        persistDraft()
    }

    fun recording(value: Boolean) {
        mutable.update { it.copy(recording = value) }
    }

    fun voice(mode:Boolean,stage:String=if(mode)"listening" else "") {mutable.update{it.copy(voiceMode=mode,voiceStage=stage,voiceRequestId=if(mode)it.voiceRequestId else "")}}
    fun transcribe(file: File,autoSend:Boolean=false) = action {
        mutable.update { it.copy(transcribing = true,voiceStage=if(autoSend)"transcribing" else it.voiceStage) }
        try {
            val json = api.multipart("/mobile/v1/transcribe", emptyMap(), listOf("audio" to file))
            val text=json.getString("text").trim()
            require(text.isNotEmpty()){"未识别到语音，请重试"}
            draft((state.value.draft + " " + text).trim())
            if(autoSend&&state.value.voiceMode)send().join()
        } finally {
            file.delete()
            mutable.update { it.copy(transcribing = false) }
        }
    }

    fun checkUpdate() = action {
        val info = updater.check()
        mutable.update { it.copy(update = info, error = if (info == null) "已是最新版本" else null) }
    }

    fun dismissUpdate() {
        mutable.update { it.copy(update = null) }
    }

    fun downloadUpdate() = action {
        val info = state.value.update ?: return@action
        mutable.update { it.copy(downloadProgress = 0) }
        try {
            installation =
                updater.download(info) { p -> mutable.update { it.copy(downloadProgress = p) } }
            mutable.update { it.copy(update = null) }
        } finally {
            mutable.update { it.copy(downloadProgress = null) }
        }
    }

    override fun onCleared(){
        val current=state.value
        if(current.chat.temporary){api.discardTemporary(current.chat.sessionId);(current.attachments+current.documents).forEach{File(it).delete()}}
        super.onCleared()
    }

    fun install() {
        installation?.let {
            updater.install(it)
            installation = null
        }
    }
}
