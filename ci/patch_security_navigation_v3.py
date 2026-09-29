from pathlib import Path
import subprocess

PROVEN_COMMIT = "34673f4096f43bc874dc201a6e1e693d10adfeb2"
SCRIPT_PATH = "ci/patch_security_navigation_v3.py"

# Execute the exact v4 transform that already produced the desired async-search
# and silent-rclone source snapshot. Then fix the one compiler-discovered
# duplicate method in the generated Kotlin source.
subprocess.run(
    ["git", "fetch", "--depth=1", "origin", PROVEN_COMMIT],
    check=True,
    stdout=subprocess.DEVNULL,
)
code = subprocess.check_output(
    ["git", "show", f"{PROVEN_COMMIT}:{SCRIPT_PATH}"],
    text=True,
)
exec(compile(code, f"{SCRIPT_PATH}@{PROVEN_COMMIT}", "exec"), {"__name__": "__main__"})

controller = Path("source/TAR-JS/app/src/main/java/com/tarjs/archive/TarJsController.kt")
text = controller.read_text(encoding="utf-8")
duplicate = '    fun messageWindow(chatId: Long, centerDbId: Long): List<MessageItem> = runCatching { JsonModels.messages(db.messageWindowJson(chatId, centerDbId, 80, 80)) }.getOrDefault(emptyList())\n'
if duplicate not in text:
    raise SystemExit("Expected generated duplicate messageWindow overload not found")
text = text.replace(duplicate, "", 1)
controller.write_text(text, encoding="utf-8")
print("V4_DUPLICATE_CONTROLLER_OVERLOAD_REMOVED")
