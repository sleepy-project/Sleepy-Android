package com.zmal.sleepy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
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
    private lateinit var updateReceiver: BroadcastReceiver

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())


    }
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(updateReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sleepy后台服务")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }


}
