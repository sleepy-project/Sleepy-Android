// MainActivity.kt
package com.zmal.sleepy

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zmal.sleepy.ui.theme.SleepyTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermissionIfNeeded()
        requestBatteryOptimizationIfNeeded()
        checkAccessibilityServiceEnabled()

        setContent {
            SleepyTheme {
                MainScreen()
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationIfNeeded() {
        val powerManager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }.also { startActivity(it) }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS).also {
                ActivityCompat.requestPermissions(this, it, 1001)
            }
        }
    }

    private fun checkAccessibilityServiceEnabled() {
        if (!isAccessibilityServiceEnabled(this, AppChangeDetectorService::class.java)) {
            Toast.makeText(this, "无障碍权限未开启", Toast.LENGTH_LONG).show()
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }.also { startActivity(it) }
        }
    }

}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val sharedPreferences =
         context.getSharedPreferences("config", Context.MODE_PRIVATE)
    val configLiveData = remember { MutableLiveData<Map<String, String>>() }
    val logViewModel: LogViewModel = viewModel()
    val logs by logViewModel.logs.collectAsState(emptyList())

    val accessibilityEnabled = remember { mutableStateOf(false) }
    val batteryOptimizationIgnored = remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // 初始化与生命周期监听
    LaunchedEffect(Unit) {
        loadConfig(sharedPreferences, configLiveData)
        accessibilityEnabled.value =
            isAccessibilityServiceEnabled(context, AppChangeDetectorService::class.java)
        batteryOptimizationIgnored.value = isIgnoringBatteryOptimizations(context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled.value =
                    isAccessibilityServiceEnabled(context, AppChangeDetectorService::class.java)
                batteryOptimizationIgnored.value = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val config by configLiveData.observeAsState(emptyMap())

    val versionName = "v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})"

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
            Text("sleepy Android client", style = MaterialTheme.typography.titleLarge)
            Text(
                versionName,
                color = Color(211, 193, 250),
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .clickable {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://github.com/kmizmal/Sleepy-Android/releases/latest".toUri()
                            )
                        )
                    })
            StatusIndicatorSection(
                accessibilityEnabled = accessibilityEnabled.value,
                batteryOptimizationIgnored = batteryOptimizationIgnored.value,
                context = context
            )
            Spacer(Modifier.height(24.dp))
            ConfigInputSection(config) { url, secret, id, name, logLevel ->
                saveConfig(sharedPreferences, url, secret, id, name, logLevel)
                loadConfig(sharedPreferences, configLiveData)
                AppChangeDetectorService.cachedConfig = null
                LogRepository.addLog(LogLevel.INFO, "配置已保存")
                Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.height(24.dp))
            LogDisplaySection(logs)
        }
    }
}


@Composable
fun StatusIndicatorSection(
    accessibilityEnabled: Boolean, batteryOptimizationIgnored: Boolean, context: Context
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!accessibilityEnabled || !batteryOptimizationIgnored) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!accessibilityEnabled) {
                    Text("无障碍服务", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }, modifier = Modifier.height(36.dp)
                    ) { Text("去开启") }
                }
                if (!batteryOptimizationIgnored) {
                    Spacer(Modifier.width(24.dp))
                    Text("电池优化", color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { requestIgnoreBatteryOptimization(context) },
                        modifier = Modifier.height(36.dp)
                    ) { Text("去忽略") }
                }
            }
        }
    }
}


@Composable
fun ConfigInputSection(
    initialConfig: Map<String, String>, onSave: (String, String, String, String, String) -> Unit
) {
    val scrollState = rememberScrollState()

    var serverUrl by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var showName by remember { mutableStateOf("") }
    var logLevel by remember { mutableStateOf("INFO") }
    var secretVisible by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    val logLevels = listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR")

    // 初始化或恢复配置
    LaunchedEffect(initialConfig) {
        serverUrl = initialConfig["server_url"].orEmpty()
        secret = initialConfig["secret"].orEmpty()
        id = initialConfig["id"].orEmpty()
        showName = initialConfig["show_name"].orEmpty()
        logLevel = initialConfig["LogLevel"] ?: "INFO"
    }

    BackHandler(enabled = isEditing) {
        // 恢复初始配置并退出编辑
        serverUrl = initialConfig["server_url"].orEmpty()
        secret = initialConfig["secret"].orEmpty()
        id = initialConfig["id"].orEmpty()
        showName = initialConfig["show_name"].orEmpty()
        logLevel = initialConfig["LogLevel"] ?: "INFO"
        isEditing = false
    }

    Column(modifier = Modifier.verticalScroll(scrollState)) {
        Text("服务配置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { if (isEditing) serverUrl = it },
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = isEditing
        )

        Spacer(Modifier.height(8.dp))

        if (isEditing) {
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("服务器密钥") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val icon = if (secretVisible) Icons.Filled.Edit else Icons.Filled.Lock
                    IconButton(onClick = { secretVisible = !secretVisible }) {
                        Icon(icon, contentDescription = "切换可见性")
                    }
                })
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = id,
                onValueChange = { id = it },
                label = { Text("设备 ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(Modifier.height(8.dp))

            Text("日志等级", style = MaterialTheme.typography.bodyMedium)
            logLevels.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { logLevel = option }
                        .padding(2.dp)) {
                    RadioButton(
                        selected = option == logLevel,
                        onClick = { logLevel = option },
                        modifier = Modifier.size(25.dp)
                    )
                    Text(option)
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = showName,
            onValueChange = { if (isEditing) showName = it },
            label = { Text("显示名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = isEditing
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (isEditing) onSave(serverUrl, secret, id, showName, logLevel)
                isEditing = !isEditing
            }, modifier = Modifier.fillMaxWidth(), enabled = !isEditing || listOf(
                serverUrl, secret, id, showName
            ).all { it.isNotBlank() }) {
            Text(if (isEditing) "保存配置" else "编辑配置")
        }
    }
}


@Composable
fun LogDisplaySection(logs: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("日志输出", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        SelectionContainer {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = log,
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// ========== 工具函数 ==========

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimization(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = "package:${context.packageName}".toUri()
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

fun isAccessibilityServiceEnabled(
    context: Context, service: Class<out AccessibilityService>
): Boolean {
    val serviceName = ComponentName(context, service).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
}

fun loadConfig(
    sharedPreferences: SharedPreferences, liveData: MutableLiveData<Map<String, String>>
) {
    val configMap = mapOf(
        "server_url" to (sharedPreferences.getString("server_url", "") ?: ""),
        "secret" to (sharedPreferences.getString("secret", "") ?: ""),
        "id" to (sharedPreferences.getString("id", "") ?: ""),
        "show_name" to (sharedPreferences.getString("show_name", "") ?: ""),
        "LogLevel" to (sharedPreferences.getString("LogLevel", "INFO") ?: "INFO")
    )
    liveData.postValue(configMap)
    LogRepository.setLogLevel(configMap["LogLevel"] ?: "INFO")
}

fun saveConfig(
    sp: SharedPreferences,
    url: String,
    secret: String,
    id: String,
    showName: String,
    logLevel: String
) {
    sp.edit {
        putString("server_url", url)
        putString("secret", secret)
        putString("id", id)
        putString("show_name", showName)
        putString("LogLevel", logLevel)
    }
}

