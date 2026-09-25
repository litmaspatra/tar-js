from pathlib import Path
p=Path("tarjs/app/src/main/assets/web/app.js")
s=p.read_text()
old="function isMine(m){if(S.prefs.senderId&&String(m.senderId||'')===String(S.prefs.senderId))return true;if(S.prefs.displayName&&norm(m.sender)===norm(S.prefs.displayName))return true;if(S.chat&&String(S.chat.type||'').includes('personal')&&m.sender&&S.chat.name&&norm(m.sender)!==norm(S.chat.name))return true;return false}"
new="function isMine(m){let o={};try{if(S.archive)o=j(TARJS.getOwner(S.archive.id),'{}')}catch{}if(o.id&&String(m.senderId||'')===String(o.id))return true;if(o.name&&norm(m.sender)===norm(o.name))return true;if(S.prefs.senderId&&String(m.senderId||'')===String(S.prefs.senderId))return true;if(S.prefs.displayName&&norm(m.sender)===norm(S.prefs.displayName))return true;if(S.chat&&String(S.chat.type||'').includes('personal')&&m.sender&&S.chat.name&&norm(m.sender)!==norm(S.chat.name))return true;return false}"
if old not in s: raise SystemExit("isMine hook not found")
p.write_text(s.replace(old,new))
