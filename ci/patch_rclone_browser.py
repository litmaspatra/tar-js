from pathlib import Path

# Native rclone folder validation
p = Path('tarjs/app/src/main/java/com/tarjs/archive/MainActivity.kt')
s = p.read_text()
old = '''        @JavascriptInterface fun importRcloneFolder(remote: String, path: String) {
            io.execute {
                try {
                    val entries = rclone.list(remote, path)
                    var found = false
                    for (i in 0 until entries.length()) if (!entries.getJSONObject(i).optBoolean("IsDir", false) && entries.getJSONObject(i).optString("Name") == "result.json") { found = true; break }
                    require(found) { "No result.json found in this rclone folder" }
                    val archiveName = if (path.isBlank()) remote else path.substringAfterLast('/')
                    val id = db.createArchive(archiveName.ifBlank { "Telegram Archive" }, "rclone", "rclone://$remote/${path.trimStart('/')}")
                    val src = db.archiveSource(id) ?: error("Archive source missing")
                    tree.openResultJson(src).use { input ->
                        require(input != null) { "Could not fetch result.json through rclone" }
                        val res = TelegramImporter(db).import(id, input) { msg -> event("importProgress", JSONObject().put("archiveId", id).put("message", msg)) }
                        event("importDone", JSONObject().put("archiveId", id).put("chats", res.chats).put("messages", res.messages))
                        event("archivesChanged", JSONArray(db.archiveJson()))
                    }
                } catch (e: Exception) { event("importError", JSONObject().put("message", e.message ?: "rclone import failed")) }
            }
        }
'''
new = '''        @JavascriptInterface fun importRcloneFolder(remote: String, path: String) {
            io.execute {
                try {
                    event("importProgress", JSONObject().put("message", "Checking Telegram export folder…"))
                    val entries = rclone.list(remote, path)
                    var found = false
                    for (i in 0 until entries.length()) {
                        val e = entries.getJSONObject(i)
                        if (e.optBoolean("IsDir", false)) continue
                        val name = e.optString("Name").ifBlank { e.optString("Path").replace('\\\\', '/').substringAfterLast('/') }
                        if (name.equals("result.json", ignoreCase = true)) { found = true; break }
                    }
                    require(found) { "No result.json found in this folder. Select the Telegram export root folder." }
                    val cleanPath = path.replace('\\\\', '/').trim('/')
                    val remoteName = remote.removeSuffix(":")
                    val archiveName = if (cleanPath.isBlank()) remoteName else cleanPath.substringAfterLast('/')
                    val id = db.createArchive(archiveName.ifBlank { "Telegram Archive" }, "rclone", "rclone://$remoteName/$cleanPath")
                    val src = db.archiveSource(id) ?: error("Archive source missing")
                    event("importProgress", JSONObject().put("archiveId", id).put("message", "Downloading result.json…"))
                    tree.openResultJson(src).use { input ->
                        require(input != null) { "Could not fetch result.json through rclone" }
                        val res = TelegramImporter(db).import(id, input) { msg -> event("importProgress", JSONObject().put("archiveId", id).put("message", msg)) }
                        event("importDone", JSONObject().put("archiveId", id).put("chats", res.chats).put("messages", res.messages))
                        event("archivesChanged", JSONArray(db.archiveJson()))
                    }
                } catch (e: Exception) {
                    event("importError", JSONObject().put("message", e.message ?: "rclone import failed"))
                }
            }
        }
'''
if old not in s: raise SystemExit('importRcloneFolder block not found')
p.write_text(s.replace(old, new))

# Normalize Telegram media paths against the selected export root
p = Path('tarjs/app/src/main/java/com/tarjs/archive/TreeAccess.kt')
s = p.read_text()
old = '''    private fun joinRemote(base: String, child: String): String {
        val a = base.trim('/')
        val b = child.replace('\\\\','/').trimStart('/')
        return if (a.isBlank()) b else if (b.isBlank()) a else "$a/$b"
    }
'''
new = '''    private fun joinRemote(base: String, child: String): String {
        val a = base.replace('\\\\', '/').trim('/')
        val b = child.replace('\\\\', '/')
            .trim().trimStart('/').removePrefix("./")
            .split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")
        require(b.split('/').none { it == ".." }) { "Unsafe media path" }
        return if (a.isBlank()) b else if (b.isBlank()) a else "$a/$b"
    }
'''
if old not in s: raise SystemExit('joinRemote block not found')
p.write_text(s.replace(old, new))

# Rclone browser: show folders AND files, highlight result.json, and use state-backed folder selection.
p = Path('tarjs/app/src/main/assets/web/app.js')
s = p.read_text()
old = "function renderRcloneListing(p){S.view='rcloneBrowser';S.rclone={remote:p.remote,path:p.path};setHead(p.remote,p.path||'Root',true,false);const dirs=(p.entries||[]).filter(e=>e.IsDir);const hasResult=(p.entries||[]).some(e=>!e.IsDir&&e.Name==='result.json');main.innerHTML=`<div class=\"panel\">${hasResult?`<button class=\"action\" style=\"background:var(--accent);color:white\" onclick=\"TARJS.importRcloneFolder('${esc(p.remote)}','${esc(p.path)}')\"><span>Use this Telegram archive folder</span><span>✓</span></button>`:`<p style=\"padding-top:18px\">Choose the folder containing <b>result.json</b>.</p>`}${dirs.map(e=>{const rel=e.Path||e.Name;const next=p.path?(rel.startsWith(p.path)?rel:p.path.replace(/\\/$/,'')+'/'+rel):rel;return `<div class=\"row\" onclick=\"openRclone('${esc(p.remote)}','${esc(next)}')\"><div class=\"avatar\">📁</div><div class=\"row-main\"><div class=\"row-title\">${esc(e.Name||rel.split('/').pop())}</div></div><span>›</span></div>`}).join('')}</div>`}"
new = "function rcloneEntryName(e){return String(e.Name||e.Path||'').replace(/\\\\/g,'/').split('/').filter(Boolean).pop()||''}\nfunction rcloneNextPath(parent,e){const rel=String(e.Path||e.Name||'').replace(/\\\\/g,'/').replace(/^\\/+|\\/+$/g,'');const cur=String(parent||'').replace(/\\\\/g,'/').replace(/^\\/+|\\/+$/g,'');if(!cur)return rel;if(rel===cur||rel.startsWith(cur+'/'))return rel;return cur+'/'+rel}\nfunction selectCurrentRcloneFolder(){const b=document.getElementById('use-rclone-folder');if(b){b.disabled=true;b.innerHTML='<span><span class=\"spinner\"></span> Opening archive…</span><span>›</span>'}const q=document.getElementById('progress');if(q)q.innerHTML='<div class=\"progress\"><span class=\"spinner\"></span>Checking result.json…</div>';TARJS.importRcloneFolder(S.rclone.remote,S.rclone.path)}\nfunction renderRcloneListing(p){S.view='rcloneBrowser';S.rclone={remote:p.remote,path:p.path};setHead(p.remote,p.path||'Root',true,false);const entries=(p.entries||[]).slice().sort((a,b)=>(b.IsDir?1:0)-(a.IsDir?1:0)||rcloneEntryName(a).localeCompare(rcloneEntryName(b)));const files=entries.filter(e=>!e.IsDir);const result=files.find(e=>rcloneEntryName(e).toLowerCase()==='result.json');const mediaNames=new Set(['photos','stickers','video_files','voice_messages','files','round_video_messages','profile_pictures']);const mediaCount=entries.filter(e=>e.IsDir&&mediaNames.has(rcloneEntryName(e).toLowerCase())).length;const status=result?`<div class=\"notice\"><b>Telegram export detected</b><br>result.json found${mediaCount?' · '+mediaCount+' media folder'+(mediaCount===1?'':'s')+' detected':''}.</div><button id=\"use-rclone-folder\" class=\"action\" style=\"background:var(--accent);color:white\" onclick=\"selectCurrentRcloneFolder()\"><span>Use this folder</span><span>✓</span></button>`:`<div class=\"notice\"><b>Choose the export root folder.</b><br>It must directly contain <code>result.json</code>.</div>`;const rows=entries.map(e=>{const name=rcloneEntryName(e),rel=e.Path||e.Name||'',isResult=!e.IsDir&&name.toLowerCase()==='result.json';if(e.IsDir){const next=rcloneNextPath(p.path,e);return `<div class=\"row\" data-path=\"${esc(next)}\" onclick=\"openRclone(S.rclone.remote,this.dataset.path)\"><div class=\"avatar\">📁</div><div class=\"row-main\"><div class=\"row-title\">${esc(name||'Folder')}</div><div class=\"row-preview\">${mediaNames.has(name.toLowerCase())?'Telegram media folder':'Folder'}</div></div><span>›</span></div>`}const size=Number(e.Size||0),pretty=size>1048576?(size/1048576).toFixed(1)+' MB':size>1024?(size/1024).toFixed(1)+' KB':size?size+' B':'File';return `<div class=\"row\"><div class=\"avatar\">${isResult?'{}':'📄'}</div><div class=\"row-main\"><div class=\"row-title\">${esc(name||'File')}</div><div class=\"row-preview\">${isResult?'Telegram archive index':esc(pretty)}</div></div>${isResult?'<span class=\"badge\">FOUND</span>':''}</div>`}).join('');main.innerHTML=`<div class=\"panel\">${status}<div id=\"progress\"></div>${rows||'<div class=\"empty\">This folder is empty.</div>'}</div>`}"
if old not in s: raise SystemExit('rclone listing JS block not found')
p.write_text(s.replace(old, new))