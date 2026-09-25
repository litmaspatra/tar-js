from pathlib import Path

# Replace web UI assets from the packed overhaul bundle.
import base64, zipfile, io
parts = [
    Path("ci/ui_assets_0.b64").read_text(),
    Path("ci/ui_assets_1.b64").read_text(),
    Path("ci/ui_assets_2.b64").read_text(),
]
raw = base64.b64decode("".join(parts))
with zipfile.ZipFile(io.BytesIO(raw)) as z:
    for name in ("index.html","styles.css","app.js"):
        target = Path("tarjs/app/src/main/assets/web") / name
        target.write_bytes(z.read(name))

# Native privacy/profile/TGS support.
p = Path("tarjs/app/src/main/java/com/tarjs/archive/MainActivity.kt")
s = p.read_text()

# imports
for imp in [
    "import android.graphics.Color",
    "import java.security.SecureRandom",
    "import javax.crypto.SecretKeyFactory",
    "import javax.crypto.spec.PBEKeySpec",
    "import java.util.zip.GZIPInputStream",
    "import java.io.ByteArrayInputStream",
]:
    if imp not in s:
        s = s.replace("import android.app.Activity\n", "import android.app.Activity\n"+imp+"\n", 1)

# fields and constants
s = s.replace(
    '    private var pendingSourceKind = "local"\n',
    '    private var pendingSourceKind = "local"\n    private val prefs by lazy { getSharedPreferences("tarjs_prefs", MODE_PRIVATE) }\n    private var suppressLockOnce = false\n'
)
s = s.replace(
    '        const val REQ_RCLONE = 1202\n',
    '        const val REQ_RCLONE = 1202\n        const val REQ_PROFILE = 1203\n'
)

# onCreate visual polish and safer WebView media
s = s.replace(
    '        web.settings.allowContentAccess = false\n',
    '        web.settings.allowContentAccess = false\n        web.settings.mediaPlaybackRequiresUserGesture = false\n        window.statusBarColor = Color.TRANSPARENT\n        window.navigationBarColor = Color.TRANSPARENT\n'
)

# intercept profile + TGS before media block
needle = '''                if (u.scheme == "https" && u.host == "tarjs.local" && u.path == "/media") {'''
insert = '''                if (u.scheme == "https" && u.host == "tarjs.local" && u.path == "/profile") {
                    val profile = prefs.getString("profile_uri", null) ?: return notFound()
                    return try {
                        val uri = Uri.parse(profile)
                        val input = contentResolver.openInputStream(uri) ?: return notFound()
                        val mime = contentResolver.getType(uri) ?: "image/*"
                        WebResourceResponse(mime, null, input)
                    } catch (_: Exception) { notFound() }
                }
                if (u.scheme == "https" && u.host == "tarjs.local" && u.path == "/tgs") {
                    val archiveId = u.getQueryParameter("archive")?.toLongOrNull() ?: return notFound()
                    val path = u.getQueryParameter("path") ?: return notFound()
                    return try {
                        val resolved = tree.resolveMedia(archiveId, path) ?: return notFound()
                        val input = tree.openMedia(resolved) ?: return notFound()
                        val raw = input.use { it.readBytes() }
                        val json = try {
                            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                        } catch (_: Exception) { raw }
                        WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(json))
                    } catch (_: Exception) { notFound() }
                }
'''+needle
if needle not in s:
    raise SystemExit("media intercept marker missing")
s = s.replace(needle, insert, 1)

# Add bridge methods after search.
needle = '        @JavascriptInterface fun search(archiveId: Long, query: String): String = db.searchJson(archiveId, query)\n'
bridge = needle + r'''

        @JavascriptInterface fun getAppPrefs(): String = JSONObject().apply {
            put("pinEnabled", prefs.contains("pin_hash"))
            put("displayName", prefs.getString("display_name", "") ?: "")
            put("senderId", prefs.getString("sender_id", "") ?: "")
            put("hasProfilePhoto", !prefs.getString("profile_uri", null).isNullOrBlank())
        }.toString()

        @JavascriptInterface fun saveIdentity(name: String, senderId: String) {
            prefs.edit().putString("display_name", name.trim()).putString("sender_id", senderId.trim()).apply()
        }

        @JavascriptInterface fun chooseProfilePhoto() {
            suppressLockOnce = true
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, REQ_PROFILE)
            }
        }

        @JavascriptInterface fun setPin(pin: String): Boolean {
            if (!pin.matches(Regex("\\d{4,8}"))) return false
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = pinHash(pin, salt)
            prefs.edit()
                .putString("pin_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                .putString("pin_hash", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP))
                .apply()
            return true
        }

        @JavascriptInterface fun verifyPin(pin: String): Boolean {
            val saltB64 = prefs.getString("pin_salt", null) ?: return true
            val hashB64 = prefs.getString("pin_hash", null) ?: return true
            return try {
                val salt = android.util.Base64.decode(saltB64, android.util.Base64.NO_WRAP)
                val expected = android.util.Base64.decode(hashB64, android.util.Base64.NO_WRAP)
                java.security.MessageDigest.isEqual(expected, pinHash(pin, salt))
            } catch (_: Exception) { false }
        }

        @JavascriptInterface fun disablePin(pin: String): Boolean {
            if (!verifyPin(pin)) return false
            prefs.edit().remove("pin_salt").remove("pin_hash").apply()
            return true
        }
'''
if needle not in s:
    raise SystemExit("bridge search marker missing")
s = s.replace(needle, bridge, 1)

# Suppress lock while launching existing system pickers.
s = s.replace(
    '        @JavascriptInterface fun chooseFolder(kind: String) {\n            pendingSourceKind = kind\n',
    '        @JavascriptInterface fun chooseFolder(kind: String) {\n            pendingSourceKind = kind\n            suppressLockOnce = true\n'
)
s = s.replace(
    '        @JavascriptInterface fun chooseRcloneConfig() {\n            runOnUiThread {\n',
    '        @JavascriptInterface fun chooseRcloneConfig() {\n            suppressLockOnce = true\n            runOnUiThread {\n'
)

# profile result handling
s = s.replace(
    '''        when (requestCode) {
            REQ_TREE -> handleTree(uri)
            REQ_RCLONE -> handleRcloneConfig(uri)
        }
''',
    '''        suppressLockOnce = false
        when (requestCode) {
            REQ_TREE -> handleTree(uri)
            REQ_RCLONE -> handleRcloneConfig(uri)
            REQ_PROFILE -> handleProfilePhoto(uri)
        }
'''
)

# helper methods before handleTree
marker = '    private fun handleTree(uri: Uri) {\n'
helpers = r'''    private fun pinHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun handleProfilePhoto(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        prefs.edit().putString("profile_uri", uri.toString()).apply()
        event("profileChanged", JSONObject().put("ok", true))
    }

'''
if marker not in s:
    raise SystemExit("handleTree marker missing")
s = s.replace(marker, helpers+marker, 1)

# lock when app leaves foreground, except picker flow
destroy = '    override fun onDestroy() {\n'
lock = '''    override fun onStop() {
        super.onStop()
        if (!suppressLockOnce && prefs.contains("pin_hash")) {
            event("lockRequired", JSONObject().put("reason", "background"))
        }
    }

'''
if destroy not in s:
    raise SystemExit("onDestroy marker missing")
s = s.replace(destroy, lock+destroy, 1)
p.write_text(s)

# Improve WEBP/TGS MIME fallbacks.
p = Path("tarjs/app/src/main/java/com/tarjs/archive/TreeAccess.kt")
s = p.read_text()
s = s.replace(
    '''            "tgs" -> "application/gzip"
            "opus" -> "audio/ogg"
            else -> "application/octet-stream"
''',
    '''            "tgs" -> "application/gzip"
            "webp" -> "image/webp"
            "webm" -> "video/webm"
            "opus" -> "audio/ogg"
            else -> "application/octet-stream"
'''
)
p.write_text(s)

# Polished icon and splash background.
res = Path("tarjs/app/src/main/res")
(res/"drawable").mkdir(parents=True, exist_ok=True)
(res/"mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
(res/"values").mkdir(parents=True, exist_ok=True)
(res/"drawable/ic_tarjs_foreground.xml").write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108"><path android:fillColor="#3390EC" android:pathData="M18,18h72v72h-72z"/><path android:fillColor="#FFFFFF" android:pathData="M31,29h46v12H60v38H48V41H31z"/></vector>''')
(res/"values/colors.xml").write_text('''<resources><color name="tarjs_blue">#3390EC</color><color name="tarjs_bg">#F4F6F8</color></resources>''')
(res/"mipmap-anydpi-v26/ic_launcher.xml").write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/tarjs_blue"/><foreground android:drawable="@drawable/ic_tarjs_foreground"/></adaptive-icon>''')
(res/"mipmap-anydpi-v26/ic_launcher_round.xml").write_text('''<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android"><background android:drawable="@color/tarjs_blue"/><foreground android:drawable="@drawable/ic_tarjs_foreground"/></adaptive-icon>''')

# App icon, light/dark system bars, and Android 12+ splash.
manifest = Path("tarjs/app/src/main/AndroidManifest.xml")
ms = manifest.read_text()
if 'android:icon="@mipmap/ic_launcher"' not in ms:
    ms = ms.replace('android:label="TAR-JS"', 'android:label="TAR-JS"\n        android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher_round"')
manifest.write_text(ms)
(res/"values/styles.xml").write_text('''<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:colorAccent">#3390EC</item>
        <item name="android:navigationBarColor">#F4F6F8</item>
        <item name="android:statusBarColor">#F4F6F8</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowLightNavigationBar">true</item>
    </style>
</resources>''')
(res/"../values-night").mkdir(parents=True, exist_ok=True)
(res/"../values-night/styles.xml").write_text('''<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:colorAccent">#5AAAF3</item>
        <item name="android:navigationBarColor">#11161C</item>
        <item name="android:statusBarColor">#11161C</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:windowLightNavigationBar">false</item>
    </style>
</resources>''')
v31 = res/"../values-v31"
v31.mkdir(parents=True, exist_ok=True)
(v31/"styles.xml").write_text('''<resources>
    <style name="AppTheme" parent="android:style/Theme.Material.Light.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:navigationBarColor">#F4F6F8</item>
        <item name="android:statusBarColor">#F4F6F8</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:windowSplashScreenBackground">#3390EC</item>
        <item name="android:windowSplashScreenAnimatedIcon">@drawable/ic_tarjs_foreground</item>
    </style>
</resources>''')
