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
