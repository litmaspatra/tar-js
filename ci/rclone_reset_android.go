package gomobile

import (
    "github.com/rclone/rclone/fs/config"
    "github.com/rclone/rclone/fs/config/configfile"
)

// RcloneResetConfig clears cached config password/state before a new import.
func RcloneResetConfig() {
    config.ClearConfigPassword()
    configfile.Install()
}

// RclonePrepareConfig selects an app-private config and installs file-backed
// storage. Android may initialize rclone without a home directory, which leaves
// config in memory-only mode; config/setpath alone does not replace that
// storage backend.
func RclonePrepareConfig(path string) error {
    config.ClearConfigPassword()
    if err := config.SetConfigPath(path); err != nil {
        return err
    }
    configfile.Install()
    return nil
}
