from pathlib import Path

path = Path("source/TAR-JS/app/build.gradle.kts")
source = path.read_text(encoding="utf-8")
old = '    kotlinOptions { jvmTarget = "17" }\n'
if old not in source:
    raise SystemExit("Expected Kotlin jvmTarget configuration was not found")
source = source.replace(old, "", 1)
marker = "\n\ndependencies {"
replacement = '''

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {'''
if marker not in source:
    raise SystemExit("Expected dependencies block marker was not found")
path.write_text(source.replace(marker, replacement, 1), encoding="utf-8")
print("KOTLIN_2_3_COMPATIBILITY_FIX_APPLIED")
