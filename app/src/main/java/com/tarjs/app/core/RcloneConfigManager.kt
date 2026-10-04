package com.tarjs.app.core

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Safe rclone config management. The user-selected config is opened read-only,
 * copied into app-private storage, and all runtime operations use only the copy.
 */
class RcloneConfigManager(private val context: Context) {

    private val appPrivateConfigDir: File
        get() = File(context.filesDir, "rclone-configs").also { it.mkdirs() }

    fun importConfigFromUri(uri: Uri, configName: String): Result<File> = runCatching {
        val safeName = configName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "rclone.conf" }
        val target = File(appPrivateConfigDir, safeName)
        val temp = File(appPrivateConfigDir, "$safeName.importing")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output -> copyWithLimit(input, output, MAX_CONFIG_BYTES) }
            } ?: error("Unable to read selected rclone config")
            require(temp.length() > 0L) { "Selected rclone config is empty" }
            if (target.exists()) target.delete()
            check(temp.renameTo(target) || runCatching { temp.copyTo(target, overwrite = true); temp.delete(); true }.getOrDefault(false)) {
                "Unable to store private rclone config copy"
            }
            target
        } catch (failure: Throwable) {
            temp.delete()
            throw failure
        }
    }

    fun isEncrypted(configText: String): Boolean {
        val firstMeaningful = configText
            .removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') && !it.startsWith(';') }
            ?: return false
        return firstMeaningful.startsWith("RCLONE_ENCRYPT_V0:") || firstMeaningful.startsWith("RCLONE_ENCRYPT_V")
    }

    fun parseConfig(configText: String): List<RcloneRemote> = RcloneConfigParser.parse(configText)

    fun listRemotes(configPath: File): List<RcloneRemote> {
        require(isPrivateConfig(configPath)) { "Refusing to read rclone config outside app-private storage" }
        val text = configPath.readText(Charsets.UTF_8)
        if (isEncrypted(text)) return emptyList()
        return parseConfig(text).map { it.copy(name = normalizeRemote(it.name)) }
    }

    fun validateRemotePath(remote: String, path: String): Result<RemotePath> = runCatching {
        val normalizedRemote = normalizeRemote(remote)
        require(normalizedRemote.isNotBlank()) { "Remote name is empty" }
        require(!normalizedRemote.contains(':')) { "Malformed remote name" }
        val normalizedPath = path.replace('\\', '/').trim().trim('/')
        require(!normalizedPath.contains("::")) { "Malformed remote path" }
        val segments = normalizedPath.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." || it.contains(':') }) { "Unsafe remote path" }
        RemotePath(normalizedRemote, segments.joinToString("/"))
    }

    fun deletePrivateConfig(configPath: File) {
        if (isPrivateConfig(configPath)) runCatching { configPath.delete() }
    }

    fun privateConfig(name: String = "rclone.conf"): File = File(appPrivateConfigDir, name)

    private fun normalizeRemote(name: String): String = name.trim().removeSuffix(":").trim()

    private fun isPrivateConfig(file: File): Boolean {
        val root = appPrivateConfigDir.canonicalFile
        val candidate = file.canonicalFile
        return candidate.parentFile == root
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 2L * 1024L * 1024L
    }
}
