# MistConnect

Android MCP 桥接 — 把手机状态、UI 控制、Shizuku shell 工具开放给 AI 客户端。

**作者：** Lntano_starry（GitHub: [rejian2022](https://github.com/rejian2022)）
**仓库：** https://github.com/rejian2022/MistConnect
**包名：** `com.mist.connect` · **版本：** 3.0.0
**License：** MIT

> 大量修改自 [LoverConnect](https://github.com/LoverConnect/LoverConnect)，与原项目无关联。

---

## 这是什么

后台运行的 Android 应用，在手机 `:5000` 端口暴露一个 MCP HTTP 端点，AI 客户端（RikkaHub、自建 agent 等）通过它可以：

- 读取电量 / 屏幕时间 / 步数 / 天气 / 当前播放
- 推送通知、设闹钟、锁屏
- 读写本地记忆
- 控制 UI（无障碍）：控件树、点击、滑动、输入、截图
- 通过 **Shizuku** 执行 **adb shell** 级别命令（**无需 root**）

---

## MCP 工具列表

### 基础
| 工具 | 说明 |
|------|------|
| `get_battery` | 电量 + 是否充电 |
| `get_screen_time` | 24 小时屏幕使用 |
| `get_app_timeline` | App 使用时间线 |
| `get_steps` | 今日步数 |
| `get_weather` | 天气（城市在 App 内设置） |
| `send_notification` | 系统通知 |
| `save_memory` / `read_memory` | 本地 JSON 记忆 |
| `reset_screen_time` | 重置屏幕使用统计 |
| `set_alarm` / `cancel_alarm` | 闹钟 |
| `lock_screen` | 设备管理员锁屏 |
| `play_music` / `get_now_playing` | 媒体 |
| `open_app` / `open_deep_link` | 启动应用 |
| `get_clipboard` / `set_clipboard` | 剪贴板 |
| `get_running_apps` | 最近使用应用 |

### UI 控制（需开启无障碍 + App 内开关）
| 工具 | 说明 |
|------|------|
| `get_ui_tree` | 无障碍控件树 |
| `get_screenshot_base64` | 截屏 |
| `tap` / `swipe` / `tap_text` / `input_text` / `press_key` | 手势 |
| `wait_for_text` | 轮询直到指定文字出现 |

### Shizuku（需 Shizuku 运行并授权）
| 工具 | 等价 shell |
|------|-----------|
| `shizuku_status` | 就绪状态 |
| `shizuku_exec` | 任意 shell 命令 |
| `shizuku_kill_app` | `am kill` |
| `shizuku_freeze_app` / `shizuku_unfreeze_app` | `pm disable-user` / `pm enable` |
| `shizuku_uninstall_app` | `pm uninstall` |
| `shizuku_list_packages` | `pm list packages` |
| `shizuku_app_info` | `dumpsys package` |
| `shizuku_grant_permission` / `shizuku_revoke_permission` | `pm grant` / `pm revoke` |
| `shizuku_install_apk` | `pm install -r` |
| `shizuku_get_processes` | `ps -A` |

---

## 安装

1. 下载 [Release](../../releases) 中的 APK 并安装
2. 打开 App → 授予各项权限（用量、电池、通知、无障碍等）
3. 启动 MCP（可保持「自动启动 MCP」默认开启）
4. 客户端接入端点：

```
http://127.0.0.1:5000/mcp
```

同 Wi‑Fi 下用 `http://<手机局域网 IP>:5000/mcp`（App 主页可看）。

### 可选 MCP Token

在 App 内设置 token。客户端需带：

```
Authorization: Bearer <你的token>
```

对 `tools/list` 和 `tools/call` 强制校验。token 为空 = 开放（局域网风险自负）。

### Shizuku

1. 安装 [Shizuku](https://shizuku.rikka.app/)，用无线调试 / root / rpk 启动
2. MistConnect → Shizuku 权限页 → 授权
3. 即可使用 `shizuku_*` 系列

---

## 编译

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 21 + Android SDK 36。也可用 Android Studio 直接打开。

---

## 注意事项

- **国产 ROM 杀后台**：小米 / OPPO / vivo 等需把 MistConnect 加入电池白名单、开启自启动、允许后台运行
- **前台服务类型**：`specialUse` + 注明子类型（本地 MCP 桥接 / 闹钟响铃）
- **SharedPreferences**：仍使用 `lc_config` / `lc_memory.json` 等 key，保留升级兼容性
- **明文 HTTP**：MCP 走本地 5000 端口的明文 HTTP（仅本机或局域网）。建议启用 token，不要对外网开放

---

## 许可

MIT © 2026 Lntano_starry

上游灵感：LoverConnect。本项目作为 MistConnect 独立维护。
