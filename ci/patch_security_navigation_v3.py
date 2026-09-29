from pathlib import Path
import subprocess

root = Path('source/TAR-JS')
patch = Path('ci/security_navigation_v3.patch')
if not root.exists():
    raise SystemExit('Reconstructed TAR-JS source is missing')
if not patch.exists():
    raise SystemExit('Security/navigation patch is missing')

# The repository patch was transported as text. Its first file is intact, while every
# line beginning with the second diff acquired one extra leading '+'. Repair exactly
# that transport artifact before applying it; genuine additions after that point are
# encoded as '++...' and therefore remain '+...' after removing one prefix.
raw = patch.read_text(encoding='utf-8')
marker = '\n+diff -ruN '
pos = raw.find(marker)
if pos >= 0:
    head = raw[:pos + 1]
    tail = raw[pos + 1:]
    tail = '\n'.join(line[1:] if line.startswith('+') else line for line in tail.split('\n'))
    raw = head + tail
fixed = Path('/tmp/security_navigation_v3.fixed.patch')
fixed.write_text(raw, encoding='utf-8')

subprocess.run(['patch', '-p1', '--forward', '--batch', '-i', str(fixed)], cwd=root, check=True)

ui = (root / 'app/src/main/java/com/tarjs/archive/NativeUi.kt').read_text(encoding='utf-8')
db = (root / 'app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt').read_text(encoding='utf-8')
ctl = (root / 'app/src/main/java/com/tarjs/archive/TarJsController.kt').read_text(encoding='utf-8')
lock = root / 'app/src/main/java/com/tarjs/archive/AppPasscode.kt'
for token in ['Page.Welcome', 'WelcomeScreen(', 'Search this chat', 'Load newer messages', 'Back to home', 'Set profile photo', 'focusMessageId']:
    assert token in ui, token
for token in ['searchChatJson', 'newerMessagesJson', 'hasOlder', 'hasNewer']:
    assert token in db, token
for token in ['searchChat(', 'newerMessages(', 'hasOlder(', 'hasNewer(']:
    assert token in ctl, token
assert lock.exists() and 'PBKDF2WithHmacSHA256' in lock.read_text(encoding='utf-8')
print('SECURITY_NAVIGATION_V3_APPLIED')
