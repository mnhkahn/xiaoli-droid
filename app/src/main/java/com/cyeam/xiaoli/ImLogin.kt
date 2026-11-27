package com.cyeam.xiaoli

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.delay
import okhttp3.Credentials
import org.json.JSONArray
import org.json.JSONObject

internal val userScopes = listOf("im:chat:read", "im:message:readonly", "im:message.p2p_msg:get_as_user",
    "im:message.send_as_user", "im:message", "im:resource", "offline_access")

internal data class AppKeys(val id: String, val secret: String)
internal data class UserToken(val access: String, val refresh: String, val until: Long)
internal data class Approval(val url: String, val code: String, val registration: Boolean, val device: String, val until: Long, val interval: Long)
internal class OAuthFailure(val errorCode: String, reason: String) : IOException(reason)

private fun checked(response: JSONObject): JSONObject {
    val error = response.optString("error")
    if (error.isNotEmpty()) throw OAuthFailure(error, when (error) {
        "access_denied" -> "你已取消授权，可以重新登录"
        "expired_token" -> "确认码已过期，请重新登录"
        "invalid_grant" -> "授权已失效，请重新登录"
        else -> "开放平台授权失败（$error） ${response.optString("error_description").take(180)}"
    })
    return response
}

internal class ImLogin(private val api: ImApi) {
    private val registration = "/oauth/v1/app/registration"
    private val tokenPath = "/oauth/v3/token"

    private fun ticket(begin: JSONObject, registering: Boolean): Approval {
        checked(begin)
        val complete = begin.optString("verification_uri_complete")
        val url = api.brand.verificationUrl(complete.ifBlank { begin.getString("verification_uri") })
        val link = url.newBuilder()
        if (registering) {
            if (complete.isBlank() && url.encodedPath == "/page/launcher" && url.queryParameter("user_code").isNullOrBlank()) {
                link.setQueryParameter("user_code", begin.getString("user_code"))
            }
            val addons = json("preset" to false, "scopes" to json("user" to JSONArray(userScopes)))
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).use { it.write(addons.toString().toByteArray()) }
            link.setQueryParameter("from", "sdk").setQueryParameter("tp", "sdk")
                .setQueryParameter("source", "xiaoli-android").setQueryParameter("createOnly", "true")
                .setQueryParameter("name", "小李").setQueryParameter("addons", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()))
        }
        val lifetime = listOf("expire_in", "expires_in").map { begin.optLong(it, 0) }.firstOrNull { it > 0 } ?: 600L
        return Approval(link.build().toString(), begin.optString("user_code"), registering, begin.getString("device_code"),
            System.currentTimeMillis() + lifetime.coerceAtMost(3600) * 1000,
            begin.optLong("interval", 5).coerceIn(1, 60))
    }

    private suspend fun approve(ticket: Approval, show: suspend (Approval) -> Unit, poll: (String) -> JSONObject): JSONObject {
        show(ticket)
        var interval = ticket.interval
        while (true) {
            delay(interval * 1000)
            if (System.currentTimeMillis() >= ticket.until) throw OAuthFailure("expired_token", "确认码已过期，请重新登录")
            val reply = poll(ticket.device)
            val brand = reply.optJSONObject("user_info")?.optString("tenant_brand")
            if (!brand.isNullOrBlank() && !brand.equals(api.brand.name, ignoreCase = true))
                throw OAuthFailure("wrong_brand", "批准授权的账号与所选平台不同，请返回选择对应平台")
            when (reply.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> interval += 5
                else -> return checked(reply)
            }
        }
    }

    suspend fun register(pending: Approval? = null, show: suspend (Approval) -> Unit): AppKeys {
        val ticket = pending ?: ticket(api.form(registration, mapOf("action" to "begin", "archetype" to "PersonalAgent",
            "auth_method" to "client_secret", "request_user_info" to "open_id tenant_brand")), true)
        val keys = approve(ticket, show) { api.form(registration, mapOf("action" to "poll", "device_code" to it)) }
        return AppKeys(keys.getString("client_id"), keys.getString("client_secret"))
    }

    suspend fun signIn(keys: AppKeys, pending: Approval? = null, show: suspend (Approval) -> Unit): UserToken {
        val ticket = pending ?: ticket(api.form("/oauth/v1/device_authorization", mapOf("client_id" to keys.id,
            "scope" to userScopes.joinToString(" ")), Credentials.basic(keys.id, keys.secret)), false)
        val result = approve(ticket, show) {
            api.form(tokenPath, mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to it, "client_id" to keys.id, "client_secret" to keys.secret))
        }
        return readToken(result)
    }

    fun refresh(keys: AppKeys, previous: UserToken): UserToken {
        if (previous.refresh.isBlank()) throw OAuthFailure("invalid_grant", "请重新授权账号")
        val reply = checked(api.form(tokenPath, mapOf("grant_type" to "refresh_token", "client_id" to keys.id,
            "client_secret" to keys.secret, "refresh_token" to previous.refresh)))
        return readToken(reply, previous.refresh)
    }

    private fun readToken(reply: JSONObject, previous: String = ""): UserToken {
        require(reply.optString("token_type", "Bearer").equals("Bearer", ignoreCase = true)) {
            "此租户要求应用尚未支持的令牌绑定方式"
        }
        return UserToken(reply.getString("access_token"), reply.optString("refresh_token").ifBlank { previous },
            System.currentTimeMillis() + reply.optLong("expires_in", 7200) * 1000)
    }
}
