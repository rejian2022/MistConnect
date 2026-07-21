package com.mist.connect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mist.connect.ui.theme.MistOk

private val SectionShape = RoundedCornerShape(16.dp)
private val ChipShape = RoundedCornerShape(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuPermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val shizukuManager = remember { ShizukuManager(context).apply { init() } }
    var statusText by remember { mutableStateOf("检查中…") }
    var isAuthorized by remember { mutableStateOf(false) }
    var isRunning by remember { mutableStateOf(false) }
    var testOutput by remember { mutableStateOf("") }

    fun refreshStatus() {
        isRunning = shizukuManager.isRunning()
        isAuthorized = shizukuManager.isAvailable()
        statusText = when {
            !isRunning -> "Shizuku 未运行"
            isAuthorized -> "已授权 · shell 可用"
            else -> "在跑 · 尚未授权"
        }
    }

    val shizukuLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshStatus() }

    LaunchedEffect(Unit) {
        shizukuManager.requestPermission()
        refreshStatus()
    }

    fun openShizukuApp() {
        try {
            val intent = Intent().apply {
                setClassName(
                    "moe.shizuku.privileged.api",
                    "moe.shizuku.manager.MainActivity"
                )
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            shizukuLauncher.launch(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=moe.shizuku.privileged.api")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://shizuku.rikka.app/")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    fun copyRpkCommand() {
        val cmd =
            "sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh"
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("shizuku-rpk", cmd))
        testOutput = "已复制 rpk 启动命令，去 Termux / ADB 粘贴运行"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Shizuku",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 状态卡
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionShape,
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isAuthorized -> MaterialTheme.colorScheme.primaryContainer
                        isRunning -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isAuthorized -> MistOk
                                    isRunning -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                    )
                    Column {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                isAuthorized -> MaterialTheme.colorScheme.onPrimaryContainer
                                isRunning -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onErrorContainer
                            }
                        )
                        Text(
                            text = if (isAuthorized) "MCP 的 shizuku_* 工具可调用"
                            else "装好 Shizuku 后点下面授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 说明
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "adb shell 级权限 · 无需 Root",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "杀进程 · 冻结 · 卸载 · 静默装包 · 授权 · 任意 shell",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // 操作
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = SectionShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("操作", style = MaterialTheme.typography.titleLarge)
                    if (!isAuthorized) {
                        Button(
                            onClick = {
                                shizukuManager.requestPermission()
                                openShizukuApp()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ChipShape
                        ) { Text("打开 Shizuku 并申请授权") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { refreshStatus() },
                            modifier = Modifier.weight(1f),
                            shape = ChipShape
                        ) { Text("刷新") }
                        OutlinedButton(
                            onClick = { copyRpkCommand() },
                            modifier = Modifier.weight(1f),
                            shape = ChipShape
                        ) { Text("复制 rpk") }
                    }
                }
            }

            // 测试
            if (isAuthorized) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = SectionShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("授权后测试", style = MaterialTheme.typography.titleLarge)
                        OutlinedButton(
                            onClick = {
                                testOutput = shizukuManager.executeShell("id")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ChipShape
                        ) { Text("id") }
                        OutlinedButton(
                            onClick = {
                                testOutput =
                                    shizukuManager.executeShell("pm list packages | wc -l")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ChipShape
                        ) { Text("已安装包数量") }
                        OutlinedButton(
                            onClick = {
                                testOutput =
                                    shizukuManager.executeShell("ps -A | head -10")
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = ChipShape
                        ) { Text("前 10 个进程") }

                        if (testOutput.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = ChipShape,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Text(
                                    text = testOutput,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            } else if (testOutput.isNotEmpty()) {
                Text(
                    text = testOutput,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                "首次需装 Shizuku，或 ADB：\n" +
                    "sh /sdcard/Android/data/moe.shizuku.privileged.api/files/start.sh",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}
