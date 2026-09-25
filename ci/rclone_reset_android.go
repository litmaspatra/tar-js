package gomobile

import (
    "github.com/rclone/rclone/fs/config"
    "github.com/rclone/rclone/fs/config/configfile"
)

// RcloneResetConfig clears any cached config data and config password.
// Android calls this before loading a newly selected rclone.conf so a
// previous successful unlock can never leak into a later attempt.
func RcloneResetConfig() {
    config.ClearConfigPassword()
    configfile.Install()
}
