from pathlib import Path

root = Path("source/TAR-JS")

engine = root / "app/src/main/java/com/tarjs/archive/RcloneEngine.kt"
engine_text = engine.read_text(encoding="utf-8")
engine_old = '''        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))
        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }
'''
engine_new = '''        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }
        // Librclone runs headlessly inside Android. Never let it prompt on stdin.
        rpcChecked("options/set", JSONObject().put("main", JSONObject().put("AskPassword", false)))

        // For encrypted configs the password must be installed BEFORE setpath.
        // config/setpath loads/decrypts the file immediately; doing unlock after it
        // is too late and produces the fatal "not allowed to ask for password" error.
        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }
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
            // The existing UI requests the password before calling initialize.
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

patched_engine = engine.read_text(encoding="utf-8")
assert 'JSONObject().put("AskPassword", false)' in patched_engine
unlock_pos = patched_engine.index('rpcChecked("config/unlock"')
setpath_pos = patched_engine.index('rpcChecked("config/setpath"')
assert unlock_pos < setpath_pos, "encrypted config password must be set before config/setpath"
assert "if (!encryptedConfig)" in main.read_text(encoding="utf-8")
print("RCLONE_ENCRYPTED_CONFIG_ORDER_FIX_APPLIED")
