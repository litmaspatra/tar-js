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

func TestRcloneResetEncryptedConfig(t *testing.T) {
    RcloneInitialize()
    defer RcloneFinalize()
    path := filepath.Join(t.TempDir(), "rclone.conf")
    config.ClearConfigPassword()
    if err := config.SetConfigPassword("correct-password"); err != nil { t.Fatal(err) }
    f, err := os.Create(path)
    if err != nil { t.Fatal(err) }
    if err := config.Encrypt(bytes.NewBufferString("[b2remote]\ntype = b2\n\n[b2crypt]\ntype = crypt\nremote = b2remote:telegram\n"), f); err != nil { t.Fatal(err) }
    if err := f.Close(); err != nil { t.Fatal(err) }
    config.ClearConfigPassword()

    RcloneResetConfig()
    if _, status := rpcStatus("options/set", map[string]any{"main": map[string]any{"AskPassword": false}}); status != 200 { t.Fatalf("options/set status=%d", status) }
    if _, status := rpcStatus("config/setpath", map[string]any{"path": path}); status != 200 { t.Fatalf("setpath status=%d", status) }
    if _, status := rpcStatus("config/unlock", map[string]any{"configPassword": "wrong-password"}); status != 200 { t.Fatalf("wrong unlock request status=%d", status) }
    if _, status := rpcStatus("config/dump", map[string]any{}); status == 200 { t.Fatal("wrong password unexpectedly unlocked config") }

    RcloneResetConfig()
    if _, status := rpcStatus("options/set", map[string]any{"main": map[string]any{"AskPassword": false}}); status != 200 { t.Fatalf("options/set status=%d", status) }
    if _, status := rpcStatus("config/setpath", map[string]any{"path": path}); status != 200 { t.Fatalf("setpath status=%d", status) }
    if _, status := rpcStatus("config/unlock", map[string]any{"configPassword": "correct-password"}); status != 200 { t.Fatalf("correct unlock status=%d", status) }
    out, status := rpcStatus("config/dump", map[string]any{})
    if status != 200 { t.Fatalf("correct password failed: %s", out) }
    var dump map[string]any
    if err := json.Unmarshal([]byte(out), &dump); err != nil { t.Fatal(err) }
    if _, ok := dump["b2remote"]; !ok { t.Fatalf("b2remote missing: %s", out) }
    if _, ok := dump["b2crypt"]; !ok { t.Fatalf("b2crypt missing: %s", out) }

    remotesOut, status := rpcStatus("config/listremotes", map[string]any{})
    if status != 200 { t.Fatalf("listremotes failed after verified unlock: %s", remotesOut) }
    var remotes struct { Remotes []string `json:"remotes"` }
    if err := json.Unmarshal([]byte(remotesOut), &remotes); err != nil { t.Fatal(err) }
    if len(remotes.Remotes) != 2 { t.Fatalf("expected 2 remotes after unlock, got %d: %s", len(remotes.Remotes), remotesOut) }
}
