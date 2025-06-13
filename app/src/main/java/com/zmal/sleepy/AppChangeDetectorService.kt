// AppChangeDetectorService.kt
package com.zmal.sleepy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AppChangeDetectorService : AccessibilityService() {

    companion object {
        private const val REPORT_DELAY_MS = 1000L
        private const val CONFIG_NAME = "config"
        private const val JSON_MIME = "application/json"
        private const val USER_AGENT = "Sleep-Android"

        @Volatile
        var lastApp: String? = null
        @Volatile
        var batteryPct: Int? = null
    }

    private val httpClient by lazy { createHttpClient() }
    private val dateFormat by lazy {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reportRunnable: Runnable? = null
    private var lastSentTime = 0L
    private var pendingAppName: String? = null
    private var isUsing: Boolean = true
    private var isCharging: Boolean = false

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
            notificationTimeout = 100
        }
        logInfo("无障碍服务已连接")
        startKeepAliveService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        event.packageName?.toString()?.takeIf { it.isNotEmpty() }?.let { packageName ->
            handlePackageChange(packageName)
        }
    }

    private fun handlePackageChange(packageName: String) {
        if (packageName == lastApp) return
        if (isInputMethod(packageName)) return

        pendingAppName = getAppName(packageName)
        reportRunnable?.let { handler.removeCallbacks(it) }

        reportRunnable = Runnable {
            lastSentTime = System.currentTimeMillis()
            updateDeviceState()
            logAppSwitch()
            pendingAppName?.let { sendToServer(it) }
            lastApp = packageName
            pendingAppName = null
        }
        handler.postDelayed(reportRunnable!!, REPORT_DELAY_MS)
    }

    private fun isInputMethod(packageName: String): Boolean {
        return packageName.contains("input", true) ||
                packageName.contains("ime", true) ||
                getAppName(packageName).contains("输入法", true)
    }

    private fun logAppSwitch() {
        val time = dateFormat.format(Date(lastSentTime))
        logInfo("[$time]检测到应用切换: $pendingAppName")
    }

    private fun updateDeviceState() {
        isUsing = !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        updateBatteryStatus()
    }

    private fun updateBatteryStatus() {
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPct = (level * 100 / scale.toFloat()).toInt()
            isCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
    }

    private fun sendToServer(appName: String) {
        val (url, secret, id, showName) = getConfigValues() ?: run {
            logInfo("配置参数不完整")
            return
        }

        val request = Request.Builder()
            .url(url)
            .post(createRequestBody(appName, secret, id, showName))
            .addHeader("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).enqueue(ServerCallback())
    }

    private fun getConfigValues(): Config? {
        val prefs = getSharedPreferences(CONFIG_NAME, MODE_PRIVATE)
        return prefs.run {
            val url = getString("server_url", null)
            val secret = getString("secret", null)
            val id = getString("id", null)
            val showName = getString("show_name", null)

            if (url.isNullOrEmpty() || secret.isNullOrEmpty() ||
                id.isNullOrEmpty() || showName.isNullOrEmpty()
            ) null
            else Config(url, secret, id, showName)
        }
    }

    private fun createRequestBody(
        appName: String,
        secret: String,
        id: String,
        showName: String
    ): RequestBody {
        val json = JSONObject().apply {
            put("id", id)
            put("secret", secret)
            put("show_name", showName)
            put("using", isUsing)
            put("app_name", "$appName[$batteryPct%]${if (isCharging) "⚡️" else "🔋"}")
        }
        return json.toString().toRequestBody(JSON_MIME.toMediaType())
    }

    private fun getAppName(packageName: String): String = try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !packageManager.isPackageInstalledCompat(packageName) -> packageName

            else -> packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            ).toString()
        }
    } catch (e: Exception) {
        logInfo("获取应用名失败: ${e.message ?: "未知错误"}")
        packageName
    }

    private fun PackageManager.isPackageInstalledCompat(pkg: String): Boolean =
        try {
            getPackageInfo(pkg, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private inner class ServerCallback : Callback {
        override fun onFailure(call: Call, e: IOException) {
            logInfo("发送失败: ${e.message}")
        }

        override fun onResponse(call: Call, response: Response) {
            response.use {
                when {
                    response.isSuccessful && response.code == 200 -> Unit
                    response.isSuccessful -> logInfo("非预期响应: ${response.code}")
                    else -> logInfo("服务器错误: ${response.code} - ${response.message}")
                }
            }
        }
    }

    private fun createHttpClient() = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun onInterrupt() = logInfo("无障碍服务被中断")
    override fun onUnbind(intent: Intent?): Boolean {
        reportRunnable?.let { handler.removeCallbacks(it) }
        reportRunnable = null
        val stopIntent = Intent(this, KeepAliveService::class.java)
        stopService(stopIntent)
        // 允许系统重连本服务
        logInfo("服务解绑")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        startKeepAliveService()
        logInfo("服务已重连")
    }

    override fun onDestroy() {
        onUnbind(null)
        logInfo("无障碍服务已销毁")
        super.onDestroy()
    }


    private fun logInfo(msg: String) = LogRepository.addLog(msg)
    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
        startForegroundService(intent)
    }

    private data class Config(
        val url: String,
        val secret: String,
        val id: String,
        val showName: String
    )
}