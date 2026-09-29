from pathlib import Path

p = Path('source/TAR-JS/app/build.gradle.kts')
s = p.read_text(encoding='utf-8')

# Compose 1.12 requires compileSdk 37 / AGP 9.x. TAR-JS stays on compileSdk 36
# / AGP 8.13, so keep the core Compose line on 1.11.4 and Material3 on 1.3.2.
# Material Icons are versioned separately and stopped moving with core Compose;
# keep those artifacts on their published 1.7.8 line.
if 'resolutionStrategy.eachDependency' not in s:
    block = r'''

configurations.configureEach {
    resolutionStrategy.eachDependency {
        val g = requested.group ?: return@eachDependency
        when {
            g == "androidx.compose.material3" -> useVersion("1.3.2")
            g == "androidx.compose.material" && requested.name.startsWith("material-icons-") -> useVersion("1.7.8")
            g == "androidx.compose.animation" ||
            g == "androidx.compose.foundation" ||
            g == "androidx.compose.material" ||
            g == "androidx.compose.runtime" ||
            g == "androidx.compose.ui" -> useVersion("1.11.4")
            g == "androidx.activity" && requested.name == "activity-compose" -> useVersion("1.11.0")
        }
    }
}
'''
    s += block

p.write_text(s, encoding='utf-8')
print('COMPOSE_API36_COMPAT_PIN_APPLIED')
