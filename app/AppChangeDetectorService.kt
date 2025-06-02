package com.example.apptracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppChangeDetectorService : AccessibilityService() {

    // 配置HTTP客户端
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val serverUrl = getServerUrl()
    private fun getServerUrl(): String? {
        val sharedPreferences = getSharedPreferences("config", Context.MODE_PRIVATE)
        return sharedPreferences.getString("server_url", null)
    }

    // 用于防止频繁发送请求
    private var lastSentTime = 0L
    private val minInterval = 2000 // 2秒间隔

    override fun onServiceConnected() {
        // 配置无障碍服务参数
        val info = AccessibilityServiceInfo().apply {
            // 设置我们关心的事件类型 - 应用窗口变化
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            // 设置反馈类型（这里不需要用户反馈）
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // 设置标志位
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

            // 设置通知超时
            notificationTimeout = 100 // 毫秒

            // 声明我们可以获取窗口内容
            canRetrieveWindowContent = true
        }

        // 应用服务配置
        this.serviceInfo = info

        Log.d("AppTracker", "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 只处理窗口状态变化事件
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            val className = event.className?.toString()

            if (packageName != null && className != null) {
                Log.d("AppTracker", "检测到应用切换: $packageName/$className")

                // 添加防抖机制，避免频繁发送请求
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastSentTime > minInterval) {
                    lastSentTime = currentTime
                    sendToServer(packageName, className)
                }
            }
        }
    }

    private fun sendToServer(packageName: String, className: String) {
        // 构造JSON数据
        val json = """
            {
                "package": "$packageName",
                "class": "$className",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent()

        // 创建请求体
        val requestBody = json.toRequestBody("application/json".toMediaType())

        // 创建请求对象
        val request = Request.Builder()
            .url(serverUrl)
            .post(requestBody)
            .addHeader("User-Agent", "AppTracker/1.0")
            .build()

        // 异步发送请求
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("AppTracker", "发送失败: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d("AppTracker", "发送成功: ${response.code}")
                } else {
                    Log.e("AppTracker", "服务器错误: ${response.code}")
                }
                response.close()
            }
        })
    }

    override fun onInterrupt() {
        // 当服务被中断时调用（如用户关闭无障碍服务）
        Log.w("AppTracker", "无障碍服务被中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("AppTracker", "无障碍服务已销毁")
    }
}