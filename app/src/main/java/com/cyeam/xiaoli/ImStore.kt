package com.cyeam.xiaoli

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal data class LoginState(val keys: AppKeys? = null, val token: UserToken? = null, val peer: Peer? = null, val pending: Approval? = null)

internal class ImStore(context: Context) {
    private val prefs = context.getSharedPreferences("open-platform", Context.MODE_PRIVATE)
    var active: Brand?
        get() = prefs.getString("active", null)?.let(Brand::valueOf)
        set(value) { prefs.edit().putString("active", value?.name).apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("xiaoli-open-platform", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            "xiaoli-open-platform",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    private fun encrypted(token: String): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
    }

    @Synchronized
    fun read(brand: Brand): LoginState {
        val record = prefs.getString(brand.name, null) ?: return LoginState()
        val bytes = Base64.decode(record, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val keys = data.optJSONObject("app")?.let { AppKeys(it.getString("id"), it.getString("secret")) }
        val token = data.optJSONObject("user")?.let { UserToken(it.getString("access"), it.getString("refresh"), it.getLong("until")) }
        val peer = data.optJSONObject("chat")?.let { Peer(it.getString("id"), it.getString("name")) }
        val pending = data.optJSONObject("pending")?.let { Approval(it.getString("url"), it.getString("code"),
            it.getBoolean("registration"), it.getString("device"), it.getLong("until"), it.getLong("interval"),
            Brand.entries.find { brand -> brand.name == it.optString("registrationPollBrand") }) }
        return LoginState(keys, token, peer, pending)
    }

    @Synchronized
    fun forget(brand: Brand) { check(prefs.edit().remove(brand.name).commit()) }

    @Synchronized
    fun write(brand: Brand, state: LoginState) {
        val data = JSONObject()
        state.keys?.let { data.put("app", json("id" to it.id, "secret" to it.secret)) }
        state.token?.let { data.put("user", json("access" to it.access, "refresh" to it.refresh, "until" to it.until)) }
        state.peer?.let { data.put("chat", json("id" to it.id, "name" to it.name)) }
        state.pending?.let { ticket ->
            val pending = json("url" to ticket.url, "code" to ticket.code, "registration" to ticket.registration,
                "device" to ticket.device, "until" to ticket.until, "interval" to ticket.interval)
            ticket.registrationPollBrand?.let { pending.put("registrationPollBrand", it.name) }
            data.put("pending", pending)
        }
        check(prefs.edit().putString(brand.name, encrypted(data.toString())).commit())
    }
}
