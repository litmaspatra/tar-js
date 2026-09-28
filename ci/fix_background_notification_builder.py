from pathlib import Path

p = Path('source/TAR-JS/app/src/main/java/com/tarjs/archive/IndexingService.kt')
s = p.read_text(encoding='utf-8')
s = s.replace('import androidx.core.app.NotificationCompat\n', 'import android.app.Notification\n')
old = '''    private fun notification(title: String, text: String, ongoing: Boolean, processed: Int = 0, total: Int = 0) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title).setContentText(text).setOnlyAlertOnce(ongoing).setOngoing(ongoing).setAutoCancel(!ongoing)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .apply { if (ongoing) { if (total > 0) setProgress(total, processed.coerceAtMost(total), false) else setProgress(0, 0, true) } }
        .build()
'''
new = '''    private fun notification(title: String, text: String, ongoing: Boolean, processed: Int = 0, total: Int = 0): Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (ongoing) {
            if (total > 0) builder.setProgress(total, processed.coerceAtMost(total), false)
            else builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
'''
if old not in s:
    raise SystemExit('NotificationCompat builder block not found')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('FRAMEWORK_NOTIFICATION_BUILDER_APPLIED')
