package app.wisp.keyboard.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.wisp.keyboard.R

/**
 * Keeps the settings process alive while something is downloading, with a notification showing progress. It stops itself the moment
 * nothing is in flight, so it never lingers (no work when idle).
 */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val d = ModelDownloads.get(this@DownloadService)
            if (d.activeCount == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            getSystemService(NotificationManager::class.java).notify(ID, notification(d))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = notification(ModelDownloads.get(this))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }

    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }

    private fun notification(d: ModelDownloads): Notification {
        val active = d.transfers.entries.firstOrNull { it.value is Transfer.Downloading || it.value is Transfer.Installing }
        val title = active?.let { app.wisp.keyboard.voice.model.ModelCatalog.byId(it.key)?.name }?.let { "Getting $it" } ?: "Wisp"
        val b = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setOngoing(true).setOnlyAlertOnce(true)
        when (val t = active?.value) {
            is Transfer.Downloading -> b.setProgress(100, if (t.total > 0) (t.done * 100 / t.total).toInt() else 0, t.total <= 0).setContentText("%.0f of %.0f MB".format(t.done / 1048576f, t.total / 1048576f))
            is Transfer.Installing -> b.setProgress(0, 0, true).setContentText("Unpacking")
            else -> b.setContentText("Working")
        }
        return b.build()
    }

    companion object {
        private const val CHANNEL = "downloads"; private const val ID = 7
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }
}
