from pathlib import Path
import subprocess

# Reuse the exact v4 transform that produced the last fully-green APK, then apply
# the v5 search/security hardening on top. Keeping this wrapper small makes the
# reconstruction deterministic and avoids reintroducing older patch drift.
PROVEN_COMMIT = "bb2d09355d1cbbf1623dee45d54be59bee6cd9fd"
SCRIPT_PATH = "ci/patch_security_navigation_v3.py"

subprocess.run(
    ["git", "fetch", "--depth=1", "origin", PROVEN_COMMIT],
    check=True,
    stdout=subprocess.DEVNULL,
)
proven = subprocess.check_output(
    ["git", "show", f"{PROVEN_COMMIT}:{SCRIPT_PATH}"],
    text=True,
)
exec(compile(proven, f"{SCRIPT_PATH}@{PROVEN_COMMIT}", "exec"), {"__name__": "__main__"})

v5 = Path("ci/patch_search_security_v5.py").read_text(encoding="utf-8")
exec(compile(v5, "ci/patch_search_security_v5.py", "exec"), {"__name__": "__main__"})
