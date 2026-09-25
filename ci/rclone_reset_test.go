package gomobile

import (
    "bytes"
    "encoding/json"
    "os"
    "path/filepath"
    "testing"

    "github.com/rclone/rclone/fs/config"
)

func rpcStatus(method string, input any) (string, int) {
    b, _ := json.Marshal(input)
    r := RcloneRPC(method, string(b))
    return r.Output, r.Status
}

func writeEncryptedConfig(t *testing.T, path, password string) {
    t.Helper()
    config.ClearConfigPassword()
    if err := config.SetConfigPassword(password); err != nil { t.Fatal(err) }
    plain := bytes.NewBufferString("[b2remote]\ntype = local\n\n[b2crypt]\ntype = local\n")
    f, err := os.Create(path)
    if err != nil { t.Fatal(err) }
    if err := config.Encrypt(plain, f); err != nil { _ = f.Close(); t.Fatal(err) }
    if err := f.Close(); err != nil { t.Fatal(err) }
    config.ClearConfigPassword()
}

func TestRcloneResetEncryptedConfig(t *testing.T) {
    RcloneInitialize()
    defer RcloneFinalize()

    path := filepath.Join(t.TempDir(), "rclone.conf")
    writeEncryptedConfig(t, path, "correct-password")

    RcloneResetConfig()
    if _, status := rpcStatus("options/set", map[string]any{"main": map[string]any{"AskPassword": false}}); status != 200 { t.Fatalf("options/set status=%d", status) }
    if _, status := rpcStatus("config/setpath", map[string]any{"path": path}); status != 200 { t.Fatalf("setpath status=%d", status) }
    if _, status := rpcStatus("config/unlock", map[string]any{"configPassword": "wrong-password"}); status != 200 { t.Fatalf("unlock setter status=%d", status) }
    if out, status := rpcStatus("config/dump", map[string]any{}); status == 200 {
        t.Fatalf("wrong password unexpectedly loaded config: %s", out)
    }

    RcloneResetConfig()
    if _, status := rpcStatus("options/set", map[string]any{"main": map[string]any{"AskPassword": false}}); status != 200 { t.Fatalf("options/set status=%d", status) }
    if _, status := rpcStatus("config/setpath", map[string]any{"path": path}); status != 200 { t.Fatalf("setpath status=%d", status) }
    if _, status := rpcStatus("config/unlock", map[string]any{"configPassword": "correct-password"}); status != 200 { t.Fatalf("unlock status=%d", status) }
    out, status := rpcStatus("config/dump", map[string]any{})
    if status != 200 { t.Fatalf("correct password failed: %s", out) }
    var dump map[string]any
    if err := json.Unmarshal([]byte(out), &dump); err != nil { t.Fatal(err) }
    if _, ok := dump["b2remote"]; !ok { t.Fatalf("b2remote missing: %s", out) }
    if _, ok := dump["b2crypt"]; !ok { t.Fatalf("b2crypt missing: %s", out) }
}
