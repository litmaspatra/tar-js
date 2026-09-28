from pathlib import Path

root = Path("source/TAR-JS")

# 1) RcloneEngine: disable headless prompts, apply password before loading,
# and validate the config by listing remotes before declaring the engine ready.
engine = root / "app/src/main/java/com/tarjs/archive/RcloneEngine.kt"
engine_text = engine.read_text(encoding="utf-8")
engine_text = engine_text.replace(
'''class RcloneEngine(private val context: Context) {
    private var initialized = false
''',
'''class RcloneEngine(private val context: Context) {
    private var initialized = false
    private var configReady = false
''',
1,
)
engine_text = engine_text.replace(
'''    fun available(): Boolean = try { gomobileClass; true } catch (_: Throwable) { false }
    fun isReady(): Boolean = initialized
''',
'''    fun available(): Boolean = try { gomobileClass; true } catch (_: Throwable) { false }
    fun isReady(): Boolean = configReady
''',
1,
)
engine_old = '''        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))
        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }
'''
engine_new = '''        configReady = false
        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        // Librclone runs headlessly inside Android. Never let it prompt on stdin.
        rpcChecked("options/set", JSONObject().put("main", JSONObject().put("AskPassword", false)))

        // config/unlock only installs the decryption key; it does not need the file
        // loaded first. Set the key before the first call that reads the config.
        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))

        // Force the config to be read now. Wrong/missing passwords are converted by
        // librclone into an RC error, which the Android layer can report safely.
        rpcChecked("config/listremotes", JSONObject())
        configReady = true
'''
if engine_old not in engine_text:
    raise SystemExit("RcloneEngine initialize block did not match authoritative source")
engine_text = engine_text.replace(engine_old, engine_new, 1)
engine_text = engine_text.replace(
'''        initialized = false
''',
'''        initialized = false
        configReady = false
''',
1,
)
engine.write_text(engine_text, encoding="utf-8")

# 2) Config parser: rclone encrypted files normally begin with comments and blank
# lines before RCLONE_ENCRYPT_V0:. Detect the first meaningful line, including BOM.
parser = root / "app/src/main/java/com/tarjs/archive/RcloneConfigParser.kt"
parser_text = parser.read_text(encoding="utf-8")
parser_marker = '''object RcloneConfigParser {
    fun parse(text: String): JSONArray {
'''
parser_replacement = '''object RcloneConfigParser {
    fun isEncrypted(text: String): Boolean {
        val firstMeaningful = text.lineSequence()
            .map { it.trim().trimStart('\\uFEFF') }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith(";") }
            ?: return false
        return firstMeaningful.startsWith("RCLONE_ENCRYPT_V")
    }

    fun parse(text: String): JSONArray {
'''
if parser_marker not in parser_text:
    raise SystemExit("RcloneConfigParser marker did not match authoritative source")
parser.write_text(parser_text.replace(parser_marker, parser_replacement, 1), encoding="utf-8")

# 3) MainActivity: use the robust detector everywhere, never auto-load an encrypted
# saved config, and ask for its password again after an app restart.
main = root / "app/src/main/java/com/tarjs/archive/MainActivity.kt"
main_text = main.read_text(encoding="utf-8")
main_text = main_text.replace(
'''    private var rcloneConfigFile: java.io.File? = null
''',
'''    private var rcloneConfigFile: java.io.File? = null
    private var rcloneConfigNeedsPassword = false
''',
1,
)
main_old = '''        rcloneConfigFile = java.io.File(filesDir, "rclone/rclone.conf").takeIf { it.exists() }
        if (rclone.available() && rcloneConfigFile != null) { try { rclone.initialize(rcloneConfigFile!!) } catch (_: Exception) {} }
'''
main_new = '''        rcloneConfigFile = java.io.File(filesDir, "rclone/rclone.conf").takeIf { it.exists() }
        if (rclone.available() && rcloneConfigFile != null) {
            val encryptedConfig = runCatching {
                RcloneConfigParser.isEncrypted(rcloneConfigFile!!.readText())
            }.getOrDefault(false)
            rcloneConfigNeedsPassword = encryptedConfig
            if (!encryptedConfig) {
                try { rclone.initialize(rcloneConfigFile!!) } catch (_: Exception) {}
            }
        }
'''
if main_old not in main_text:
    raise SystemExit("MainActivity startup rclone block did not match authoritative source")
main_text = main_text.replace(main_old, main_new, 1)

# Prompt after the WebView has actually loaded, so the JS event handler exists.
client_marker = '''        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
'''
client_replacement = '''        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (rcloneConfigNeedsPassword) {
                    event("rclonePasswordRequired", JSONObject().put("message", "This rclone configuration is encrypted."))
                }
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
'''
if client_marker not in main_text:
    raise SystemExit("WebViewClient marker did not match authoritative source")
main_text = main_text.replace(client_marker, client_replacement, 1)

unlock_old = '''        @JavascriptInterface fun unlockRclone(password: String) {
            io.execute {
                try {
                    val f = rcloneConfigFile ?: error("Import an rclone config first")
                    rclone.initialize(f, password)
                    emitRcloneReady()
                } catch (e: Exception) { event("rcloneUnlockError", JSONObject().put("message", e.message ?: "Could not unlock rclone config")) }
            }
        }
'''
unlock_new = '''        @JavascriptInterface fun unlockRclone(password: String) {
            io.execute {
                try {
                    require(password.isNotBlank()) { "Enter the rclone config password" }
                    val f = rcloneConfigFile ?: error("Import an rclone config first")
                    rclone.initialize(f, password)
                    rcloneConfigNeedsPassword = false
                    emitRcloneReady()
                } catch (e: Exception) { event("rcloneUnlockError", JSONObject().put("message", e.message ?: "Could not unlock rclone config")) }
            }
        }
'''
if unlock_old not in main_text:
    raise SystemExit("unlockRclone block did not match authoritative source")
main_text = main_text.replace(unlock_old, unlock_new, 1)

handle_old = '''                val text = bytes.toString(Charsets.UTF_8)
                rcloneConfigFile = rclone.installConfig(bytes.inputStream())
                val encrypted = text.trimStart().startsWith("RCLONE_ENCRYPT_V0:")
'''
handle_new = '''                val text = bytes.toString(Charsets.UTF_8)
                rcloneConfigFile = rclone.installConfig(bytes.inputStream())
                val encrypted = RcloneConfigParser.isEncrypted(text)
                rcloneConfigNeedsPassword = encrypted
'''
if handle_old not in main_text:
    raise SystemExit("handleRcloneConfig encryption detector did not match authoritative source")
main_text = main_text.replace(handle_old, handle_new, 1)
main.write_text(main_text, encoding="utf-8")

# 4) JVM tests cover only Android-independent classification logic. The parser's
# JSONObject behavior is exercised by Android compilation; live rclone behavior is
# tested by the real v1.75.1 integration harness in CI.
build = root / "app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
if 'testImplementation("junit:junit:4.13.2")' not in build_text:
    build_text = build_text.replace(
        'dependencies {\n    implementation(fileTree("libs") { include("*.aar") })\n}',
        'dependencies {\n    implementation(fileTree("libs") { include("*.aar") })\n    testImplementation("junit:junit:4.13.2")\n}',
        1,
    )
build.write_text(build_text, encoding="utf-8")

test_file = root / "app/src/test/java/com/tarjs/archive/RcloneConfigParserTest.kt"
test_file.parent.mkdir(parents=True, exist_ok=True)
test_file.write_text('''package com.tarjs.archive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneConfigParserTest {
    @Test
    fun detectsCanonicalEncryptedRcloneConfigWithHeaderComments() {
        val text = """# Encrypted rclone configuration File

RCLONE_ENCRYPT_V0:
AAAA
"""
        assertTrue(RcloneConfigParser.isEncrypted(text))
    }

    @Test
    fun detectsEncryptedConfigWithBomAndSemicolonComments() {
        val text = "\\uFEFF; saved by rclone\\n\\nRCLONE_ENCRYPT_V0:\\nAAAA\\n"
        assertTrue(RcloneConfigParser.isEncrypted(text))
    }

    @Test
    fun treatsPlainConfigAsPlaintext() {
        val text = """# normal config
[dummy]
type = local
"""
        assertFalse(RcloneConfigParser.isEncrypted(text))
    }

    @Test
    fun classifiesFutureEncryptionVersionsAsEncryptedRatherThanPlaintext() {
        assertTrue(RcloneConfigParser.isEncrypted("RCLONE_ENCRYPT_V1:\\nAAAA"))
    }
}
''', encoding="utf-8")

# Safety assertions over the generated patch.
patched_engine = engine.read_text(encoding="utf-8")
assert 'JSONObject().put("AskPassword", false)' in patched_engine
assert 'rpcChecked("config/listremotes", JSONObject())' in patched_engine
assert 'configReady = true' in patched_engine
patched_main = main.read_text(encoding="utf-8")
assert patched_main.count('RcloneConfigParser.isEncrypted') >= 2
assert 'rcloneConfigNeedsPassword = encrypted' in patched_main
assert 'event("rclonePasswordRequired"' in patched_main
assert 'require(password.isNotBlank())' in patched_main
print("RCLONE_ARCHITECTURE_FIX_APPLIED")
