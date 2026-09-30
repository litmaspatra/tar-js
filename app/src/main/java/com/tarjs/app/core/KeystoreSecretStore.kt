package com.tarjs.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores rclone's remembered config password encrypted by an Android Keystore key. */
class KeystoreSecretStore(private val context: Context, private val alias: String = "tarjs.rclone.config") {
    private val prefs get() = context.getSharedPreferences("secure-secrets", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = ks.getKey(alias, null) as? SecretKey
            if (existing != null) return existing
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build())
            }.generateKey()
        }

    fun put(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        prefs.edit().putString("value", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)).apply()
    }

    fun get(): String? {
        val iv = prefs.getString("value", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val ciphertext = prefs.getString("ciphertext", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }.doFinal(ciphertext).toString(StandardCharsets.UTF_8) }.getOrNull()
    }

    fun clear() { prefs.edit().clear().apply() }
}
