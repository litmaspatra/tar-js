from pathlib import Path

p = Path("tarjs/app/src/main/java/com/tarjs/archive/RcloneEngine.kt")
s = p.read_text()

old = """    @Synchronized fun initialize(configFile: File, configPassword: String? = null) {
        check(available()) { "Embedded rclone engine is not installed" }
        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))
        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }
    }
"""

new = """    fun isEncryptedConfig(text: String): Boolean {
        val firstMeaningful = text.lineSequence()
            .map { it.trim().removePrefix("\\uFEFF") }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            ?: return false
        return firstMeaningful.startsWith("RCLONE_ENCRYPT_V")
    }

    @Synchronized fun initialize(configFile: File, configPassword: String? = null) {
        check(available()) { "Embedded rclone engine is not installed" }
        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true

            // gomobile has no interactive terminal: Android owns password prompts.
            rpcChecked(
                "options/set",
                JSONObject().put("main", JSONObject().put("AskPassword", false))
            )
        }

        // Encrypted configs must be unlocked before setpath loads them.
        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))
    }
"""

if old not in s:
    raise SystemExit("RcloneEngine initialize block not found")
p.write_text(s.replace(old, new))

p = Path("tarjs/app/src/main/java/com/tarjs/archive/MainActivity.kt")
s = p.read_text()

old = '        if (rclone.available() && rcloneConfigFile != null) { try { rclone.initialize(rcloneConfigFile!!) } catch (_: Exception) {} }'
new = """        if (rclone.available() && rcloneConfigFile != null) {
            try {
                val savedText = rcloneConfigFile!!.readText()
                if (!rclone.isEncryptedConfig(savedText)) rclone.initialize(rcloneConfigFile!!)
            } catch (_: Exception) {}
        }"""
if old not in s:
    raise SystemExit("startup rclone init block not found")
s = s.replace(old, new)

s = s.replace(
    'val remotes = if (text.trimStart().startsWith("RCLONE_ENCRYPT_V0:")) JSONArray() else RcloneConfigParser.parse(text)',
    'val remotes = if (rclone.isEncryptedConfig(text)) JSONArray() else RcloneConfigParser.parse(text)'
)
s = s.replace(
    '} else if (text.trimStart().startsWith("RCLONE_ENCRYPT_V0:")) {',
    '} else if (rclone.isEncryptedConfig(text)) {'
)
p.write_text(s)
