package com.zmal.sleepy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.service.quicksettings.TileService
import android.widget.Toast
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
        ServiceTracker.serviceStarted(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }
    override fun onDestroy() {
        super.onDestroy()
        ServiceTracker.serviceStopped(this)
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sleepy后台服务")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }
}
object ServiceTracker {
    private val runningServices = mutableSetOf<String>()

    fun serviceStarted(service: Service) {
        runningServices.add(service.javaClass.name)
    }

    fun serviceStopped(service: Service) {
        runningServices.remove(service.javaClass.name)
    }

    fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return runningServices.contains(serviceClass.name)
    }

    fun restartAccessibilityService(context: Context) {
        val packageName = context.packageName
        val serviceName = "com.zmal.sleepy.AppChangeDetectorService"
        val componentName = "$packageName/$serviceName"
        val commands = listOf(
            "settings put secure accessibility_enabled 0",
            "sleep 1",
            "settings put secure enabled_accessibility_services $componentName",
            "settings put secure accessibility_enabled 1"
        )

        try {
            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                process.waitFor()
            }
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

class NoteTiles : TileService() {

    override fun onClick() {
        super.onClick()
        val serviceIntent = Intent(this, KeepAliveService::class.java)
        try{
            ServiceTracker.restartAccessibilityService(this)
            Toast.makeText(this, "[su]Ciallo～(∠・ω< )⌒★", Toast.LENGTH_SHORT).show()
        }catch (_: Exception){
            if (isServiceRunning(KeepAliveService::class.java)) {
                stopService(serviceIntent)
                Toast.makeText(this, "已关闭通知服务", Toast.LENGTH_SHORT).show()
            } else {
                startService(serviceIntent)
                Toast.makeText(this, "Ciallo～(∠・ω< )⌒★", Toast.LENGTH_SHORT).show()
            }
        }

    }
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return ServiceTracker.isServiceRunning(serviceClass)
    }


}