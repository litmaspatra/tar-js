package main

import (
    "encoding/json"
    "fmt"
    "io"
    "os"
    "os/exec"
    "path/filepath"
    "strings"

    _ "github.com/rclone/rclone/backend/all"
    "github.com/rclone/rclone/fs/config"
    "github.com/rclone/rclone/librclone/librclone"
)

const password = "dummy-config-password"

func must(err error) {
    if err != nil {
        panic(err)
    }
}

func rpc(method string, input map[string]any) map[string]any {
    raw, err := json.Marshal(input)
    must(err)
    out, status := librclone.RPC(method, string(raw))
    if status != 200 {
        panic(fmt.Sprintf("%s failed: status=%d output=%s", method, status, out))
    }
    var parsed map[string]any
    must(json.Unmarshal([]byte(out), &parsed))
    return parsed
}

func createEncryptedConfig(outPath, remoteRoot string) {
    plain := fmt.Sprintf(`[dummy]
type = alias
remote = %s

[cryptdummy]
type = crypt
remote = dummy:
password = nCH8gXJp7u0DXC0c1qM
password2 = nCH8gXJp7u0DXC0c1qM
`, remoteRoot)

    must(config.SetConfigPassword(password))
    src := strings.NewReader(plain)
    dst, err := os.Create(outPath)
    must(err)
    defer dst.Close()
    must(config.Encrypt(src, dst))
}

func probe(configPath, remoteRoot string) {
    librclone.Initialize()
    defer librclone.Finalize()

    rpc("options/set", map[string]any{"main": map[string]any{"AskPassword": false}})
    rpc("config/unlock", map[string]any{"configPassword": password})
    rpc("config/setpath", map[string]any{"path": configPath})

    remotes := rpc("config/listremotes", map[string]any{})
    names, ok := remotes["remotes"].([]any)
    if !ok {
        panic(fmt.Sprintf("unexpected remotes payload: %#v", remotes))
    }
    seenDummy := false
    seenCrypt := false
    for _, item := range names {
        switch strings.TrimSuffix(fmt.Sprint(item), ":") {
        case "dummy":
            seenDummy = true
        case "cryptdummy":
            seenCrypt = true
        }
    }
    if !seenDummy || !seenCrypt {
        panic(fmt.Sprintf("expected dummy and cryptdummy remotes, got %#v", names))
    }

    listing := rpc("operations/list", map[string]any{
        "fs":     "dummy:",
        "remote": "",
        "opt":    map[string]any{"noModTime": false},
    })
    entries, ok := listing["list"].([]any)
    if !ok {
        panic(fmt.Sprintf("unexpected list payload: %#v", listing))
    }
    foundResult := false
    foundMediaDir := false
    for _, raw := range entries {
        row := raw.(map[string]any)
        name := fmt.Sprint(row["Name"])
        if name == "result.json" && row["IsDir"] == false {
            foundResult = true
        }
        if name == "media" && row["IsDir"] == true {
            foundMediaDir = true
        }
    }
    if !foundResult || !foundMediaDir {
        panic(fmt.Sprintf("dummy browse missing expected entries: %#v", entries))
    }

    destDir, err := os.MkdirTemp("", "tarjs-copy-")
    must(err)
    defer os.RemoveAll(destDir)
    rpc("operations/copyfile", map[string]any{
        "srcFs":     "dummy:",
        "srcRemote": "result.json",
        "dstFs":     destDir,
        "dstRemote": "result.json",
    })
    copied, err := os.ReadFile(filepath.Join(destDir, "result.json"))
    must(err)
    if !strings.Contains(string(copied), `"Dummy Messages"`) {
        panic("copied result.json did not contain dummy Telegram data")
    }

    fmt.Println("RCLONE_DUMMY_UNLOCK_LIST_COPY_OK")
}

func wrongPasswordProbe(configPath string) {
    librclone.Initialize()
    defer librclone.Finalize()

    rpc("options/set", map[string]any{"main": map[string]any{"AskPassword": false}})
    rpc("config/unlock", map[string]any{"configPassword": "wrong-password"})
    rpc("config/setpath", map[string]any{"path": configPath})

    raw, status := librclone.RPC("config/listremotes", `{}`)
    if status == 200 {
        panic("wrong password unexpectedly succeeded")
    }
    if !strings.Contains(strings.ToLower(raw), "decrypt") && !strings.Contains(strings.ToLower(raw), "password") {
        panic(fmt.Sprintf("wrong password returned unexpected error: %s", raw))
    }
    fmt.Println("RCLONE_WRONG_PASSWORD_RETURNS_ERROR_OK")
}

func main() {
    if len(os.Args) >= 2 {
        switch os.Args[1] {
        case "probe":
            probe(os.Args[2], os.Args[3])
            return
        case "wrong":
            wrongPasswordProbe(os.Args[2])
            return
        }
    }

    root, err := os.MkdirTemp("", "tarjs-rclone-architecture-")
    must(err)
    defer os.RemoveAll(root)

    remoteRoot := filepath.Join(root, "remote")
    must(os.MkdirAll(filepath.Join(remoteRoot, "media"), 0o755))
    resultJSON := `{"about":"dummy","chats":{"list":[{"name":"Dummy Messages","type":"personal_chat","id":1,"messages":[{"id":1,"type":"message","date_unixtime":"1790467200","from":"Dummy Alice","text":"hello"}]}]}}`
    must(os.WriteFile(filepath.Join(remoteRoot, "result.json"), []byte(resultJSON), 0o600))
    must(os.WriteFile(filepath.Join(remoteRoot, "media", "photo.jpg"), []byte("dummy-media"), 0o600))

    configPath := filepath.Join(root, "rclone.conf")
    createEncryptedConfig(configPath, remoteRoot)

    exe, err := os.Executable()
    must(err)
    probeCmd := exec.Command(exe, "probe", configPath, remoteRoot)
    probeCmd.Stdout = os.Stdout
    probeCmd.Stderr = os.Stderr
    must(probeCmd.Run())

    wrongCmd := exec.Command(exe, "wrong", configPath)
    wrongCmd.Stdout = os.Stdout
    wrongCmd.Stderr = os.Stderr
    must(wrongCmd.Run())

    f, err := os.Open(configPath)
    must(err)
    defer f.Close()
    header := make([]byte, 256)
    n, err := f.Read(header)
    if err != nil && err != io.EOF {
        panic(err)
    }
    if !strings.Contains(string(header[:n]), "RCLONE_ENCRYPT_V0:") {
        panic("dummy config was not actually encrypted")
    }

    fmt.Println("RCLONE_ARCHITECTURE_HARNESS_OK")
}
