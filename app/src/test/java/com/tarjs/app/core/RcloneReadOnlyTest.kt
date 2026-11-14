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
    fun traversalOutsideAppDirectoryIsBlocked() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RcloneRuntime.configureReadOnlyGuard(context.dataDir)
        val escaping = org.json.JSONObject()
            .put("dstFs", context.dataDir.absolutePath + "/../../sdcard-evil")
            .put("srcFs", "b2:")
        assertThrows(IllegalArgumentException::class.java) {
            RcloneRuntime.checkReadOnlyOperation("operations/copyfile", escaping)
        }
    }

    @Test
    fun symlinkEscapeIsBlocked() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RcloneRuntime.configureReadOnlyGuard(context.dataDir)
        val outside = java.io.File(context.cacheDir, "outside-target").apply { mkdirs() }
        val link = java.io.File(context.filesDir, "evil-link")
        link.delete()
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val params = org.json.JSONObject().put("dstFs", link.absolutePath).put("srcFs", "b2:")
        // cacheDir sits inside dataDir, so use a target truly outside:
        val realOutside = java.io.File(System.getProperty("java.io.tmpdir"), "outside-" + System.nanoTime()).apply { mkdirs() }
        java.nio.file.Files.delete(link.toPath())
        java.nio.file.Files.createSymbolicLink(link.toPath(), realOutside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            RcloneRuntime.checkReadOnlyOperation("operations/copyfile", params)
        }
    }

    @Test
    fun unconfiguredGuardBlocksCopies() {
        RcloneRuntime.allowedLocalRoot = null
        val params = org.json.JSONObject().put("dstFs", "/data/data/com.tarjs/files").put("srcFs", "b2:")
        assertThrows(IllegalStateException::class.java) {
            RcloneRuntime.checkReadOnlyOperation("operations/copyfile", params)
        }
    }

    @Test
    fun nestedLocalDestinationIsAllowed() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RcloneRuntime.configureReadOnlyGuard(context.dataDir)
        val nested = java.io.File(context.filesDir, "downloads/nested").apply { mkdirs() }
        val params = org.json.JSONObject().put("dstFs", nested.absolutePath).put("srcFs", "b2:")
        RcloneRuntime.checkReadOnlyOperation("operations/copyfile", params)
    }

    @Test
    fun onlyAskPasswordOptionMayBeSet() {
        RcloneRuntime.checkReadOnlyOperation(
            "options/set",
            org.json.JSONObject().put("main", org.json.JSONObject().put("AskPassword", false))
        )
        assertThrows(IllegalArgumentException::class.java) {
            RcloneRuntime.checkReadOnlyOperation(
                "options/set",
                org.json.JSONObject().put("main", org.json.JSONObject().put("Transfers", 8))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RcloneRuntime.checkReadOnlyOperation(
                "options/set",
                org.json.JSONObject().put("other", org.json.JSONObject().put("AskPassword", false))
            )
        }
    }

    @Test
    fun readOnlyOperationsPass() {
        RcloneRuntime.checkReadOnlyOperation("operations/list", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("config/listremotes", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("config/dump", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("config/unlock", JSONObject())
        RcloneRuntime.checkReadOnlyOperation("options/set", JSONObject())
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        RcloneRuntime.configureReadOnlyGuard(context.dataDir)
        val remoteToLocal = JSONObject().put("dstFs", java.io.File(context.filesDir, "dl").apply { mkdirs() }.absolutePath).put("srcFs", "b2:")
        RcloneRuntime.checkReadOnlyOperation("operations/copyfile", remoteToLocal)
    }
}
