package com.cyeam.xiaoli

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    val device: String =
        prefs.getString("device", null)
            ?: ("android-" + UUID.randomUUID()).also {
                prefs.edit().putString("device", it).commit()
            }
    val server
        get() = prefs.getString("server", "https://xiaoli-server.fly.dev")!!

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("xiaoli-device", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            "xiaoli-device",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    fun token(): String = readSecret("credential")

    internal fun readSecret(name: String): String =
        runCatching {
                val bytes =
                    Base64.decode(prefs.getString(name, null) ?: return "", Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                    )
                    String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
                }
            }
            .getOrDefault("")

    private fun encrypted(token: String): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
    }

    internal fun saveSecret(name: String, value: String) {
        check(prefs.edit().putString(name, encrypted(value)).commit())
    }

    fun save(server: String, token: String) {
        check(
            prefs
                .edit()
                .putString("server", server)
                .putString("credential", encrypted(token))
                .remove("session")
                .commit()
        )
    }

    fun session(): String = prefs.getString("session", "").orEmpty()

    fun session(id: String) {
        prefs.edit().putString("session", id).apply()
    }

    fun clear() {
        prefs.edit().remove("credential").remove("session").commit()
    }
}
