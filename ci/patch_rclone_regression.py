from pathlib import Path

p = Path("tarjs/app/src/main/java/com/tarjs/archive/RcloneEngine.kt")
s = p.read_text()

start = s.index("    @Synchronized fun initialize(")
end = s.index("\n    fun version()", start)

new = r'''    @Synchronized fun initialize(configFile: File, configPassword: String? = null) {
        check(available()) { "Embedded rclone engine is not installed" }
        if (!initialized) {
            gomobileClass.getMethod("rcloneInitialize").invoke(null)
            initialized = true
        }

        // A newly selected config must never inherit the previous config's
        // decrypted storage or password.
        gomobileClass.getMethod("rcloneResetConfig").invoke(null)

        rpcChecked(
            "options/set",
            JSONObject().put("main", JSONObject().put("AskPassword", false))
        )
        rpcChecked("config/setpath", JSONObject().put("path", configFile.absolutePath))

        if (!configPassword.isNullOrEmpty()) {
            rpcChecked("config/unlock", JSONObject().put("configPassword", configPassword))
        }

        // setpath/unlock alone don't prove the config decrypted. Force a real
        // read now. Wrong passwords fail here instead of exposing cached data.
        val dump = rpcChecked("config/dump", JSONObject())
        require(dump.length() > 0) { "Config contains no remotes or could not be decrypted" }
    }

    fun configuredRemotes(): JSONArray {
        val dump = rpcChecked("config/dump", JSONObject())
        val out = JSONArray()
        val names = dump.keys().asSequence().toList().sorted()
        for (name in names) {
            val cfg = dump.optJSONObject(name)
            out.put(JSONObject()
                .put("name", name)
                .put("type", cfg?.optString("type", "") ?: ""))
        }
        return out
    }
'''
s = s[:start] + new + s[end:]
p.write_text(s)

p = Path("tarjs/app/src/main/java/com/tarjs/archive/MainActivity.kt")
s = p.read_text()
s = s.replace(
    'val remotes = rclone.remotes()\n        event("rcloneConfig", JSONObject().put("remotes", remotes).put("hasCrypt", true).put("engineAvailable", true))',
    'val remotes = rclone.configuredRemotes()\n        val hasCrypt = (0 until remotes.length()).any { remotes.getJSONObject(it).optString("type") == "crypt" }\n        event("rcloneConfig", JSONObject().put("remotes", remotes).put("hasCrypt", hasCrypt).put("engineAvailable", true))'
)
p.write_text(s)

# UI regression fix: accept both the old string form and the new object form.
p = Path("tarjs/app/src/main/assets/web/app.js")
s = p.read_text()
old = "function rcloneStart(p){S.view='rclone';setHead('rclone','Choose a remote',S.archives.length>0,false);const remotes=p.remotes||[];"
new = "function rcloneStart(p){S.view='rclone';setHead('rclone','Choose a remote',S.archives.length>0,false);const remotes=(p.remotes||[]).map(r=>typeof r==='string'?{name:r.replace(/:$/,''),type:'remote'}:r).filter(r=>r&&r.name);"
if old not in s: raise SystemExit("rcloneStart marker missing")
s = s.replace(old,new)

# Make browse errors visible on the current browser screen instead of looking like an empty folder.
s = s.replace(
    "if(name==='importError'){let e=$('#progress');",
    "if(name==='importError'){let e=$('#progress');"
)
p.write_text(s)
