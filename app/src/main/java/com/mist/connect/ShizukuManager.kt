package com.mist.connect

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku manager: permission + UserService bind + shell exec.
 * API 13.x has no Shizuku.init(); provider binds binder automatically.
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        private val binderReady = AtomicBoolean(false)
        @Volatile private var userService: IUserService? = null
        private val bindLock = Any()

        init {
            try {
                Shizuku.addBinderReceivedListenerSticky {
                    binderReady.set(true)
                    Log.i(TAG, "binder received")
                }
                Shizuku.addBinderDeadListener {
                    binderReady.set(false)
                    userService = null
                    Log.w(TAG, "binder dead")
                }
                if (Shizuku.pingBinder()) binderReady.set(true)
            } catch (e: Throwable) {
                Log.e(TAG, "listener setup failed", e)
            }
        }
    }

    fun init() {
        if (Shizuku.pingBinder()) binderReady.set(true)
        ensureBound()
    }

    fun isRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return try {
            isRunning() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun requestPermission() {
        try {
            if (Shizuku.isPreV11()) return
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(0)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestPermission", e)
        }
    }

    private fun ensureBound(): Boolean {
        if (!isAvailable()) return false
        if (userService != null) return true
        synchronized(bindLock) {
            if (userService != null) return true
            return try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, UserService::class.java.name)
                )
                    .daemon(false)
                    .processNameSuffix("lc_shell")
                    .debuggable(false)
                    .version(BuildConfig.VERSION_CODE)
                val latch = CountDownLatch(1)
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (binder != null && binder.pingBinder()) {
                            userService = IUserService.Stub.asInterface(binder)
                        }
                        latch.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        userService = null
                    }
                }
                Shizuku.bindUserService(args, conn)
                latch.await(8, TimeUnit.SECONDS)
                userService != null
            } catch (e: Exception) {
                Log.e(TAG, "bindUserService failed", e)
                false
            }
        }
    }

    fun executeShell(command: String): String {
        if (!isRunning()) return "Shizuku 未运行，请先启动 Shizuku App 或 rpk"
        if (!isAvailable()) return "Shizuku 未授权，请在 App 内授权"
        if (!ensureBound()) return "UserService 绑定失败"
        val svc = userService ?: return "UserService 为空"
        return try {
            svc.exec(command) ?: "(null)"
        } catch (e: Exception) {
            Log.e(TAG, "exec failed: $command", e)
            userService = null
            if (ensureBound()) {
                try {
                    return userService?.exec(command) ?: "retry null"
                } catch (e2: Exception) {
                    return "执行异常: ${e2.message}"
                }
            }
            "执行异常: ${e.message}"
        }
    }
}
