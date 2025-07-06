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
import java.util.concurrent.TimeUnit

class AppChangeDetectorService : AccessibilityService() {

    companion object {
        private const val REPORT_DELAY_MS = 1000L
        private const val CONFIG_NAME = "config"
        private const val JSON_MIME = "application/json"
        private const val USER_AGENT = "Sleep-Android"

        var cachedConfig: Config? = null

        val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
        }

        @Volatile
        var lastPackageName: String? = null

        @Volatile
        var lastAppName: String? = null

        @Volatile
        var batteryPct: Int? = null
    }

    data class Config(
        val url: String, val secret: String, val id: String, val showName: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private var reportRunnable: Runnable? = null
    private var isUsing = true
    private var isCharging = false
    private lateinit var keepAliveIntent: Intent

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        logs(LogLevel.INFO, "无障碍服务已连接")
        keepAliveIntent = Intent(this, KeepAliveService::class.java)
        startKeepAliveService()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.className == "android.widget.FrameLayout" || event.className == "android.widget.ScrollView") {
            logs(
                LogLevel.VERBOSE,
                "忽略无关事件: eventType=${event.eventType}, className=${event.className}"
            )
            return
        }

        val pkgName = event.packageName?.toString()
        val currentIsUsing =
            !(getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

        if (pkgName == lastPackageName && isUsing == currentIsUsing) {
            logs(LogLevel.VERBOSE, "已忽略: $pkgName，isUsing未变=$isUsing")
            return
        }

        isUsing = currentIsUsing

        logs(LogLevel.DEBUG, "处理包名: $pkgName，类名：${event.className}")

        pkgName?.takeIf { it.isNotEmpty() }?.let { packageName ->
            handlePackageChange(packageName)
        }
    }

    override fun onInterrupt() = logs(LogLevel.ERROR, "无障碍服务被中断")

    override fun onUnbind(intent: Intent?): Boolean {
        reportRunnable?.let { handler.removeCallbacks(it) }
        reportRunnable = null
        stopService(keepAliveIntent)
        logs(LogLevel.INFO, "服务解绑")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        startKeepAliveService()
        logs(LogLevel.INFO, "服务已重连")
    }

    override fun onDestroy() {
        reportRunnable?.let { handler.removeCallbacks(it) }
        stopService(keepAliveIntent)
        logs(LogLevel.DEBUG, "无障碍服务已销毁")
        super.onDestroy()
    }

    private fun handlePackageChange(packageName: String) {
        if (isInputMethod(packageName)) {
            logs(LogLevel.VERBOSE, "忽略输入法包名: $packageName")
            return
        }

        reportRunnable?.let {
            logs(LogLevel.DEBUG, "取消: $lastPackageName;替换: $packageName")
            handler.removeCallbacks(it)
        }

        lastPackageName = packageName

        reportRunnable = Runnable {
            val resolvedAppName = resolveAppName(packageName)
            updateDeviceState()
            logs(LogLevel.VERBOSE, "亮屏:$isUsing")
            sendToServer(resolvedAppName)
            reportRunnable = null
        }
        handler.postDelayed(reportRunnable!!, REPORT_DELAY_MS)
    }

    private fun updateDeviceState() {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val chargingStatus = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        isCharging =
            chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        logs(
            LogLevel.VERBOSE,
            "设备状态 - isUsing=$isUsing, isCharging=$isCharging, batteryPct=$batteryPct"
        )
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

    private fun sendToServer(appName: String) {
        val config = getConfigValues() ?: run {
            logs(LogLevel.ERROR, "配置参数不完整")
            return
        }

        val request = Request.Builder().url(config.url)
            .post(createRequestBody(appName, config.secret, config.id, config.showName))
            .addHeader("User-Agent", USER_AGENT).build()
        logs(LogLevel.INFO, appName)

        httpClient.newCall(request).enqueue(ServerCallback())
    }

    private fun getConfigValues(): Config? {
        if (cachedConfig == null) {
            getSharedPreferences(CONFIG_NAME, MODE_PRIVATE).run {
                val url = getString("server_url", null) ?: return null
                val secret = getString("secret", null) ?: return null
                val id = getString("id", null) ?: return null
                val showName = getString("show_name", null) ?: return null
                cachedConfig = Config(url, secret, id, showName)
            }
        }
        return cachedConfig
    }

    private fun createRequestBody(
        appName: String, secret: String, id: String, showName: String
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
        logs(LogLevel.WARN, "获取应用名失败: ${e.message ?: "未知错误"}")
        packageName
    }

    private fun startKeepAliveService() {
        if (::keepAliveIntent.isInitialized) {
            try {
                startForegroundService(keepAliveIntent)
            } catch (e: Exception) {
                logs(LogLevel.INFO, "无法启动保活服务: ${e.message}")
            }
        }
    }

    private inner class ServerCallback : Callback {
        override fun onFailure(call: Call, e: IOException) {
            logs(LogLevel.ERROR, "发送失败: ${e.message}")
            logs(LogLevel.VERBOSE, "请求信息: ${call.request().method} ${call.request().url}")
        }

        override fun onResponse(call: Call, response: Response) {
            logs(LogLevel.VERBOSE, "收到响应: ${response.code} ${response.message}")

            response.use {
                val bodyStr = try {
                    response.body?.string().orEmpty()
                } catch (e: Exception) {
                    logs(LogLevel.ERROR, "读取响应体失败: ${e.message}")
                    ""
                }

                when {
                    response.isSuccessful && response.code == 200 -> {
                    }

                    response.isSuccessful && response.code != 200 -> {
                        logs(LogLevel.WARN, "非预期响应: ${response.code}，内容: $bodyStr")
                    }

                    else -> {
                        logs(LogLevel.ERROR, "服务器错误: ${response.code} - ${response.message}")
                        logs(LogLevel.ERROR, "响应内容: $bodyStr")
                    }
                }
            }
        }
    }

    private fun logs(level: LogLevel, msg: String) = LogRepository.addLog(level, msg)
}
