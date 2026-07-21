package com.mist.connect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        // 与主页「打开 App 时自动启动」一致；默认 true
        if (!prefs.getBoolean("auto_start_mcp", true)) return

        val serviceIntent = Intent(context, McpService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
