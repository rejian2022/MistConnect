package com.mist.connect

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.provider.AlarmClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class McpService : Service(), SensorEventListener {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val PORT = 5000
    private var wakeLock: PowerManager.WakeLock? = null

    private var stepCount: Int = 0
    private var initialSteps: Int = -1
    private var sensorManager: SensorManager? = null
    private var resetTimestamp: Long = 0L

    companion object {
        private const val CHANNEL_ID = "lc_service"
        private const val NOTIFICATION_ID = 1
        var instance: McpService? = null
    }

    // UI控制安全开关
    private fun isUiControlEnabled(): Boolean {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_ui_control", false)
    }

    private fun checkMcpToken(headers: Map<String, String>): Boolean {
        val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
        val savedToken = prefs.getString("mcp_token", "") ?: ""
        if (savedToken.isEmpty()) return true   // no token set = open
        val auth = headers["authorization"] ?: return false
        return auth.startsWith("Bearer ") && auth.substring(7).trim() == savedToken
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }



    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MistConnect::MCP").apply { acquire() }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val stepSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        stepSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }

        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        serverSocket?.close()
        wakeLock?.release()
        sensorManager?.unregisterListener(this)

        // 被杀后5秒自动重启
        val restartIntent = Intent(applicationContext, McpService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 0, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, pendingIntent)
    }


    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, McpService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pendingIntent)
        super.onTaskRemoved(rootIntent)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                val totalSteps = it.values[0].toInt()
                if (initialSteps == -1) initialSteps = totalSteps
                stepCount = totalSteps - initialSteps
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
// ==================== HTTP服务器 ====================

    private fun startServer() {
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(PORT)
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun handleClient(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = socket.getOutputStream()

            val requestLine = input.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            var line = input.readLine()
            while (!line.isNullOrEmpty()) {
                val parts = line.split(": ", limit = 2)
                if (parts.size == 2) headers[parts[0].lowercase()] = parts[1]
                line = input.readLine()
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                input.read(buf, 0, contentLength)
                String(buf)
            } else ""

            val response = handleMcpRequest(body, headers)

            val httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nAccess-Control-Allow-Methods: *\r\nContent-Length: ${response.toByteArray().size}\r\n\r\n$response"
            output.write(httpResponse.toByteArray())
            output.flush()
            socket.close()
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun unauthorized(id: Any?): String {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("error", JSONObject().apply {
                put("code", -32001)
                put("message", "Unauthorized: 需要 Authorization: Bearer <token>")
            })
            put("id", id ?: JSONObject.NULL)
        }.toString()
    }

    private fun handleMcpRequest(body: String, headers: Map<String, String> = emptyMap()): String {
        if (body.isEmpty()) {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("result", JSONObject().apply {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", JSONObject().apply {
                        put("tools", JSONObject().apply { put("listChanged", false) })
                    })
                    put("serverInfo", JSONObject().apply {
                        put("name", "MistConnect")
                        put("version", "3.0.0")
                    })
                })
                put("id", 1)
            }.toString()
        }

        return try {
            val json = JSONObject(body)
            val method = json.optString("method", "")
            val id = json.opt("id")

            // Token：配置了则 tools/list 与 tools/call 必带 Bearer
            if (method == "tools/list" || method == "tools/call") {
                if (!checkMcpToken(headers)) return unauthorized(id)
            }

            when (method) {
                "initialize" -> {
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2025-03-26")
                            put("capabilities", JSONObject().apply {
                                put("tools", JSONObject().apply { put("listChanged", false) })
                            })
                            put("serverInfo", JSONObject().apply {
                                put("name", "MistConnect")
                                put("version", "3.0.0")
                            })
                        })
                        put("id", id)
                    }.toString()
                }
                "notifications/initialized" -> ""
                "tools/list" -> handleToolsList(id)
                "tools/call" -> handleToolsCall(json, id)
                else -> {
                    JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("error", JSONObject().apply {
                            put("code", -32601)
                            put("message", "Method not found: $method")
                        })
                        put("id", id)
                    }.toString()
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("error", JSONObject().apply {
                    put("code", -32700)
                    put("message", "Parse error: ${e.message}")
                })
                put("id", JSONObject.NULL)
            }.toString()
        }
    }
    private fun handleToolsList(id: Any?): String {
        val tools = JSONArray().apply {
            put(JSONObject().apply {
                put("name", "get_battery")
                put("description", "获取电池状态")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_screen_time")
                put("description", "获取屏幕使用时间报告")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_app_timeline")
                put("description", "获取App使用时间线")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_weather")
                put("description", "获取天气信息")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_steps")
                put("description", "获取今日步数")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "send_notification")
                put("description", "推送通知")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("message", JSONObject().apply { put("type", "string"); put("description", "消息内容") })
                    })
                    put("required", JSONArray().apply { put("message") })
                })
            })
            put(JSONObject().apply {
                put("name", "save_memory")
                put("description", "保存一条记忆到本地记忆库")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().apply { put("type", "string"); put("description", "记忆的键名") })
                        put("value", JSONObject().apply { put("type", "string"); put("description", "记忆的内容") })
                    })
                    put("required", JSONArray().apply { put("key"); put("value") })
                })
            })
            put(JSONObject().apply {
                put("name", "read_memory")
                put("description", "读取本地记忆库，不传key返回全部")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().apply { put("type", "string"); put("description", "要查询的键名，不传则返回全部") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "reset_screen_time")
                put("description", "重置屏幕使用时间计数")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "set_alarm")
                put("description", "设置闹钟")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("hour", JSONObject().apply { put("type", "integer"); put("description", "小时（0-23）") })
                        put("minute", JSONObject().apply { put("type", "integer"); put("description", "分钟（0-59）") })
                        put("message", JSONObject().apply { put("type", "string"); put("description", "闹钟备注（可选）") })
                    })
                    put("required", JSONArray().apply { put("hour"); put("minute") })
                })
            })
            put(JSONObject().apply {
                put("name", "cancel_alarm")
                put("description", "取消闹钟")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("hour", JSONObject().apply { put("type", "integer"); put("description", "小时（0-23）") })
                        put("minute", JSONObject().apply { put("type", "integer"); put("description", "分钟（0-59）") })
                    })
                    put("required", JSONArray().apply { put("hour"); put("minute") })
                })
            })
            put(JSONObject().apply {
                put("name", "lock_screen")
                put("description", "强制锁屏")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "play_music")
                put("description", "播放音乐（通过QQ音乐或网易云）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().apply { put("type", "string"); put("description", "歌曲名或歌手名") })
                        put("platform", JSONObject().apply { put("type", "string"); put("description", "平台：qq/netease/auto（默认auto）") })
                    })
                    put("required", JSONArray().apply { put("query") })
                })
            })
            put(JSONObject().apply {
                put("name", "get_now_playing")
                put("description", "获取当前正在播放的音乐")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "get_ui_tree")
                put("description", "获取当前界面控件树，含文本/坐标/可点击等信息")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("max_depth", JSONObject().apply { put("type", "integer"); put("description", "最大树深度，默认8") })
                        put("include_bounds", JSONObject().apply { put("type", "boolean"); put("description", "是否包含坐标边界，默认true") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "tap")
                put("description", "点击屏幕坐标")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("x", JSONObject().apply { put("type", "integer"); put("description", "X坐标") })
                        put("y", JSONObject().apply { put("type", "integer"); put("description", "Y坐标") })
                        put("duration_ms", JSONObject().apply { put("type", "integer"); put("description", "按下时长毫秒，默认80") })
                    })
                    put("required", JSONArray().apply { put("x"); put("y") })
                })
            })
            put(JSONObject().apply {
                put("name", "swipe")
                put("description", "从起点滑到终点")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("start_x", JSONObject().apply { put("type", "integer"); put("description", "起点X") })
                        put("start_y", JSONObject().apply { put("type", "integer"); put("description", "起点Y") })
                        put("end_x", JSONObject().apply { put("type", "integer"); put("description", "终点X") })
                        put("end_y", JSONObject().apply { put("type", "integer"); put("description", "终点Y") })
                        put("duration_ms", JSONObject().apply { put("type", "integer"); put("description", "滑动时长毫秒，默认400") })
                    })
                    put("required", JSONArray().apply { put("start_x"); put("start_y"); put("end_x"); put("end_y") })
                })
            })
            put(JSONObject().apply {
                put("name", "tap_text")
                put("description", "按屏幕上可见文字点击")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply { put("type", "string"); put("description", "要点击的文字") })
                        put("exact", JSONObject().apply { put("type", "boolean"); put("description", "是否精确匹配，默认false") })
                    })
                    put("required", JSONArray().apply { put("text") })
                })
            })
            put(JSONObject().apply {
                put("name", "input_text")
                put("description", "在当前聚焦输入框输入文字")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply { put("type", "string"); put("description", "要输入的文字") })
                    })
                    put("required", JSONArray().apply { put("text") })
                })
            })
            put(JSONObject().apply {
                put("name", "press_key")
                put("description", "模拟系统按键：back/home/recents/notifications/quick_settings")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("key", JSONObject().apply { put("type", "string"); put("description", "按键名") })
                    })
                    put("required", JSONArray().apply { put("key") })
                })
            })
            put(JSONObject().apply {
                put("name", "get_screenshot_base64")
                put("description", "获取当前截屏 base64（不分析），适合 agent 自行视觉处理")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "open_app")
                put("description", "打开指定App，传包名或应用名")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "App包名，如 com.tencent.mm") })
                        put("app_name", JSONObject().apply { put("type", "string"); put("description", "App名称（模糊匹配），如 微信") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "open_deep_link")
                put("description", "打开深度链接/URL，如 weixin://、https://等")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("url", JSONObject().apply { put("type", "string"); put("description", "深度链接URL") })
                    })
                    put("required", JSONArray().apply { put("url") })
                })
            })
            put(JSONObject().apply {
                put("name", "get_clipboard")
                put("description", "获取剪贴板内容")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "set_clipboard")
                put("description", "设置剪贴板内容")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply { put("type", "string"); put("description", "要复制到剪贴板的文字") })
                    })
                    put("required", JSONArray().apply { put("text") })
                })
            })
            put(JSONObject().apply {
                put("name", "get_running_apps")
                put("description", "获取最近使用的App列表")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "wait_for_text")
                put("description", "等待屏幕上出现指定文字，适合轮询加载完成")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply { put("type", "string"); put("description", "要等待出现的文字") })
                        put("timeout_ms", JSONObject().apply { put("type", "integer"); put("description", "超时毫秒，默认10000") })
                    })
                    put("required", JSONArray().apply { put("text") })
                })
            })

            // ===== Shizuku 工具 (adb shell 权限) =====
            put(JSONObject().apply {
                put("name", "shizuku_exec")
                put("description", "执行任意 shell 命令（需 Shizuku 授权）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply { put("type", "string"); put("description", "Shell 命令") })
                    })
                    put("required", JSONArray().apply { put("command") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_kill_app")
                put("description", "杀死指定包名的进程")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                    })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_freeze_app")
                put("description", "冻结/禁用 App（pm disable-user）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                    })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_unfreeze_app")
                put("description", "解冻/启用 App（pm enable）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                    })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_uninstall_app")
                put("description", "卸载 App（可选保留数据）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                        put("keep_data", JSONObject().apply { put("type", "boolean"); put("description", "保留数据，默认false") })
                    })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_list_packages")
                put("description", "列出已安装包")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("enabled", JSONObject().apply { put("type", "string"); put("description", "all/enabled/disabled，默认all") })
                    })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_app_info")
                put("description", "获取包详细信息（dumpsys package）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                    })
                    put("required", JSONArray().apply { put("package_name") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_grant_permission")
                put("description", "授予权限（pm grant）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                        put("permission", JSONObject().apply { put("type", "string"); put("description", "权限名，如 android.permission.READ_SMS") })
                    })
                    put("required", JSONArray().apply { put("package_name"); put("permission") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_revoke_permission")
                put("description", "撤销权限（pm revoke）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply { put("type", "string"); put("description", "包名") })
                        put("permission", JSONObject().apply { put("type", "string"); put("description", "权限名") })
                    })
                    put("required", JSONArray().apply { put("package_name"); put("permission") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_install_apk")
                put("description", "静默安装 APK（pm install -r）")
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("apk_path", JSONObject().apply { put("type", "string"); put("description", "APK 文件路径") })
                        put("allow_downgrade", JSONObject().apply { put("type", "boolean"); put("description", "允许降级安装 -d") })
                    })
                    put("required", JSONArray().apply { put("apk_path") })
                })
            })
            put(JSONObject().apply {
                put("name", "shizuku_get_processes")
                put("description", "获取运行中的进程列表")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
            put(JSONObject().apply {
                put("name", "shizuku_status")
                put("description", "检查 Shizuku 授权状态")
                put("inputSchema", JSONObject().apply { put("type", "object"); put("properties", JSONObject()) })
            })
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("result", JSONObject().apply { put("tools", tools) })
            put("id", id)
        }.toString()
    }
    private fun handleToolsCall(json: JSONObject, id: Any?): String {
        val params = json.getJSONObject("params")
        val toolName = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()

        val result = when (toolName) {
            "get_battery" -> toolGetBattery()
            "get_screen_time" -> toolGetScreenTime()
            "get_app_timeline" -> toolGetAppTimeline()
            "get_weather" -> toolGetWeather()
            "get_steps" -> toolGetSteps()
            "send_notification" -> toolSendNotification(args)
            "reset_screen_time" -> toolResetScreenTime()
            "save_memory" -> toolSaveMemory(args)
            "read_memory" -> toolReadMemory(args)
            "set_alarm" -> toolSetAlarm(args)
            "cancel_alarm" -> toolCancelAlarm(args)
            "lock_screen" -> toolLockScreen()
            "play_music" -> toolPlayMusic(args)
            "get_now_playing" -> toolGetNowPlaying()
            "get_ui_tree" -> toolGetUiTree(args)
            "tap" -> toolTap(args)
            "swipe" -> toolSwipe(args)
            "tap_text" -> toolTapText(args)
            "input_text" -> toolInputText(args)
            "press_key" -> toolPressKey(args)
            "get_screenshot_base64" -> toolGetScreenshotBase64()
            "open_app" -> toolOpenApp(args)
            "open_deep_link" -> toolOpenDeepLink(args)
            "get_clipboard" -> toolGetClipboard()
            "set_clipboard" -> toolSetClipboard(args)
            "get_running_apps" -> toolGetRunningApps()
            "wait_for_text" -> toolWaitForText(args)

            // Shizuku tools
            "shizuku_exec" -> toolShizukuExec(args)
            "shizuku_kill_app" -> toolShizukuKillApp(args)
            "shizuku_freeze_app" -> toolShizukuFreezeApp(args)
            "shizuku_unfreeze_app" -> toolShizukuUnfreezeApp(args)
            "shizuku_uninstall_app" -> toolShizukuUninstallApp(args)
            "shizuku_list_packages" -> toolShizukuListPackages(args)
            "shizuku_app_info" -> toolShizukuAppInfo(args)
            "shizuku_grant_permission" -> toolShizukuGrantPermission(args)
            "shizuku_revoke_permission" -> toolShizukuRevokePermission(args)
            "shizuku_install_apk" -> toolShizukuInstallApk(args)
            "shizuku_get_processes" -> toolShizukuGetProcesses(args)
            "shizuku_status" -> toolShizukuStatus(args)

            else -> "未知工具：$toolName"
        }

        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("result", JSONObject().apply {
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", result)
                    })
                })
            })
            put("id", id)
        }.toString()
    }

// ==================== 原有工具实现 ====================

    private fun toolGetBattery(): String {
        return try {
            val intentFilter = android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = registerReceiver(null, intentFilter)
            val level = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (scale > 0) (level * 100 / scale) else -1
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            val temp = (batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            val chargingStr = if (charging) "充电中" else "未充电"
            "电量：${pct}%\n状态：${chargingStr}\n温度：${temp}°C"
        } catch (e: Exception) {
            "获取电池信息失败：${e.message}"
        }
    }

    private fun toolGetScreenTime(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = if (resetTimestamp > 0) resetTimestamp else end - 24 * 60 * 60 * 1000
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
            if (stats.isNullOrEmpty()) return "无数据（请确认已授予使用情况访问权限）"

            val sorted = stats.filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.totalTimeInForeground }

            val total = sorted.sumOf { it.totalTimeInForeground }
            val totalMin = total / 60000
            val totalHr = totalMin / 60
            val remainMin = totalMin % 60

            val sb = StringBuilder()
            sb.appendLine("屏幕使用时间报告")
            sb.appendLine("总计：${totalHr}小时${remainMin}分钟")
            sb.appendLine("---")
            sorted.take(10).forEach {
                val name = getAppName(it.packageName)
                val min = it.totalTimeInForeground / 60000
                sb.appendLine("$name：${min}分钟")
            }
            sb.toString()
        } catch (e: Exception) {
            "获取失败：${e.message}"
        }
    }
    private fun toolGetAppTimeline(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 24 * 60 * 60 * 1000
            val events = usm.queryEvents(start, end)
            val eventList = mutableListOf<String>()
            val event = android.app.usage.UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.timeStamp))
                    val name = getAppName(event.packageName)
                    eventList.add("$time $name")
                }
            }

            if (eventList.isEmpty()) return "最近24小时无App切换记录"

            val sb = StringBuilder()
            sb.appendLine("App使用时间线")
            sb.appendLine("---")
            eventList.forEach { sb.appendLine(it) }
            sb.toString()
        } catch (e: Exception) {
            "获取失败：${e.message}"
        }
    }

    private fun toolGetWeather(): String {
        return try {
            val prefs = getSharedPreferences("lc_config", Context.MODE_PRIVATE)
            val city = prefs.getString("city", "") ?: ""
            if (city.isEmpty()) return "未设置城市，请在App中设置"

            val url = URL("https://wttr.in/${city}?format=j1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "curl/7.0")

            val response = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()

            val json = JSONObject(response)
            val current = json.getJSONArray("current_condition").getJSONObject(0)
            val tempC = current.getString("temp_C")
            val humidity = current.getString("humidity")
            val desc = (current.optJSONArray("lang_zh") ?: current.getJSONArray("weatherDesc")).getJSONObject(0).getString("value")
            val feelsLike = current.getString("FeelsLikeC")
            val windSpeed = current.getString("windspeedKmph")

            val weather = json.getJSONArray("weather").getJSONObject(0)
            val maxTemp = weather.getString("maxtempC")
            val minTemp = weather.getString("mintempC")

            val sb = StringBuilder()
            sb.appendLine("${city}天气")
            sb.appendLine("当前：${desc} ${tempC}°C")
            sb.appendLine("体感：${feelsLike}°C")
            sb.appendLine("湿度：${humidity}%")
            sb.appendLine("风速：${windSpeed}km/h")
            sb.appendLine("今日：${minTemp}°C ~ ${maxTemp}°C")
            sb.toString()
        } catch (e: Exception) {
            "天气获取失败：${e.message}"
        }
    }

    private fun toolGetSteps(): String {
        return "今日步数：${stepCount}步"
    }
    private fun toolSendNotification(args: JSONObject): String {
        val message = args.optString("message", "")
        if (message.isEmpty()) return "消息内容不能为空"

        val channelId = "lc_notify"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (notificationManager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "MistConnect通知", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MistConnect")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        return "已推送：$message"
    }

    private fun toolResetScreenTime(): String {
        resetTimestamp = System.currentTimeMillis()
        return "屏幕使用时间已重置"
    }

    private fun toolSaveMemory(args: JSONObject): String {
        val key = args.optString("key", "").trim()
        val value = args.optString("value", "").trim()
        if (key.isEmpty() || value.isEmpty()) return "key和value不能为空"

        val file = java.io.File(filesDir, "lc_memory.json")
        val json = if (file.exists()) {
            JSONObject(file.readText())
        } else {
            JSONObject()
        }
        json.put(key, value)
        file.writeText(json.toString(2))
        return "已记住：$key = $value"
    }

    private fun toolReadMemory(args: JSONObject): String {
        val key = args.optString("key", "").trim()
        val file = java.io.File(filesDir, "lc_memory.json")
        if (!file.exists()) return "记忆库为空"

        val json = JSONObject(file.readText())

        if (key.isEmpty()) {
            if (json.length() == 0) return "记忆库为空"
            val sb = StringBuilder("记忆库内容：\n")
            json.keys().forEach { k ->
                sb.appendLine("- $k：${json.getString(k)}")
            }
            return sb.toString()
        } else {
            return if (json.has(key)) {
                "$key = ${json.getString(key)}"
            } else {
                "没有找到：$key"
            }
        }
    }
    private fun toolSetAlarm(args: JSONObject): String {
        return try {
            val hour = args.getInt("hour")
            val minute = args.getInt("minute")
            val message = args.optString("message", "MistConnect闹钟")

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }

            val intent = Intent(this, AlarmReceiver::class.java).apply {
                putExtra("message", message)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, hour * 100 + minute, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )

            "已设置闹钟：${hour}:${String.format("%02d", minute)} - $message"
        } catch (e: Exception) {
            "设置闹钟失败：${e.message}"
        }
    }

    private fun toolCancelAlarm(args: JSONObject): String {
        return try {
            val hour = args.getInt("hour")
            val minute = args.getInt("minute")

            val intent = Intent(this, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this, hour * 100 + minute, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent)

            "已取消闹钟：${hour}:${String.format("%02d", minute)}"
        } catch (e: Exception) {
            "取消闹钟失败：${e.message}"
        }
    }

    private fun toolLockScreen(): String {
        return try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = ComponentName(this, LockScreenReceiver::class.java)
            if (dpm.isAdminActive(componentName)) {
                dpm.lockNow()
                "已锁屏"
            } else {
                "锁屏失败：未激活设备管理员，请在App中点击激活"
            }
        } catch (e: Exception) {
            "锁屏失败：${e.message}"
        }
    }

    private fun toolPlayMusic(args: JSONObject): String {
        val query = args.optString("query", "")
        if (query.isEmpty()) return "请提供歌曲名或关键词"
        val platform = args.optString("platform", "auto")

        // 复制到剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("music", query))

        // 确定包名
        val pkgMap = mapOf(
            "netease" to "com.netease.cloudmusic",
            "qq" to "com.tencent.qqmusic",
            "kugou" to "com.kugou.android"
        )

        val targetPkg = if (platform != "auto") {
            pkgMap[platform]
        } else {
            pkgMap.values.firstOrNull {
                try { packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
            }
        }

        val launchIntent = targetPkg?.let { packageManager.getLaunchIntentForPackage(it) }

        if (launchIntent == null) {
            return "已复制「$query」到剪贴板，但未找到已安装的音乐App"
        }

        // 发通知，点击打开音乐app
        val channelId = "lc_music"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(channelId, "音乐播放", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this, 0, launchIntent.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("点击打开音乐App")
            .setContentText("已复制「$query」，打开后粘贴搜索")
            .setStyle(Notification.BigTextStyle().bigText("已复制「$query」，打开后粘贴搜索即可"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(8888, notification)

        return "已复制「$query」到剪贴板并发送通知，点击通知打开音乐App粘贴搜索即可"
    }

// ==================== 截屏 ====================

    private fun toolGetNowPlaying(): String {
        return MusicListenerService.getNowPlaying(this)
    }

    private fun toolGetUiTree(args: JSONObject): String {
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val maxDepth = args.optInt("max_depth", 8)
        val includeBounds = args.optBoolean("include_bounds", true)
        val tree = service.dumpUiTree(maxDepth, includeBounds)
        return tree.toString()
    }

    private fun toolGetScreenshotBase64(): String {
        val service = LCAccessibilityService.instance
            ?: return "截屏未就绪，请先开启无障碍服务"
        val latch = java.util.concurrent.CountDownLatch(1)
        var base64: String? = null
        service.takeScreenshotNow { b64 ->
            base64 = b64
            latch.countDown()
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        if (base64.isNullOrEmpty()) return "截屏失败"
        return "data:image/jpeg;base64,${base64}"
    }

// ==================== UI控制工具 ====================

    private fun toolTap(args: JSONObject): String {
        if (!isUiControlEnabled()) return "UI控制功能未开启，请在App中打开开关"
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val x = args.getInt("x")
        val y = args.getInt("y")
        val duration = args.optLong("duration_ms", 80L)
        val ok = service.tap(x, y, duration)
        return if (ok) "已点击 (${x}, ${y})" else "点击失败"
    }

    private fun toolSwipe(args: JSONObject): String {
        if (!isUiControlEnabled()) return "UI控制功能未开启，请在App中打开开关"
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val sx = args.getInt("start_x")
        val sy = args.getInt("start_y")
        val ex = args.getInt("end_x")
        val ey = args.getInt("end_y")
        val duration = args.optLong("duration_ms", 400L)
        val ok = service.swipe(sx, sy, ex, ey, duration)
        return if (ok) "已滑动 (${sx},${sy})→(${ex},${ey})" else "滑动失败"
    }

    private fun toolTapText(args: JSONObject): String {
        if (!isUiControlEnabled()) return "UI控制功能未开启，请在App中打开开关"
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val text = args.getString("text")
        val exact = args.optBoolean("exact", false)
        val ok = service.tapText(text, exact)
        return if (ok) "已点击文字 「$text」" else "点击文字失败：未找到 「$text」"
    }

    private fun toolInputText(args: JSONObject): String {
        if (!isUiControlEnabled()) return "UI控制功能未开启，请在App中打开开关"
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val text = args.getString("text")
        val ok = service.inputText(text)
        return if (ok) "已输入：$text" else "输入失败：未找到输入框"
    }

    private fun toolPressKey(args: JSONObject): String {
        if (!isUiControlEnabled()) return "UI控制功能未开启，请在App中打开开关"
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val key = args.getString("key")
        val ok = service.pressKey(key)
        return if (ok) "已按：$key" else "按键失败：未知按键 $key"
    }

// ==================== operit风格扩展工具 ====================

    private fun toolOpenApp(args: JSONObject): String {
        val pkgName = args.optString("package_name", "").trim()
        val appName = args.optString("app_name", "").trim()
        if (pkgName.isEmpty() && appName.isEmpty()) return "请提供 package_name 或 app_name"

        val pm = packageManager
        val targetPkg = if (pkgName.isNotEmpty()) {
            try { pm.getPackageInfo(pkgName, 0); pkgName } catch (_: Exception) { null }
        } else {
            val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
            // 优先精确匹配
            allApps.firstOrNull { app ->
                pm.getApplicationLabel(app).toString() == appName
            }?.packageName ?: allApps.firstOrNull { app ->
                val label = pm.getApplicationLabel(app).toString()
                label.contains(appName) && !label.contains("intelligence")
            }?.packageName
        }

        if (targetPkg == null) return "未找到App：${pkgName.ifEmpty { appName }}"

        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
        if (launchIntent == null) return "无法启动App：$targetPkg（无LaunchIntent）"

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        return "已启动：${getAppName(targetPkg)}（$targetPkg）"
    }

    private fun toolOpenDeepLink(args: JSONObject): String {
        val url = args.getString("url").trim()
        if (url.isEmpty()) return "URL不能为空"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            "已打开链接：$url"
        } catch (e: Exception) {
            "打开链接失败：${e.message}"
        }
    }

    private fun toolGetClipboard(): String {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip == null || clip.itemCount == 0) return "剪贴板为空"
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isEmpty()) "剪贴板为空" else "剪贴板内容：$text"
        } catch (e: Exception) {
            "获取剪贴板失败：${e.message}"
        }
    }

    private fun toolSetClipboard(args: JSONObject): String {
        val text = args.getString("text")
        if (text.isEmpty()) return "text不能为空"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MistConnect", text))
        return "已复制到剪贴板：$text"
    }

    private fun toolGetRunningApps(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val end = System.currentTimeMillis()
            val start = end - 60 * 60 * 1000
            val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_BEST, start, end)
            if (stats.isNullOrEmpty()) return "无数据（请确认已授予使用情况访问权限）"

            val sorted = stats.filter { it.totalTimeInForeground > 0 }
                .sortedByDescending { it.lastTimeUsed }
                .take(15)

            val sb = StringBuilder("最近使用的App：\n---\n")
            sorted.forEach {
                val name = getAppName(it.packageName)
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.lastTimeUsed))
                sb.appendLine("$time $name（${it.packageName}）")
            }
            sb.toString()
        } catch (e: Exception) {
            "获取失败：${e.message}"
        }
    }

    private fun toolWaitForText(args: JSONObject): String {
        if (!isUiControlEnabled()) return "UI控制功能未开启，请在App中打开开关"
        val service = LCAccessibilityService.instance
            ?: return "无障碍服务未就绪，请先开启"
        val text = args.getString("text")
        val timeoutMs = args.optInt("timeout_ms", 10000)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = service.getRoot()
            if (root != null) {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                if (!nodes.isNullOrEmpty()) return "已找到文字「$text」"
            }
            Thread.sleep(500)
        }
        return "超时未找到文字「$text」"
    }

// ==================== Shizuku 工具实现 ====================

    private fun toolShizukuExec(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuExec(args)
    }

    private fun toolShizukuKillApp(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuKillApp(args)
    }

    private fun toolShizukuFreezeApp(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuFreezeApp(args)
    }

    private fun toolShizukuUnfreezeApp(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuUnfreezeApp(args)
    }

    private fun toolShizukuUninstallApp(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuUninstallApp(args)
    }

    private fun toolShizukuListPackages(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuListPackages(args)
    }

    private fun toolShizukuAppInfo(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuAppInfo(args)
    }

    private fun toolShizukuGrantPermission(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuGrantPermission(args)
    }

    private fun toolShizukuRevokePermission(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuRevokePermission(args)
    }

    private fun toolShizukuInstallApk(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuInstallApk(args)
    }

    private fun toolShizukuGetProcesses(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuGetRunningProcesses(args)
    }

    private fun toolShizukuStatus(args: JSONObject): String {
        val tools = ShizukuTools(this)
        return tools.handleShizukuStatus()
    }

// ==================== 辅助方法 ====================

    private fun getAppName(pkg: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg.split(".").last()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MistConnect服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持MCP连接"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("MistConnect")
            .setContentText("MCP服务运行中")
            .setOngoing(true)
            .build()
    }
}
