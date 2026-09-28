from pathlib import Path

root = Path("source/TAR-JS")

engine = root / "app/src/main/java/com/tarjs/archive/RcloneEngine.kt"
engine_text = engine.read_text(encoding="utf-8")
engine_old = '''        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))
'''
engine_new = '''        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        // Librclone runs headlessly inside Android. Disable interactive password
        // prompting before pointing it at a config, otherwise encrypted configs
        // can terminate the Go library with a fatal stdin EOF.
        rpcChecked("options/set", JSONObject().put("main", JSONObject().put("AskPassword", false)))
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))
'''
if engine_old not in engine_text:
    raise SystemExit("RcloneEngine initialize block did not match authoritative source")
engine.write_text(engine_text.replace(engine_old, engine_new, 1), encoding="utf-8")

main = root / "app/src/main/java/com/tarjs/archive/MainActivity.kt"
main_text = main.read_text(encoding="utf-8")
main_old = '''        if (rclone.available() && rcloneConfigFile != null) { try { rclone.initialize(rcloneConfigFile!!) } catch (_: Exception) {} }
'''
main_new = '''        if (rclone.available() && rcloneConfigFile != null) {
            // Never auto-open a saved encrypted config without its password.
            // The UI will request the password before calling initialize.
            val encryptedConfig = runCatching {
                rcloneConfigFile!!.bufferedReader().use { reader ->
                    reader.readLine().orEmpty().trimStart().startsWith("RCLONE_ENCRYPT_V0:")
                }
            }.getOrDefault(false)
            if (!encryptedConfig) {
                try { rclone.initialize(rcloneConfigFile!!) } catch (_: Exception) {}
            }
        }
'''
if main_old not in main_text:
    raise SystemExit("MainActivity startup rclone block did not match authoritative source")
main.write_text(main_text.replace(main_old, main_new, 1), encoding="utf-8")

assert 'JSONObject().put("AskPassword", false)' in engine.read_text(encoding="utf-8")
assert "if (!encryptedConfig)" in main.read_text(encoding="utf-8")
print("RCLONE_HEADLESS_EOF_FIX_APPLIED")
