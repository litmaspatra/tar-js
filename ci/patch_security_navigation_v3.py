from pathlib import Path
import subprocess

root = Path('source/TAR-JS')
patch = Path('ci/security_navigation_v3.patch')
if not root.exists():
    raise SystemExit('Reconstructed TAR-JS source is missing')
if not patch.exists():
    raise SystemExit('Security/navigation patch is missing')

subprocess.run(['patch', '-p1', '--forward', '--batch', '-i', str(patch.resolve())], cwd=root, check=True)

ui = (root / 'app/src/main/java/com/tarjs/archive/NativeUi.kt').read_text(encoding='utf-8')
db = (root / 'app/src/main/java/com/tarjs/archive/ArchiveDatabase.kt').read_text(encoding='utf-8')
lock = root / 'app/src/main/java/com/tarjs/archive/AppPasscode.kt'
for token in ['Page.Welcome', 'WelcomeScreen(', 'Search this chat', 'Load newer messages', 'Back to home', 'Set profile photo']:
    assert token in ui, token
for token in ['searchChatJson', 'newerMessagesJson', 'hasOlder', 'hasNewer']:
    assert token in db, token
assert lock.exists() and 'PBKDF2WithHmacSHA256' in lock.read_text(encoding='utf-8')
print('SECURITY_NAVIGATION_V3_APPLIED')
