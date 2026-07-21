package com.mist.connect

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mist.connect.ui.theme.MistConnectTheme
import com.mist.connect.ui.theme.MistOk
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MistConnectTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

private val SectionShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(12.dp)

private fun startMcp(context: Context) {
    val intent = Intent(context, McpService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}

private fun stopMcp(context: Context) {
    context.stopService(Intent(context, McpService::class.java))
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
    val scrollState = rememberScrollState()

    var serviceRunning by remember { mutableStateOf(McpService.instance != null) }
    var autoStart by remember { mutableStateOf(prefs.getBoolean("auto_start_mcp", true)) }
    var city by remember { mutableStateOf(prefs.getString("city", "") ?: "") }

    var visionApiUrl by remember { mutableStateOf(prefs.getString("vision_api_url", "") ?: "") }
    var visionApiKey by remember { mutableStateOf(prefs.getString("vision_api_key", "") ?: "") }
    var visionModel by remember { mutableStateOf(prefs.getString("vision_model", "") ?: "") }

    var uiControlEnabled by remember { mutableStateOf(prefs.getBoolean("enable_ui_control", false)) }
    var mcpToken by remember { mutableStateOf(prefs.getString("mcp_token", "") ?: "") }

    var showShizuku by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf("") }
    var permTick by remember { mutableIntStateOf(0) }

    // 冷启动：开关默认开 → 自动起 MCP
    LaunchedEffect(Unit) {
        if (autoStart && McpService.instance == null) {
            startMcp(context)
            serviceRunning = true
            toastMsg = "MCP 已自动启动"
        } else {
            serviceRunning = McpService.instance != null
        }
    }

    val lifecycleOwner = (context as? ComponentActivity)
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceRunning = McpService.instance != null
                permTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showShizuku) {
        ShizukuPermissionScreen(onBack = {
            showShizuku = false
            permTick++
        })
        return
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            try {
                val file = java.io.File(context.filesDir, "lc_memory.json")
                if (file.exists()) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(file.readBytes())
                    }
                    toastMsg = "导出成功"
                } else {
                    toastMsg = "记忆库为空，无需导出"
                }
            } catch (e: Exception) {
                toastMsg = "导出失败：${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: ""
                JSONObject(content)
                val file = java.io.File(context.filesDir, "lc_memory.json")
                file.writeText(content)
                toastMsg = "导入成功"
            } catch (_: Exception) {
                toastMsg = "导入失败：文件格式不对"
            }
        }
    }

    val ipAddress = remember { getLocalIpAddress() }
    val mcpLan = "http://$ipAddress:5000/mcp"
    val mcpLocal = "http://127.0.0.1:5000/mcp"
    val versionName = BuildConfig.VERSION_NAME
    val versionCode = BuildConfig.VERSION_CODE

    fun copyText(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toastMsg = "已复制：$text"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderBlock(
            serviceRunning = serviceRunning,
            versionName = versionName,
            toastMsg = toastMsg
        )

        SectionCard(title = "MCP 服务", subtitle = "启动后把地址填进 RikkaHub / 其它 MCP 客户端") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("打开 App 时自动启动", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "默认开启；关掉后只手动启停",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoStart,
                    onCheckedChange = {
                        autoStart = it
                        prefs.edit().putBoolean("auto_start_mcp", it).apply()
                        toastMsg = if (it) "已开自动启动" else "已关自动启动"
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        startMcp(context)
                        serviceRunning = true
                        toastMsg = "服务已启动"
                    },
                    modifier = Modifier.weight(1f),
                    shape = ChipShape
                ) { Text("启动") }
                OutlinedButton(
                    onClick = {
                        stopMcp(context)
                        serviceRunning = false
                        toastMsg = "服务已停止"
                    },
                    modifier = Modifier.weight(1f),
                    shape = ChipShape
                ) { Text("停止") }
            }

            Spacer(Modifier.height(10.dp))
            AddressRow(label = "局域网", address = mcpLan, onCopy = { copyText("mcp-lan", mcpLan) })
            Spacer(Modifier.height(8.dp))
            AddressRow(label = "本机", address = mcpLocal, onCopy = { copyText("mcp-local", mcpLocal) })
        }

        SectionCard(
            title = "权限",
            subtitle = "绿点=已开 · 灰点=未开/无法检测 · 点 chip 跳设置"
        ) {
            // permTick 强制回前台重算
            @Suppress("UNUSED_EXPRESSION")
            permTick
            PermissionGrid(context = context)
        }

        SectionCard(title = "视觉 API", subtitle = "截屏分析，OpenAI 兼容接口") {
            OutlinedTextField(
                value = visionApiUrl,
                onValueChange = { visionApiUrl = it },
                label = { Text("API 地址") },
                placeholder = { Text("https://api.xxx.com/v1/chat/completions") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = ChipShape
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = visionApiKey,
                onValueChange = { visionApiKey = it },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = ChipShape
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = visionModel,
                onValueChange = { visionModel = it },
                label = { Text("模型名") },
                placeholder = { Text("gemini-2.5-flash") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = ChipShape
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    prefs.edit()
                        .putString("vision_api_url", visionApiUrl.trim())
                        .putString("vision_api_key", visionApiKey.trim())
                        .putString("vision_model", visionModel.trim())
                        .apply()
                    toastMsg = "视觉 API 已保存"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = ChipShape
            ) { Text("保存") }
        }

        SectionCard(
            title = "UI 控制",
            subtitle = "允许 MCP 调用 tap / swipe / input 等高危操作"
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("启用 UI 控制", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = uiControlEnabled,
                    onCheckedChange = {
                        uiControlEnabled = it
                        prefs.edit().putBoolean("enable_ui_control", it).apply()
                        toastMsg = if (it) "UI 控制已开" else "UI 控制已关"
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mcpToken,
                onValueChange = { mcpToken = it },
                label = { Text("MCP Token（留空不校验）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = ChipShape
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    prefs.edit().putString("mcp_token", mcpToken.trim()).apply()
                    toastMsg = "Token 已保存"
                },
                modifier = Modifier.fillMaxWidth(),
                shape = ChipShape
            ) { Text("保存 Token") }
            Text(
                "填写后请求需带 Authorization: Bearer <token>",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        SectionCard(title = "Shizuku", subtitle = "adb shell 级权限，无需 Root") {
            Text(
                "杀进程 · 静默装包 · 授权 · 任意 shell",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { showShizuku = true },
                    modifier = Modifier.weight(1f),
                    shape = ChipShape
                ) { Text("权限页") }
                OutlinedButton(
                    onClick = {
                        val cmd =
                            "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh"
                        copyText("shizuku-rpk", cmd)
                    },
                    modifier = Modifier.weight(1f),
                    shape = ChipShape
                ) { Text("复制 rpk 命令") }
            }
        }

        SectionCard(title = "天气城市", subtitle = "wttr.in，拼音或英文名") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("城市") },
                    placeholder = { Text("Beijing") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = ChipShape
                )
                Button(
                    onClick = {
                        prefs.edit().putString("city", city.trim()).apply()
                        toastMsg = "城市已保存"
                    },
                    shape = ChipShape
                ) { Text("保存") }
            }
        }

        SectionCard(title = "记忆库", subtitle = "导出备份，换机导入") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("lc_memory.json") },
                    modifier = Modifier.weight(1f),
                    shape = ChipShape
                ) { Text("导出") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.weight(1f),
                    shape = ChipShape
                ) { Text("导入") }
            }
        }

        SectionCard(title = "关于", subtitle = null) {
            Text(
                "MistConnect v$versionName ($versionCode)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "作者 Lntano_starry · 开源 Android MCP 桥\n" +
                    "Kotlin · Compose · Shizuku · MCP HTTP :5000",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/rejian2022/MistConnect")
                            )
                        )
                    } catch (_: Exception) {
                        toastMsg = "打不开浏览器"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = ChipShape
            ) { Text("GitHub 仓库（发布后可用）") }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun HeaderBlock(serviceRunning: Boolean, versionName: String, toastMsg: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "MistConnect",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "v$versionName",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Text(
            text = "手机状态 · MCP 桥 · AI 能摸到的那层雾",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (serviceRunning) MistOk
                        else MaterialTheme.colorScheme.outline
                    )
            )
            Text(
                text = if (serviceRunning) "MCP 运行中" else "MCP 已停止",
                style = MaterialTheme.typography.labelLarge,
                color = if (serviceRunning) MistOk
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (toastMsg.isNotEmpty()) {
            Text(
                text = toastMsg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = SectionShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
            } else {
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

@Composable
private fun AddressRow(label: String, address: String, onCopy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ChipShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            TextButton(onClick = onCopy) { Text("复制") }
        }
    }
}

private enum class PermState { GRANTED, DENIED, UNKNOWN }

private data class PermItem(
    val label: String,
    val state: PermState,
    val action: () -> Unit
)

private fun hasUsageAccess(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

private fun isAccessibilityOn(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val my = ComponentName(context, LCAccessibilityService::class.java).flattenToString()
    return enabled.any {
        val id = it.resolveInfo?.serviceInfo?.let { si ->
            ComponentName(si.packageName, si.name).flattenToString()
        } ?: it.id
        id.contains(context.packageName) || id == my || id.endsWith("/.LCAccessibilityService")
    }
}

private fun isNotificationListenerOn(context: Context): Boolean {
    return try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Exception) {
        false
    }
}

private fun isDeviceAdminOn(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    return dpm.isAdminActive(ComponentName(context, LockScreenReceiver::class.java))
}

private fun isBatteryWhitelist(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun hasActivityRecognition(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    } else true
}

private fun hasPostNotifications(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionGrid(context: Context) {
    val items = listOf(
        PermItem(
            label = "使用情况",
            state = if (hasUsageAccess(context)) PermState.GRANTED else PermState.DENIED
        ) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        PermItem(
            label = "电池白名单",
            state = if (isBatteryWhitelist(context)) PermState.GRANTED else PermState.DENIED
        ) {
            if (!isBatteryWhitelist(context)) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            } else {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        },
        PermItem(
            label = "通知",
            state = if (hasPostNotifications(context)) PermState.GRANTED else PermState.DENIED
        ) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        },
        PermItem(
            label = "步数",
            state = if (hasActivityRecognition(context)) PermState.GRANTED else PermState.DENIED
        ) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        },
        PermItem(
            label = "无障碍",
            state = if (isAccessibilityOn(context)) PermState.GRANTED else PermState.DENIED
        ) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        PermItem(
            label = "设备管理员",
            state = if (isDeviceAdminOn(context)) PermState.GRANTED else PermState.DENIED
        ) {
            val componentName = ComponentName(context, LockScreenReceiver::class.java)
            context.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "允许 MistConnect 锁定屏幕"
                    )
                }
            )
        },
        PermItem(
            label = "通知使用权",
            state = if (isNotificationListenerOn(context)) PermState.GRANTED else PermState.DENIED
        ) {
            context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        },
        PermItem(
            label = "自启动",
            state = PermState.UNKNOWN
        ) {
            try {
                context.startActivity(
                    Intent().setClassName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                    )
                )
            } catch (_: Exception) {
                try {
                    context.startActivity(
                        Intent().setClassName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    )
                } catch (_: Exception) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }
            }
        }
    )

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            OutlinedButton(
                onClick = item.action,
                shape = ChipShape,
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier.height(40.dp),
                border = BorderStroke(
                    1.dp,
                    when (item.state) {
                        PermState.GRANTED -> MistOk.copy(alpha = 0.7f)
                        PermState.DENIED -> MaterialTheme.colorScheme.outline
                        PermState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
                    }
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (item.state) {
                                    PermState.GRANTED -> MistOk
                                    PermState.DENIED -> MaterialTheme.colorScheme.outline
                                    PermState.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                    Text(item.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
            }
        }
    } catch (_: Exception) {
    }
    return "127.0.0.1"
}
