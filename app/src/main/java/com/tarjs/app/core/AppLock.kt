package com.tarjs.app.core

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AppLock(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("lock", Context.MODE_PRIVATE)
    val configured get() = prefs.contains("hash")
    fun setPasscode(value: String) { require(value.length >= 4); val salt=ByteArray(16).also(SecureRandom()::nextBytes); prefs.edit().putString("salt",Base64.encodeToString(salt,0)).putString("hash",Base64.encodeToString(derive(value,salt),0)).apply() }
    fun verify(value: String): Boolean { val salt=Base64.decode(prefs.getString("salt","") ?: "",0); val expected=Base64.decode(prefs.getString("hash","") ?: "",0); return expected.contentEquals(derive(value,salt)) }
    private fun derive(value:String,salt:ByteArray):ByteArray = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(value.toCharArray(),salt,120_000,256)).encoded
}
