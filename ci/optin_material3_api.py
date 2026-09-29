from pathlib import Path

p = Path('source/TAR-JS/app/src/main/java/com/tarjs/archive/NativeUi.kt')
s = p.read_text(encoding='utf-8')
marker = '@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)\n\n'
if not s.startswith('@file:OptIn('):
    s = marker + s
p.write_text(s, encoding='utf-8')
print('MATERIAL3_EXPERIMENTAL_API_OPTIN_APPLIED')
