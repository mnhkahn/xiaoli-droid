package com.cyeam.xiaoli

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class ApiError(val status: Int, message: String) : IOException(message)

class Api(val server: String, private val device: String, private val token: String) {
    companion object {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build()
    }

    private fun request(path: String) =
        Request.Builder()
            .url(server + path)
            .header("Device-Id", device)
            .header("Authorization", "Bearer $token")

    fun imageRequest(url: String): Request {
        val resolved = clientUrl(url)
        return Request.Builder()
            .url(resolved)
            .apply {
                if (sameOrigin(resolved, server)) {
                    header("Device-Id", device)
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
    }

    fun clientUrl(url: String): String {
        val target = server.toHttpUrl().resolve(url) ?: throw IllegalArgumentException("图片地址无效")
        require(
            target.scheme == "https" ||
                BuildConfig.DEBUG && target.host in listOf("10.0.2.2", "localhost", "127.0.0.1")
        )
        return target.toString()
    }

    suspend fun json(path: String, method: String = "GET", body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val req =
                request(path)
                    .apply {
                        if (method != "GET")
                            method(
                                method,
                                (body ?: JSONObject())
                                    .toString()
                                    .toRequestBody("application/json".toMediaType()),
                            )
                    }
                    .build()
            execute(req).use { response -> decode(response) }
        }

    suspend fun multipart(
        path: String,
        fields: Map<String, String>,
        files: List<Pair<String, File>>,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .apply {
                        fields.forEach { (k, v) -> addFormDataPart(k, v) }
                        files.forEach { (field, file) ->
                            addFormDataPart(
                                field,
                                file.name,
                                file.asRequestBody(
                                    (when(field){"audio"->"audio/ogg";"images"->"image/jpeg";else->"application/octet-stream"})
                                        .toMediaType()
                                ),
                            )
                        }
                    }
                    .build()
            execute(request(path).post(body).build()).use(::decode)
        }

    fun discardTemporary(id:String){
        client.newCall(request("/mobile/v1/sessions/$id/discard").post("{}".toRequestBody("application/json".toMediaType())).build()).enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){}
            override fun onResponse(call:Call,response:Response){response.close()}
        })
    }
    suspend fun download(url:String, limit:Long=20L*1024*1024):ByteArray=withContext(Dispatchers.IO) {
        execute(imageRequest(url)).use{response->
            if(!response.isSuccessful) throw ApiError(response.code,errorText(response.code))
            val body=response.body?:throw IOException("文件为空")
            require(body.contentLength()<=limit){"文件过大"}
            body.byteStream().use{input->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536);var total=0L
                while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=limit){"文件过大"};output.write(buffer,0,n)}
                output.toByteArray()
            }
        }
    }

    suspend fun pair(code: String, name: String): String {
        val payload =
            JSONObject()
                .put("code", code)
                .put("device_id", device)
                .put("device_name", name)
                .put("device_kind", "android")
        return json("/xiaozhi/pair", "POST", payload).getJSONObject("websocket").getString("token")
    }

    suspend fun events(session:String,after:Long,receive:(JSONObject)->Unit):Unit=eventStream("/mobile/v1/sessions/$session/events?after=$after",receive)
    suspend fun activity(receive:(JSONObject)->Unit):Unit=eventStream("/mobile/v1/activity",receive)
    private suspend fun eventStream(path:String,receive:(JSONObject)->Unit):Unit=
        suspendCancellableCoroutine { continuation ->
            val call =
                client
                    .newBuilder()
                    .callTimeout(0, TimeUnit.MILLISECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .build()
                    .newCall(
                        request(path)
                            .header("Accept", "text/event-stream")
                            .build()
                    )
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { r ->
                                if (!r.isSuccessful) throw ApiError(r.code, errorText(r.code))
                                val source = r.body?.source() ?: throw IOException("连接中断")
                                var data = StringBuilder()
                                while (continuation.isActive && !source.exhausted()) {
                                    val line = source.readUtf8Line() ?: break
                                    if (line.isEmpty()) {
                                        if (data.isNotEmpty()) {
                                            receive(JSONObject(data.toString()))
                                            data = StringBuilder()
                                        }
                                    } else if (line.startsWith("data:")) {
                                        if (data.isNotEmpty()) data.append('\n')
                                        data.append(line.substring(5).trimStart())
                                        if (data.length > 2_000_000) throw IOException("响应过大")
                                    }
                                }
                            }
                            if (continuation.isActive) continuation.resume(Unit)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            )
        }

    private fun decode(response: Response): JSONObject {
        if (!response.isSuccessful) {
            val detail=runCatching{JSONObject(response.body?.string().orEmpty()).optString("error")}.getOrDefault("")
            throw ApiError(response.code,if(detail.isNotBlank()&&detail.length<200)detail else errorText(response.code))
        }
        return JSONObject(response.body?.string().orEmpty())
    }

    private fun errorText(code: Int) =
        when (code) {
            401 -> "连接凭证已失效，请重新配对"
            404 -> "接口或会话不存在，请确认服务端已更新"
            409 -> "当前会话状态已变化，请刷新后重试"
            413 -> "图片或消息过大"
            429 -> "服务繁忙，请稍后重试"
            503 -> "服务暂不可用，请检查服务端配置"
            else -> "请求失败（$code），请重试"
        }

    private suspend fun execute(req: Request): Response = suspendCancellableCoroutine { c ->
        val call = client.newCall(req)
        c.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (c.isActive) c.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (c.isActive) c.resume(response) else response.close()
                }
            }
        )
    }
}

fun sameOrigin(a: String, b: String): Boolean =
    runCatching {
            val aa = a.toHttpUrl()
            val bb = b.toHttpUrl()
            aa.scheme == bb.scheme && aa.host == bb.host && aa.port == bb.port
        }
        .getOrDefault(false)
