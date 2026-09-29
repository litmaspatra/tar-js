import subprocess

PROVEN_COMMIT = "34673f4096f43bc874dc201a6e1e693d10adfeb2"
SCRIPT_PATH = "ci/patch_security_navigation_v3.py"

# Reuse the exact v4 transform that already applied successfully and produced
# the desired async-search / silent-rclone source snapshot. Fetching the
# historical commit avoids re-serializing a large and fragile source transform.
subprocess.run(
    ["git", "fetch", "--depth=1", "origin", PROVEN_COMMIT],
    check=True,
    stdout=subprocess.DEVNULL,
)
code = subprocess.check_output(
    ["git", "show", f"{PROVEN_COMMIT}:{SCRIPT_PATH}"],
    text=True,
)

# Native Material3 v2 already defines messageWindow(chatId, messageDbId).
# The proven v4 transform accidentally added an identical-signature overload,
# which is the only compile failure observed in that run. Remove only that line.
duplicate = '''    fun messageWindow(chatId: Long, centerDbId: Long): List<MessageItem> = runCatching { JsonModels.messages(db.messageWindowJson(chatId, centerDbId, 80, 80)) }.getOrDefault(emptyList())\n'''
if duplicate not in code:
    raise SystemExit("Expected duplicate messageWindow overload not found in proven v4 transform")
code = code.replace(duplicate, "", 1)

exec(compile(code, f"{SCRIPT_PATH}@{PROVEN_COMMIT}", "exec"), {"__name__": "__main__"})
