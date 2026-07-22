package vn.vdpr.video.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import vn.vdpr.video.R
import vn.vdpr.video.StreamActivity

/**
 * Foreground service giữ process khi đang livestream — giảm bị hệ thống kill khi nóng/RAM cao.
 */
class StreamKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val court = intent?.getStringExtra(EXTRA_COURT) ?: "Sân"
                val matchId = intent?.getIntExtra(EXTRA_MATCH_ID, 0) ?: 0
                startAsForeground(court, matchId)
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(court: String, matchId: Int) {
        ensureChannel()
        val open = Intent(this, StreamActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("match_id", matchId)
        }
        val pi = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VDPR Live đang phát")
            .setContentText("Sân: $court — giữ app chạy nền")
            .setSmallIcon(R.drawable.logo)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Livestream",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Giữ kết nối livestream khi app đang phát"
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "vdpr_stream_keepalive"
        private const val NOTIF_ID = 4201
        const val ACTION_START = "vn.vdpr.video.STREAM_KEEPALIVE_START"
        const val ACTION_STOP = "vn.vdpr.video.STREAM_KEEPALIVE_STOP"
        private const val EXTRA_COURT = "court"
        private const val EXTRA_MATCH_ID = "match_id"

        fun start(context: Context, courtName: String, matchId: Int) {
            val intent = Intent(context, StreamKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_COURT, courtName)
                putExtra(EXTRA_MATCH_ID, matchId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, StreamKeepAliveService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                context.stopService(Intent(context, StreamKeepAliveService::class.java))
            }
        }
    }
}
