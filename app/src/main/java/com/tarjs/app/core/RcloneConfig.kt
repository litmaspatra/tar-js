package com.tarjs.app.core

/** A parsed rclone remote. Secrets are intentionally never exposed by this model. */
data class RcloneRemote(val name: String, val type: String, val options: Map<String, String>) {
    val isEncrypted: Boolean get() = type.equals("crypt", ignoreCase = true)
    val backend: String? get() = options["remote"]?.substringBefore(":")
}

object RcloneConfigParser {
    /**
     * Parses an rclone INI config. It accepts UTF-8 BOMs, blank lines, and both
     * ';' and '#' comments. Inline comments are not stripped because they may
     * be part of a valid value (for example a URL fragment).
     */
    fun parse(text: String): List<RcloneRemote> {
        val sections = linkedMapOf<String, LinkedHashMap<String, String>>()
        var section: String? = null
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith('#') || line.startsWith(';')) return@forEach
            if (line.startsWith('[') && line.endsWith(']')) {
                section = line.substring(1, line.length - 1).trim().also { sections.getOrPut(it) { linkedMapOf() } }
                return@forEach
            }
            val current = section ?: return@forEach
            val separator = line.indexOf('=')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            if (key.isNotEmpty()) sections.getValue(current)[key] = value
        }
        return sections.map { (name, values) ->
            RcloneRemote(name, values["type"].orEmpty(), values.toMap())
        }.filter { it.type.isNotBlank() }
    }
}

data class RemotePath(val remote: String, val path: String = "") {
    fun child(name: String): RemotePath = copy(path = listOf(path.trim('/'), name.trim('/')).filter(String::isNotEmpty).joinToString("/"))
    override fun toString(): String = "$remote:${path.trimStart('/')}"
}

object RcloneRemoteNavigator {
    fun root(remotes: List<RcloneRemote>): List<RemotePath> = remotes.map { RemotePath(it.name) }
    fun resolve(remote: RcloneRemote, path: String): RemotePath = RemotePath(remote.name, path.trim('/'))
    fun resultJsonCandidates(path: RemotePath): List<RemotePath> = listOf(path.child("result.json"), path.child("messages.html")).filter { it.path.endsWith("result.json") }
}
