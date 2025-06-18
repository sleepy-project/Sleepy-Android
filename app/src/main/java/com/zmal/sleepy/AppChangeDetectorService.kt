package com.zmal.sleepy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AppChangeDetectorService : AccessibilityService() {

    companion object {
        private const val REPORT_DELAY_MS = 1000L
        private const val CONFIG_NAME = "config"
        private const val JSON_MIME = "application/json"
        private const val USER_AGENT = "Sleep-Android"

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        @Volatile
        var lastPackageName: String? = null
        @Volatile
        var lastAppName: String? = null
        @Volatile
        var batteryPct: Int? = null
    }
    private data class Config(
        val url: String,
        val secret: String,
        val id: String,
        val showName: String
    )
    private val dateFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    private val handler = Handler(Looper.getMainLooper())
    private var reportRunnable: Runnable? = null
    private var lastSentTime = 0L
    private var pendingAppName: String? = null
    private var isUsing = true
    private var isCharging = false
    private lateinit var keepAliveIntent: Intent

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        logInfo("无障碍服务已连接")
        keepAliveIntent = Intent(this, KeepAliveService::class.java)
        startKeepAliveService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        event.packageName?.toString()?.takeIf { it.isNotEmpty() }?.let { packageName ->
            handlePackageChange(packageName)
        }
    }

    override fun onInterrupt() = logInfo("无障碍服务被中断")

    override fun onUnbind(intent: Intent?): Boolean {
        reportRunnable?.let { handler.removeCallbacks(it) }
        reportRunnable = null
        stopService(keepAliveIntent)
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

    private fun handlePackageChange(packageName: String) {
        if (isInputMethod(packageName)) return

        val resolvedAppName = resolveAppName(packageName)
        if (packageName == lastPackageName) return

        reportRunnable?.let { handler.removeCallbacks(it) }
        lastPackageName = packageName

        reportRunnable = Runnable {
            lastSentTime = System.currentTimeMillis()
            updateDeviceState()
            pendingAppName = resolvedAppName
            logAppSwitch()
            sendToServer(resolvedAppName)
        }
        handler.postDelayed(reportRunnable!!, REPORT_DELAY_MS)
    }

    private fun resolveAppName(packageName: String): String {
        val appName = getAppName(packageName)
        return if (appName == "系统界面") {
            lastAppName ?: appName
        } else {
            lastAppName = appName
            appName
        }
    }

    private fun isInputMethod(packageName: String): Boolean {
        return try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)
            info.services?.any { it.permission == "android.permission.BIND_INPUT_METHOD" } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun logAppSwitch() {
        val time = if (lastSentTime > 0) dateFormat.format(Date(lastSentTime)) else "unknown"
        logInfo("[$time] - $pendingAppName")
    }


    private fun updateDeviceState() {
        isUsing = !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val chargingStatus = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        isCharging = chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun sendToServer(appName: String) {
        val config = getConfigValues() ?: run {
            logInfo("配置参数不完整")
            return
        }

        val request = Request.Builder()
            .url(config.url)
            .post(createRequestBody(appName, config.secret, config.id, config.showName))
            .addHeader("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).enqueue(ServerCallback())
    }

    private fun getConfigValues(): Config? = getSharedPreferences(CONFIG_NAME, MODE_PRIVATE).run {
        val url = getString("server_url", null) ?: return null
        val secret = getString("secret", null) ?: return null
        val id = getString("id", null) ?: return null
        val showName = getString("show_name", null) ?: return null
        Config(url, secret, id, showName)
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
            put(
                "app_name",
                "$appName[${batteryPct ?: "-"}%]${if (isCharging) "\u26A1\uFE0F" else "\uD83D\uDD0B"}"
            )
        }
        return json.toString().toRequestBody(JSON_MIME.toMediaType())
    }

    private fun getAppName(packageName: String): String = try {
        val appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        packageManager.getApplicationLabel(appInfo).toString()
    } catch (e: Exception) {
        logInfo("获取应用名失败: ${e.message ?: "未知错误"}")
        packageName
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

    private fun logInfo(msg: String) = LogRepository.addLog(msg)

    private fun startKeepAliveService() {
        if (::keepAliveIntent.isInitialized) {
            try {
                startForegroundService(keepAliveIntent)
            } catch (e: Exception) {
                logInfo("无法启动保活服务: ${e.message}")
            }
        }
    }

}
