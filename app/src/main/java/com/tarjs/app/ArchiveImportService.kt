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
        cancelled.set(false)
        writeStatus(this, ImportStatus("indexing", 0, 0, "Preparing archive…", null))
        startForeground(NOTIFICATION_ID, notification("Indexing Telegram archive", "Preparing…", 0, 0))
        executor.execute {
            val db = ArchiveDb(applicationContext)
            try {
                val text = snapshot.readText(Charsets.UTF_8)
                val result = db.importJson(text) { done, total ->
                    if (cancelled.get()) throw InterruptedException("Import cancelled")
                    writeStatus(this, ImportStatus("indexing", done, total, "$done / $total messages indexed", null))
                    if (done == total || done % 5000 == 0) {
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            notification("Indexing Telegram archive", "$done / $total messages", done, total)
                        )
                    }
                }
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
                db.close()
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
        private const val PREFS = "import-status"

        fun start(context: Context, snapshot: File) {
            writeStatus(context, ImportStatus("indexing", 0, 0, "Preparing archive…", null))
            val intent = Intent(context, ArchiveImportService::class.java).putExtra(EXTRA_SNAPSHOT, snapshot.absolutePath)
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
    val active: Boolean get() = phase == "indexing"
}
