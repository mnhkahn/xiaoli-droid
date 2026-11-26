package com.cyeam.xiaoli

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Private app storage. A single atomic file per server, device and conversation. */
class Drafts(context: Context) {
    private val directory = File(context.filesDir, "drafts").apply { mkdirs() }
    private fun file(owner: String, session: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest("$owner/$session".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$key.json")
    }
    @Synchronized fun save(owner: String, session: String, text: String, photos: List<String>, remote: List<ChatImage>, request: String?,documents:List<String>,files:List<ChatFile>,search:Boolean) {
        val target = file(owner, session)
        if (text.isEmpty() && photos.isEmpty() && remote.isEmpty() && documents.isEmpty() && files.isEmpty()) { target.delete(); return }
        val json = JSONObject().put("search",search).put("text", text).put("photos", JSONArray(photos)).put("remote", JSONArray(remote.map { JSONObject().put("id",it.id).put("url",it.url) })).put("request", request).put("documents",JSONArray(documents)).put("files",JSONArray(files.map { JSONObject().put("id",it.id).put("name",it.name).put("url",it.url).put("mime",it.mime).put("bytes",it.bytes) }))
        val temporary = File.createTempFile("draft-", ".tmp", directory)
        try { temporary.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }; check(temporary.renameTo(target)) }
        finally { temporary.delete() }
    }
    @Synchronized fun load(owner: String, session: String): SavedDraft? = runCatching {
        val json = JSONObject(file(owner, session).readText())
        SavedDraft(json.optString("text"), json.optJSONArray("photos")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { File(it).isFile } }.orEmpty(), json.objects("remote").map { ChatImage(it.getString("id"),it.getString("url")) }, json.optString("request").takeIf { it.isNotBlank() && it != "null" },json.optJSONArray("documents")?.let { a -> (0 until a.length()).map { a.getString(it) }.filter { File(it).isFile } }.orEmpty(),json.objects("files").map(::parseFile),json.optBoolean("search"))
    }.getOrNull()
}
data class SavedDraft(val text:String, val photos:List<String>, val remote:List<ChatImage>, val request:String?,val documents:List<String>,val files:List<ChatFile>,val search:Boolean)
