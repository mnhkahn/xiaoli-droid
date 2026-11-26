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

internal class FeishuChannelStore(context: Context) {
    private val prefs = context.getSharedPreferences("feishu", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("xiaoli-feishu-channels", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            "xiaoli-feishu-channels",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
                generateKey()
            }
    }

    fun read(): List<FeishuChannel> =
        runCatching {
                val bytes =
                    Base64.decode(prefs.getString("channels", null) ?: return emptyList(), Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                    )
                    decodeChannels(String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
                }
            }
            .getOrDefault(emptyList())

    private fun encrypted(token: String): String {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
    }

    fun save(channels: List<FeishuChannel>) {
        check(prefs.edit().putString("channels", encrypted(encodeChannels(channels))).commit())
    }
}
