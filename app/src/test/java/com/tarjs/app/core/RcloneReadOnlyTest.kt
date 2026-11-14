package com.tarjs.app.core

import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app must never modify rclone/Backblaze storage: imports and media
 * reads only. The runtime allowlist blocks every other RPC, and copies may
 * only ever flow remote -> local.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RcloneReadOnlyTest {

    @Test
    fun remoteWriteOperationsAreBlocked() {
        val writes = listOf(
            "operations/delete", "operations/deletefile", "operations/purge",
            "operations/movefile", "operations/mkdir", "operations/rmdir",
            "operations/copyfiledest", "sync/sync", "sync/move", "sync/copy",
            "config/create", "config/update", "config/delete", "rc/noop"
        )
        for (method in writes) {
            assertThrows("$method must be blocked", IllegalArgumentException::class.java) {
                RcloneRuntime.checkReadOnlyOperation(method, JSONObject())
            }
        }
    }

    @Test
    fun copyToRemoteDestinationIsBlocked() {
        val params = JSONObject().put("dstFs", "b2:my-bucket").put("srcFs", "local:")
        assertThrows(IllegalArgumentException::class.java) {
            RcloneRuntime.checkReadOnlyOperation("operations/copyfile", params)
        }
    }

    @Test
    fun copyFromLocalSourceIsBlocked() {
        val params = JSONObject().put("dstFs", "/data/data/com.tarjs/files").put("srcFs", "/sdcard/exports")
        assertThrows(IllegalArgumentException::class.java) {
            RcloneRuntime.checkReadOnlyOperation("operations/copyfile", params)
        }
    }

    @Test
    fun readOnlyOperationsPass() {
        RcloneRuntime.checkReadOnlyOperation("operations/list", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("config/listremotes", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("config/dump", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("config/unlock", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("options/set", JSONObject())
        val remoteToLocal = JSONObject().put("dstFs", "/data/data/com.tarjs/files").put("srcFs", "b2:")
        RcloneRuntime.checkReadOnlyOperation("operations/copyfile", remoteToLocal)
    }
}
