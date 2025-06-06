package com.zmal.sleepy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "KeepAliveServiceChannel"
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_NAME = "后台保活通知"
        private const val CHANNEL_DESC = "确保应用在后台持续运行"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 可以根据 intent 做相应处理
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = CHANNEL_DESC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

    }

    private fun buildNotification(): Notification {
        val prefs = getSharedPreferences("notes", MODE_PRIVATE)
        val lastApp = prefs.getString("last_app", "无")
        val batteryPct = prefs.getInt("battery_pct", -1)

        val contentText = "$lastApp[$batteryPct]"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sleepy后台服务")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }


}
