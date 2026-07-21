# MistConnect

**Android MCP 桥接 — 把手机状态、UI 控制、Shizuku shell 工具开放给 AI 客户端。**  
**Android MCP bridge — exposes phone state, UI control, and Shizuku shell tools to AI agents.**

| | |
|---|---|
| **作者 / Author** | Lntano_starry（GitHub: [rejian2022](https://github.com/rejian2022)） |
| **仓库 / Repo** | https://github.com/rejian2022/MistConnect |
| **包名 / Package** | `com.mist.connect` |
| **版本 / Version** | 3.0.0 |
| **协议 / License** | MIT |

> 大量修改自 [LoverConnect](https://github.com/LoverConnect/LoverConnect)，与原项目无关联。  
> Heavily reworked from [LoverConnect](https://github.com/LoverConnect/LoverConnect). Not affiliated with the original project.

---

## 这是什么 / What it is

**中文：** 后台运行的 Android 应用，在手机 `:5000` 端口暴露 MCP HTTP 端点。AI 客户端（RikkaHub、自建 agent 等）通过它可以：

- 读取电量 / 屏幕时间 / 步数 / 天气 / 当前播放
- 推送通知、设闹钟、锁屏
- 读写本地记忆
- 控制 UI（无障碍）：控件树、点击、滑动、输入、截图
- 通过 **Shizuku** 执行 **adb shell** 级别命令（**无需 root**）

**English:** A background Android app that exposes an MCP HTTP endpoint on the phone at port `:5000`, letting AI clients (RikkaHub, custom agents, etc.) do the following:

- Read battery / screen time / steps / weather / now-playing
- Send notifications, set alarms, lock the screen
- Read/write local memory
- Control the UI (AccessibilityService): tree, tap, swipe, input, screenshot
- Run **adb shell–level** commands via **Shizuku** (**no root required**)

---

## MCP 工具列表 / Tools

### 基础 / Core
| 工具 / Tool | 说明 / Description |
|------|------|
| `get_battery` | 电量 + 是否充电 / Battery level + charging |
| `get_screen_time` | 24 小时屏幕使用 / 24h rolling screen usage |
| `get_app_timeline` | App 使用时间线 / App usage timeline |
| `get_steps` | 今日步数 / Today's step count |
| `get_weather` | 天气（城市在 App 内设置） / Weather (city set in app) |
| `send_notification` | 系统通知 / System notification |
| `save_memory` / `read_memory` | 本地 JSON 记忆 / Local JSON memory |
| `reset_screen_time` | 重置屏幕使用统计 / Reset usage stats window |
| `set_alarm` / `cancel_alarm` | 闹钟 / Alarms |
| `lock_screen` | 设备管理员锁屏 / Device-admin lock |
| `play_music` / `get_now_playing` | 媒体 / Media |
| `open_app` / `open_deep_link` | 启动应用 / Launch |
| `get_clipboard` / `set_clipboard` | 剪贴板 / Clipboard |
| `get_running_apps` | 最近使用应用 / Recent apps |

### UI 控制 / UI control（需开启无障碍 + App 内开关 / requires a11y + in-app switch）
| 工具 / Tool | 说明 / Description |
|------|------|
| `get_ui_tree` | 无障碍控件树 / Accessibility tree |
| `get_screenshot_base64` | 截屏 / Screenshot |
| `tap` / `swipe` / `tap_text` / `input_text` / `press_key` | 手势 / Gestures |
| `wait_for_text` | 轮询直到指定文字出现 / Poll until text appears |

### Shizuku（需 Shizuku 运行并授权 / needs Shizuku running + granted）
| 工具 / Tool | 等价 shell / Shell equivalent |
|------|-----------|
| `shizuku_status` | 就绪状态 / readiness |
| `shizuku_exec` | 任意 shell 命令 / any shell command |
| `shizuku_kill_app` | `am kill` |
| `shizuku_freeze_app` / `shizuku_unfreeze_app` | `pm disable-user` / `pm enable` |
| `shizuku_uninstall_app` | `pm uninstall` |
| `shizuku_list_packages` | `pm list packages` |
| `shizuku_app_info` | `dumpsys package` |
| `shizuku_grant_permission` / `shizuku_revoke_permission` | `pm grant` / `pm revoke` |
| `shizuku_install_apk` | `pm install -r` |
| `shizuku_get_processes` | `ps -A` |

---

## 安装 / Install

1. 下载 [Release](../../releases) 中的 APK 并安装 / Download and install the APK from [Releases](../../releases)
2. 打开 App → 授予各项权限（用量、电池、通知、无障碍等） / Open the app and grant the requested permissions (usage, battery, notification, accessibility, …)
3. 启动 MCP（可保持「自动启动 MCP」默认开启） / Start MCP (or leave **auto start** on)
4. 客户端接入端点 / Client endpoint:

```
http://127.0.0.1:5000/mcp
```

同 Wi‑Fi 下用 `http://<手机局域网 IP>:5000/mcp`（App 主页可看）。  
For same Wi‑Fi use `http://<phone-lan-ip>:5000/mcp` (shown in the app).

### 可选 MCP Token / Optional MCP token

在 App 内设置 token，客户端需带 / Set the token in the app. Clients must send:

```
Authorization: Bearer <your-token>
```

对 `tools/list` 和 `tools/call` 强制校验。token 为空 = 开放（局域网风险自负）。  
Enforced on `tools/list` and `tools/call`. Empty token = open (LAN risk).

### Shizuku

1. 安装 [Shizuku](https://shizuku.rikka.app/)，用无线调试 / root / rpk 启动 / Install [Shizuku](https://shizuku.rikka.app/), start via wireless debugging / root / rpk
2. MistConnect → Shizuku 权限页 → 授权 / In MistConnect → Shizuku → authorize
3. 即可使用 `shizuku_*` 系列 / Use the `shizuku_*` tools

---

## 编译 / Build

```bash
./gradlew :app:assembleDebug
# 产物 / out: app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 21 + Android SDK 36。也可用 Android Studio 直接打开。  
Requires JDK 21 + Android SDK 36. Android Studio works too.

---

## 注意事项 / Notes

- **国产 ROM 杀后台** / **OEM kill:** 小米 / OPPO / vivo 等需把 MistConnect 加入电池白名单、开启自启动、允许后台运行 / Xiaomi / OPPO / vivo → battery whitelist + autostart + allow background
- **前台服务类型** / **FGS type:** `specialUse` + 注明子类型（本地 MCP 桥接 / 闹钟响铃） / `specialUse` with documented subtype (local MCP bridge / alarm ring)
- **SharedPreferences**：仍使用 `lc_config` / `lc_memory.json` 等 key，保留升级兼容性 / Prefs still use `lc_config` / `lc_memory.json` keys for upgrade compatibility
- **明文 HTTP** / **Cleartext:** MCP 走本地 5000 端口的明文 HTTP（仅本机或局域网）。建议启用 token，不要对外网开放 / Plain HTTP on port 5000 (local device / LAN only). Prefer token + avoid public networks

---

## 搜索关键词 / Keywords for discovery

`Android MCP` · `MCP server` · `Shizuku` · `phone control` · `AccessibilityService` · `AI agent` · `Kotlin` · `no root` · `adb shell` · `UI automation` · `Kiro` · `Claude` · `OpenCode` · `RikkaHub`

---

## 许可 / License

MIT © 2026 Lntano_starry

上游灵感 / Upstream inspiration: LoverConnect。本项目作为 MistConnect 独立维护 / This fork is independently maintained as MistConnect.
