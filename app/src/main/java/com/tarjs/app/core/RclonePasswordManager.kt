package com.tarjs.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Wires rclone password validation to Android Keystore persistence.
 * Plaintext passwords are never logged and failed attempts never erase an
 * already remembered credential.
 */
class RclonePasswordManager(context: Context) {
    private val keystore = KeystoreSecretStore(context)

    suspend fun unlockAndMaybeRemember(
        configPath: String,
        password: String,
        remember: Boolean
    ): Result<String> = runCatching {
        require(password.isNotBlank()) { "Enter the rclone configuration password" }
        RcloneRuntime.unlock(File(configPath), password)
        if (remember) keystore.put(password)
        password
    }

    fun getRememberedPassword(): String? = keystore.get()
    fun forgetPassword() = keystore.clear()
    fun hasRememberedPassword(): Boolean = getRememberedPassword() != null

    suspend fun tryRememberedUnlock(configPath: String): Result<Boolean> = runCatching {
        val password = getRememberedPassword() ?: return@runCatching false
        RcloneRuntime.unlock(File(configPath), password)
        true
    }
}

data class RcloneEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = -1L
)

/**
 * Reflection wrapper around the gomobile AAR. Reflection keeps the Kotlin
 * sources resilient to gomobile's generated Java wrapper class shape while the
 * workflow still verifies org/rclone/gomobile/Gomobile.class is packaged.
 */
object RcloneRuntime {
    private val lock = Any()
    @Volatile private var initialized = false

    fun unlock(config: File, password: String?) = synchronized(lock) {
        require(config.isFile) { "Private rclone config is missing" }
        initialize()
        invokeOptionalNoArg("rcloneResetConfig")
        rpcChecked("options/set", JSONObject().put("main", JSONObject().put("AskPassword", false)))
        rpcChecked("config/setpath", JSONObject().put("path", config.absolutePath))
        if (!password.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", password))
        }
        rpcChecked("config/listremotes", JSONObject())
    }

    fun listRemotes(): List<String> = synchronized(lock) {
        val json = rpcChecked("config/listremotes", JSONObject())
        val arr = json.optJSONArray("remotes") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().removeSuffix(":").takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    fun list(remote: String, path: String): List<RcloneEntry> = synchronized(lock) {
        val json = rpcChecked(
            "operations/list",
            JSONObject().put("fs", normalizeFs(remote)).put("remote", path.trim().trim('/'))
        )
        val arr = json.optJSONArray("list") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val name = item.optString("Name", item.optString("Path").substringAfterLast('/'))
                if (name.isBlank()) continue
                add(RcloneEntry(name, item.optString("Path", name), item.optBoolean("IsDir", false), item.optLong("Size", -1L)))
            }
        }
    }

    fun copyToLocal(remote: String, remotePath: String, target: File) = synchronized(lock) {
        target.parentFile?.mkdirs()
        val params = JSONObject()
            .put("srcFs", normalizeFs(remote))
            .put("srcRemote", remotePath.trimStart('/'))
            .put("dstFs", target.parentFile!!.absolutePath)
            .put("dstRemote", target.name)
        rpcChecked("operations/copyfile", params)
        require(target.isFile && target.length() > 0L) { "rclone did not create ${target.name}" }
    }

    private fun normalizeFs(remote: String): String = remote.trim().removeSuffix(":") + ":"

    private fun initialize() {
        if (initialized) return
        val cls = Class.forName(CLASS_NAME)
        cls.methods.firstOrNull { it.name.equals("rcloneInitialize", ignoreCase = true) && it.parameterCount == 0 }
            ?.invoke(null) ?: error("Embedded rclone initialize API is unavailable")
        initialized = true
    }

    private fun invokeOptionalNoArg(name: String) {
        val cls = Class.forName(CLASS_NAME)
        cls.methods.firstOrNull { it.name.equals(name, ignoreCase = true) && it.parameterCount == 0 }?.invoke(null)
    }

    private fun rpcChecked(method: String, params: JSONObject): JSONObject {
        val cls = Class.forName(CLASS_NAME)
        val rpc = cls.methods.firstOrNull { it.name.equals("rcloneRPC", ignoreCase = true) && it.parameterCount == 2 }
            ?: error("Embedded rclone RPC API is unavailable")
        val raw = rpc.invoke(null, method, params.toString())
        val output = extractOutput(raw)
        val status = extractStatus(raw)
        if (status != null && status !in 200..299 && status != 0) {
            error(output.ifBlank { "rclone $method failed ($status)" })
        }
        if (output.isBlank()) return JSONObject()
        return runCatching { JSONObject(output) }.getOrElse { JSONObject().put("raw", output) }
    }

    private fun extractOutput(raw: Any?): String {
        if (raw == null) return ""
        if (raw is String) return raw
        val cls = raw.javaClass
        val getter = cls.methods.firstOrNull { it.parameterCount == 0 && (it.name.equals("getOutput", true) || it.name.equals("output", true)) }
        if (getter != null) return getter.invoke(raw)?.toString().orEmpty()
        val field = cls.fields.firstOrNull { it.name.equals("output", ignoreCase = true) }
        if (field != null) return field.get(raw)?.toString().orEmpty()
        return raw.toString()
    }

    private fun extractStatus(raw: Any?): Int? {
        if (raw == null || raw is String) return null
        val cls = raw.javaClass
        val getter = cls.methods.firstOrNull { it.parameterCount == 0 && (it.name.equals("getStatus", true) || it.name.equals("status", true)) }
        val field = cls.fields.firstOrNull { it.name.equals("status", ignoreCase = true) }
        val value = getter?.invoke(raw) ?: field?.get(raw)
        return (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull()
    }

    private const val CLASS_NAME = "org.rclone.gomobile.Gomobile"
}
