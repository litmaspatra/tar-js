package com.tarjs.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tarjs.app.core.ArchiveDb
import com.tarjs.app.core.RcloneRuntime
import com.tarjs.app.core.SafArchiveSource
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground importer so large Telegram archives continue while UI is backgrounded. */
class ArchiveImportService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled.set(true)
            return START_NOT_STICKY
        }
        val snapshot = intent?.getStringExtra(EXTRA_SNAPSHOT)?.let(::File) ?: return START_NOT_STICKY
        val remote = intent.getStringExtra(EXTRA_REMOTE)
        val remoteFile = intent.getStringExtra(EXTRA_REMOTE_FILE)
        cancelled.set(false)
        val needsDownload = remote != null && remoteFile != null
        val initialStatus = if (needsDownload) {
            ImportStatus("downloading", 0, 0, "Downloading result.json", null)
        } else {
            ImportStatus("indexing", 0, 0, "Reading archive", null)
        }
        writeStatus(this, initialStatus)
        startForeground(NOTIFICATION_ID, notification("Preparing Telegram archive", initialStatus.message, 0, 0))
        executor.execute {
            var db: ArchiveDb? = null
            try {
                if (needsDownload) {
                    if (cancelled.get()) throw InterruptedException("Import cancelled")
                    snapshot.parentFile?.mkdirs()
                    RcloneRuntime.copyToLocal(remote!!, remoteFile!!, snapshot)
                    require(snapshot.isFile && snapshot.length() > 0L) { "Downloaded result.json is empty" }
                    saveRcloneSource(intent, snapshot)
                    writeStatus(this, ImportStatus("indexing", 0, 0, "Reading archive", null))
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification("Indexing Telegram archive", "Reading archive", 0, 0)
                    )
                }
                val archiveDb = ArchiveDb(applicationContext)
                db = archiveDb
                var latestDone = 0
                var latestTotal = 0
                val result = archiveDb.importJson(
                    snapshot,
                    progress = { done, total ->
                        if (cancelled.get()) throw InterruptedException("Import cancelled")
                        latestDone = done
                        latestTotal = total
                        writeStatus(this, ImportStatus("indexing", done, total, "$done / $total messages indexed", null))
                        if (done == total || done % 5000 == 0) {
                            getSystemService(NotificationManager::class.java).notify(
                                NOTIFICATION_ID,
                                notification("Indexing Telegram archive", "$done / $total messages", done, total)
                            )
                        }
                    },
                    stage = { message ->
                        if (cancelled.get()) throw InterruptedException("Import cancelled")
                        writeStatus(this, ImportStatus("indexing", latestDone, latestTotal, message, null))
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            notification("Indexing Telegram archive", message, latestDone, latestTotal)
                        )
                    }
                )
                if (result.error != null) error(result.error)
                writeStatus(this, ImportStatus("complete", result.messages, result.messages, "Indexed ${result.messages} messages", null))
                getSystemService(NotificationManager::class.java).notify(
                    COMPLETE_NOTIFICATION_ID,
                    notification("TAR-JS indexing complete", "${result.messages} messages are ready", result.messages, result.messages, complete = true)
                )
            } catch (e: Exception) {
                val message = if (cancelled.get()) "Indexing cancelled" else (e.message ?: "Indexing failed")
                writeStatus(this, ImportStatus(if (cancelled.get()) "cancelled" else "error", 0, 0, message, message))
            } finally {
                db?.close()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveRcloneSource(intent: Intent, snapshot: File) {
        val id = intent.getStringExtra(EXTRA_SOURCE_ID) ?: return
        val path = intent.getStringExtra(EXTRA_SOURCE_PATH) ?: return
        val importedAt = intent.getLongExtra(EXTRA_IMPORTED_AT, System.currentTimeMillis())
        SafArchiveSource.saveArchiveMetadata(
            this,
            SafArchiveSource.ArchiveSource(
                id = id,
                type = "rclone",
                path = path,
                resultJsonHash = "",
                importedAt = importedAt
            )
        )
    }

    private fun notification(title: String, text: String, done: Int, total: Int, complete: Boolean = false): Notification {
        val launch = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launch)
            .setOnlyAlertOnce(!complete)
            .setOngoing(!complete)
        if (!complete) {
            if (total > 0) builder.setProgress(total, done.coerceAtMost(total), false) else builder.setProgress(0, 0, true)
            val cancelIntent = PendingIntent.getService(
                this, 1, Intent(this, ArchiveImportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Cancel", cancelIntent)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Archive indexing", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "tarjs-indexing"
        private const val NOTIFICATION_ID = 4201
        private const val COMPLETE_NOTIFICATION_ID = 4202
        private const val ACTION_CANCEL = "com.tarjs.app.CANCEL_IMPORT"
        private const val EXTRA_SNAPSHOT = "snapshot"
        private const val EXTRA_REMOTE = "remote"
        private const val EXTRA_REMOTE_FILE = "remoteFile"
        private const val EXTRA_SOURCE_ID = "sourceId"
        private const val EXTRA_SOURCE_PATH = "sourcePath"
        private const val EXTRA_IMPORTED_AT = "importedAt"
        private const val PREFS = "import-status"

        fun start(context: Context, snapshot: File) {
            writeStatus(context, ImportStatus("indexing", 0, 0, "Preparing archive…", null))
            val intent = Intent(context, ArchiveImportService::class.java).putExtra(EXTRA_SNAPSHOT, snapshot.absolutePath)
            ContextCompat.startForegroundService(context, intent)
        }

        fun startRclone(
            context: Context,
            snapshot: File,
            remote: String,
            remoteFile: String,
            source: SafArchiveSource.ArchiveSource
        ) {
            writeStatus(context, ImportStatus("downloading", 0, 0, "Downloading result.json", null))
            val intent = Intent(context, ArchiveImportService::class.java)
                .putExtra(EXTRA_SNAPSHOT, snapshot.absolutePath)
                .putExtra(EXTRA_REMOTE, remote)
                .putExtra(EXTRA_REMOTE_FILE, remoteFile)
                .putExtra(EXTRA_SOURCE_ID, source.id)
                .putExtra(EXTRA_SOURCE_PATH, source.path)
                .putExtra(EXTRA_IMPORTED_AT, source.importedAt)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ArchiveImportService::class.java).setAction(ACTION_CANCEL))
        }

        fun status(context: Context): ImportStatus {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ImportStatus(
                p.getString("phase", "idle") ?: "idle",
                p.getInt("done", 0), p.getInt("total", 0),
                p.getString("message", "") ?: "", p.getString("error", null)
            )
        }

        private fun writeStatus(context: Context, status: ImportStatus) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("phase", status.phase).putInt("done", status.done).putInt("total", status.total)
                .putString("message", status.message).putString("error", status.error).apply()
        }
    }
}

data class ImportStatus(val phase: String, val done: Int, val total: Int, val message: String, val error: String?) {
    val active: Boolean get() = phase == "downloading" || phase == "indexing"
}
