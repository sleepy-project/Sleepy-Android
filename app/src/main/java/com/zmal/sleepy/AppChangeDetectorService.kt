package com.zmal.sleepy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Display
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

    }

    data class Config(
        val url: String, val secret: String, val id: String, val showName: String
    )


    data class Status(
        var isUsing: Boolean,
        var batteryPct: Int?,
        var isCharging: Boolean,
        var appName: String,
    )

    val status = Status(false, null, false, "")

    private val handler = Handler(Looper.getMainLooper())
    private var reportRunnable: Runnable? = null
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
        cachedConfig = getConfigValues() ?: run {
            logs(LogLevel.ERROR, "配置参数不完整")
            return
        }
        val ignoredClassNames = setOf(
            "android.widget.FrameLayout",
            "androidx.fragment.app.FragmentContainerView",
            "android.widget.ScrollView",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "androidx.constraintlayout.widget.ConstraintLayout",
            "android.view.View",
            "android.view.ViewGroup",
            "android.widget.ViewFlipper",
            "android.widget.ViewAnimator",
            "android.widget.Toolbar",
            "androidx.appcompat.widget.Toolbar",
            "com.android.internal.policy.DecorView"
        )


        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.className in ignoredClassNames) {
            logs(
                LogLevel.VERBOSE,
                "忽略无关事件: eventType=${event.eventType}, className=${event.className}"
            )
            return
        }
        val raw = event.packageName.toString()
        val nowScreenOff = !isScreenOff(this)
        if (raw == lastPackageName && status.isUsing == nowScreenOff) {
            logs(LogLevel.DEBUG, "重复状态: $raw,${nowScreenOff}")
            return
        }
        logs(
            LogLevel.DEBUG,
            "处理包名: $raw，类名：${event.className}，亮屏：${nowScreenOff}"
        )

        val pkgName = raw.let {
            if (it == "com.android.systemui") {
                logs(LogLevel.DEBUG, "fallback ：$lastPackageName")
                lastPackageName
            } else {
                it
            }
        }

        if (isInputMethod(pkgName.toString())) {
            logs(LogLevel.VERBOSE, "忽略输入法包名: $pkgName")
            return
        }

        lastPackageName = pkgName


        pkgName?.let { handlePackageChange(it) }

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

        reportRunnable?.let {
            logs(LogLevel.DEBUG, "取消: $lastPackageName;替换: $packageName")
            handler.removeCallbacks(it)
        }

        reportRunnable = Runnable {
            status.appName = resolveAppName(packageName)
            updateDeviceState()
            sendToServer(status)
            reportRunnable = null
        }
        handler.postDelayed(reportRunnable!!, REPORT_DELAY_MS)
    }

    fun isScreenOff(context: Context): Boolean {
        val powerManager = context.getSystemService(POWER_SERVICE) as? PowerManager
        val displayManager = context.getSystemService(DISPLAY_SERVICE) as? DisplayManager
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)

        val isDisplayOff = display?.state == Display.STATE_OFF || display == null
        val isNonInteractive = powerManager?.isInteractive == false

        if (display == null) {
            logs(LogLevel.WARN, "无法获取默认显示器")
        }

        return isNonInteractive || isDisplayOff
    }


    private fun updateDeviceState() {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val chargingStatus = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        status.isUsing = !isScreenOff(this)
        status.isCharging =
            chargingStatus == BatteryManager.BATTERY_STATUS_CHARGING || chargingStatus == BatteryManager.BATTERY_STATUS_FULL
        status.batteryPct = if (battery in 0..100) battery else -1

        logs(
            LogLevel.VERBOSE,
            "设备状态 -${status.appName}${status.batteryPct}[${status.isUsing}], isCharging=${status.isCharging}"
        )
    }

    private fun resolveAppName(packageName: String): String {
        val appName = getAppName(packageName)
        return appName
    }

    private fun isInputMethod(packageName: String): Boolean {
        return try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SERVICES)
            info.services?.any { it.permission == "android.permission.BIND_INPUT_METHOD" } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendToServer(status: Status) {
        val config = cachedConfig ?: return

        val request = Request.Builder().url(config.url).post(
            createRequestBody(
                status.appName, config.secret, config.id, config.showName, status.isUsing
            )
        ).addHeader("User-Agent", USER_AGENT).build()
        logs(LogLevel.INFO, "${status.appName}[${status.isUsing}]")

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
        appName: String, secret: String, id: String, showName: String, using: Boolean
    ): RequestBody {
        val json = JSONObject().apply {
            put("id", id)
            put("secret", secret)
            put("show_name", showName)
            put("using", using)
            put(
                "app_name",
                "$appName[${status.batteryPct ?: "-"}%]${if (status.isCharging) "\u26A1\uFE0F" else "\uD83D\uDD0B"}"
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
                    response.body.string()
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
