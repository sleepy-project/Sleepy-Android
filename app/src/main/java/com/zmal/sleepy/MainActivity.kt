// MainActivity.kt
package com.zmal.sleepy

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zmal.sleepy.ui.theme.SleepyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestBatteryOptimization()
        startKeepAliveService()
        setContent {
            SleepyTheme {
                MainScreen()
            }
        }
    }

    private fun checkAndRequestBatteryOptimization() {

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                requestIgnoreBatteryOptimization(this)
            }

    }

    private fun startKeepAliveService() {
        val intent = Intent(this, KeepAliveService::class.java)
            startForegroundService(intent)
            startService(intent)
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val sharedPreferences = remember { context.getSharedPreferences("config", Context.MODE_PRIVATE) }
    val configLiveData = remember { MutableLiveData<Map<String, String>>() }
    val logViewModel: LogViewModel = viewModel()
    val logs by logViewModel.logs.collectAsState(emptyList())

    val accessibilityEnabled = remember { mutableStateOf(false) }
    val batteryOptimizationIgnored = remember { mutableStateOf(false) }

    // 加载配置
    LaunchedEffect(Unit) {
        loadConfig(sharedPreferences, configLiveData)
        accessibilityEnabled.value = isAccessibilityServiceEnabled(context, AppChangeDetectorService::class.java)
        batteryOptimizationIgnored.value = isIgnoringBatteryOptimizations(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled.value = isAccessibilityServiceEnabled(context, AppChangeDetectorService::class.java)
                batteryOptimizationIgnored.value = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val config by configLiveData.observeAsState(emptyMap())

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "sleepy Android client",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            StatusIndicatorSection(
                accessibilityEnabled = accessibilityEnabled.value,
                batteryOptimizationIgnored = batteryOptimizationIgnored.value,
                context = context
            )

            Spacer(modifier = Modifier.height(24.dp))

            ConfigInputSection(
                initialConfig = config,
                onSave = { url, secret, id, name ->
                    saveConfig(sharedPreferences, url, secret, id, name)
                    loadConfig(sharedPreferences, configLiveData)
                    LogRepository.addLog("配置已保存")
                    Toast.makeText(context, "配置已保存", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            LogDisplaySection(logs = logs)
        }
    }
}

@Composable
fun StatusIndicatorSection(
    accessibilityEnabled: Boolean,
    batteryOptimizationIgnored: Boolean,
    context: Context
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "无障碍服务: ${if (accessibilityEnabled) "已启用" else "未启用"}",
                color = if (accessibilityEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.height(36.dp)
            ) {
                Text("开启")
            }
        }

        // 电池优化状态
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "电池优化: ${if (batteryOptimizationIgnored) "已忽略" else "未忽略"}",
                color = if (batteryOptimizationIgnored) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = { requestIgnoreBatteryOptimization(context) },
                enabled =  !batteryOptimizationIgnored,
                modifier = Modifier.height(36.dp)
            ) {
                Text("忽略")
            }
        }
    }
}

@Composable
fun ConfigInputSection(
    initialConfig: Map<String, String>,
    onSave: (String, String, String, String) -> Unit
) {
    var serverUrl by remember { mutableStateOf(initialConfig["server_url"] ?: "") }
    var secret by remember { mutableStateOf(initialConfig["secret"] ?: "") }
    var id by remember { mutableStateOf(initialConfig["id"] ?: "") }
    var showName by remember { mutableStateOf(initialConfig["show_name"] ?: "") }

    // 当初始配置变化时更新状态
    LaunchedEffect(initialConfig) {
        serverUrl = initialConfig["server_url"] ?: ""
        secret = initialConfig["secret"] ?: ""
        id = initialConfig["id"] ?: ""
        showName = initialConfig["show_name"] ?: ""
    }

    Column {
        Text("服务配置", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("服务器地址") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("服务器密钥") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = id,
            onValueChange = { id = it },
            label = { Text("设备ID") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = showName,
            onValueChange = { showName = it },
            label = { Text("显示名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { onSave(serverUrl, secret, id, showName) },
            modifier = Modifier.fillMaxWidth(),
            enabled = serverUrl.isNotBlank() && secret.isNotBlank() &&
                    id.isNotBlank() && showName.isNotBlank()
        ) {
            Text("保存配置")
        }
    }
}

@Composable
fun LogDisplaySection(logs: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("日志输出", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
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

// ======== 工具函数 ========
private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

private fun loadConfig(
    sharedPreferences: SharedPreferences,
    liveData: MutableLiveData<Map<String, String>>
) {
    val configMap = mapOf(
        "server_url" to (sharedPreferences.getString("server_url", "") ?: ""),
        "secret" to (sharedPreferences.getString("secret", "") ?: ""),
        "id" to (sharedPreferences.getString("id", "") ?: ""),
        "show_name" to (sharedPreferences.getString("show_name", "") ?: "")
    )
    liveData.postValue(configMap)
}

private fun saveConfig(
    sharedPreferences: SharedPreferences,
    url: String,
    secret: String,
    id: String,
    showName: String
) {
    sharedPreferences.edit().apply {
        putString("server_url", url)
        putString("secret", secret)
        putString("id", id)
        putString("show_name", showName)
        apply()
    }
}

fun requestIgnoreBatteryOptimization(context: Context) {

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)

}

private fun isAccessibilityServiceEnabled(
    context: Context,
    service: Class<out AccessibilityService>
): Boolean {
    val serviceName = ComponentName(context, service).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
}