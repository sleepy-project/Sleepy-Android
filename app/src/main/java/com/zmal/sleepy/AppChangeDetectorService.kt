// AppChangeDetectorService.kt
package com.zmal.sleepy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
//import android.util.Log
import android.view.accessibility.AccessibilityEvent
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
//        private const val TAG = "AppTracker"
        private const val REPORT_DELAY_MS = 1000L // 1秒延迟
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reportRunnable: Runnable? = null
    private var lastSentTime = 0L
    private var pendingAppName: String? = null
    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        logInfo("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            if (!packageName.isNullOrEmpty()) {
                if (packageName.contains("input", true)) {
                    logInfo("检测到输入法不上报")
                    return
                }

                if (getAppName(packageName).contains("输入法", true)) {
                    logInfo("检测到输入法不上报")
                    return
                }

                pendingAppName = getAppName(packageName)

                // 取消之前任务
                reportRunnable?.let { handler.removeCallbacks(it) }

                reportRunnable = Runnable {
                    val currentTime = System.currentTimeMillis()

                        lastSentTime = currentTime
                        val time = sdf.format(Date(currentTime))
                        logInfo("[$time] 检测到应用切换: $pendingAppName")
                        sendToServer(pendingAppName!!)

                    pendingAppName = null
                }
                handler.postDelayed(reportRunnable!!, REPORT_DELAY_MS)
            }
        }
    }

    private fun sendToServer(appName: String) {
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val url = prefs.getString("server_url", null) ?: run {
            logInfo("未配置服务器地址")
            return
        }

        val secret = prefs.getString("secret", "") ?: ""
        val id = prefs.getString("id", "") ?: ""
        val showName = prefs.getString("show_name", "") ?: ""

        if (secret.isEmpty() || id.isEmpty() || showName.isEmpty()) {
            logInfo("无效配置参数")
            return
        }

        val jsonObject = JSONObject().apply {
            put("id", id)
            put("secret", secret)
            put("show_name", showName)
            put("using", true)
            put("app_name", appName)
        }

        val requestBody = jsonObject.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("User-Agent", "Sleep-Android")
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logInfo("发送失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        if(resp.code!=200){
                            logInfo("发送成功但非预期: ${resp.code}")
                        }
                    } else {
                        logInfo("服务器错误: ${resp.code} - ${resp.message}")
                    }
                }
            }
        })
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager

            // Android 11+ 包可见性检查
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!pm.isPackageInstalled(packageName)) return packageName
            }

            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: SecurityException) {
            logInfo("Permission denied for $packageName")
            packageName // 权限不足
        } catch (_: Exception) {
            logInfo("Unexpected error for $packageName")
            packageName // 其他异常
        }
    }

    // 扩展函数：检查包是否安装（Android 11+ 兼容）
    private fun PackageManager.isPackageInstalled(packageName: String): Boolean {
        return try {
            getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun logInfo(message: String) {
//        Log.d(TAG, message)
        LogRepository.addLog(message)
    }

    override fun onInterrupt() {
        logInfo("无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除所有待处理的任务
        reportRunnable?.let { handler.removeCallbacks(it) }
        logInfo("无障碍服务已销毁")
    }
}