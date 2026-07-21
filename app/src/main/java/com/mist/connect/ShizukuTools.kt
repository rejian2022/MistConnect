package com.mist.connect

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

class ShizukuTools(private val context: Context) {

    private val shizukuManager = ShizukuManager(context).apply { init() }

    fun handleShizukuExec(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) {
            return "Shizuku 未授权，请先在「Shizuku 权限」页面授权"
        }
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return "command 不能为空"
        return shizukuManager.executeShell(command)
    }

    fun handleShizukuKillApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name 不能为空"
        val cmd = "am kill $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuFreezeApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name 不能为空"
        val cmd = "pm disable-user --user 0 $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuUnfreezeApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name 不能为空"
        val cmd = "pm enable --user 0 $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuUninstallApp(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name 不能为空"
        val keepData = args.optBoolean("keep_data", false)
        val keepFlag = if (keepData) "-k" else ""
        val cmd = "pm uninstall $keepFlag --user 0 $pkg"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuGetRunningProcesses(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val cmd = "ps -A -o PID,USER,ARGS"
        val output = shizukuManager.executeShell(cmd)
        if (output.startsWith("执行") || output.startsWith("Shizuku")) return output
        // 简单解析
        val lines = output.lines().filter { it.isNotBlank() }.drop(1) // 去表头
        val arr = JSONArray()
        lines.forEach { line ->
            val parts = line.trim().split("\\s+".toRegex(), limit = 3)
            if (parts.size >= 3) {
                arr.put(JSONObject().apply {
                    put("pid", parts[0])
                    put("user", parts[1])
                    put("cmd", parts[2])
                })
            }
        }
        return arr.toString(2)
    }

    fun handleShizukuInstallApk(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val path = args.optString("apk_path", "").trim()
        if (path.isEmpty()) return "apk_path 不能为空"
        val allowDowngrade = args.optBoolean("allow_downgrade", false)
        val downgradeFlag = if (allowDowngrade) "-d" else ""
        val cmd = "pm install $downgradeFlag -r \"$path\""
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuGrantPermission(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        val perm = args.optString("permission", "").trim()
        if (pkg.isEmpty() || perm.isEmpty()) return "package_name 和 permission 都不能为空"
        val cmd = "pm grant $pkg $perm"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuRevokePermission(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        val perm = args.optString("permission", "").trim()
        if (pkg.isEmpty() || perm.isEmpty()) return "package_name 和 permission 都不能为空"
        val cmd = "pm revoke $pkg $perm"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuListPackages(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val enabled = args.optString("enabled", "all") // all/enabled/disabled
        val cmd = when (enabled) {
            "enabled" -> "pm list packages -e"
            "disabled" -> "pm list packages -d"
            else -> "pm list packages"
        }
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuAppInfo(args: JSONObject): String {
        if (!shizukuManager.isAvailable()) return "Shizuku 未授权"
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) return "package_name 不能为空"
        val cmd = "dumpsys package $pkg | head -80"
        return shizukuManager.executeShell(cmd)
    }

    fun handleShizukuStatus(): String {
        if (!shizukuManager.isRunning()) {
            return "Shizuku 未运行。请启动 Shizuku App，或 ADB：\nsh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/files/start.sh"
        }
        if (!shizukuManager.isAvailable()) {
            return "Shizuku 在跑但未授权。请在 App 中打开「Shizuku 权限」授权。"
        }
        val id = shizukuManager.executeShell("id")
        return "Shizuku 已就绪\nid: $id"
    }
}