# MistConnect

Android MCP bridge — phone state, UI control, Shizuku shell tools for AI clients.

**Author:** Lntano_starry (`rejian2022`)  
**Repo:** https://github.com/rejian2022/MistConnect  
**Package:** `com.mist.connect` · **Version:** 3.0.0  
**License:** MIT

> Heavily reworked from [LoverConnect](https://github.com/LoverConnect/LoverConnect). Not affiliated with the original project.

---

## What it is

Background Android app that exposes an MCP HTTP endpoint (`:5000`) so AI clients (RikkaHub, custom agents, etc.) can:

- read battery / screen time / steps / weather / now-playing
- send notifications, set alarms, lock screen
- save/read local memory
- control UI (a11y): tree, tap, swipe, input, screenshot
- run **adb shell–level** commands via **Shizuku** (no root)

---

## Tools (MCP)

### Core
| Tool | Description |
|------|-------------|
| `get_battery` | Battery level + charging |
| `get_screen_time` | 24h rolling screen usage |
| `get_app_timeline` | App usage timeline |
| `get_steps` | Today steps |
| `get_weather` | Weather via wttr.in (city in app) |
| `send_notification` | System notification |
| `save_memory` / `read_memory` | Local JSON memory |
| `reset_screen_time` | Reset usage stats window |
| `set_alarm` / `cancel_alarm` | Alarms |
| `lock_screen` | Device admin lock |
| `play_music` / `get_now_playing` | Media |
| `open_app` / `open_deep_link` | Launch |
| `get_clipboard` / `set_clipboard` | Clipboard |
| `get_running_apps` | Recent apps |

### UI control (need a11y + in-app switch)
| Tool | Description |
|------|-------------|
| `get_ui_tree` | Accessibility tree |
| `get_screenshot_base64` | Screenshot |
| `tap` / `swipe` / `tap_text` / `input_text` / `press_key` | Gestures |
| `wait_for_text` | Poll until text appears |

### Shizuku (need Shizuku running + granted)
| Tool | Shell equivalent |
|------|------------------|
| `shizuku_status` | readiness |
| `shizuku_exec` | any shell command |
| `shizuku_kill_app` | `am kill` |
| `shizuku_freeze_app` / `shizuku_unfreeze_app` | `pm disable-user` / `pm enable` |
| `shizuku_uninstall_app` | `pm uninstall` |
| `shizuku_list_packages` | `pm list packages` |
| `shizuku_app_info` | `dumpsys package` |
| `shizuku_grant_permission` / `shizuku_revoke_permission` | `pm grant` / `pm revoke` |
| `shizuku_install_apk` | `pm install -r` |
| `shizuku_get_processes` | `ps -A` |

---

## Install

1. Install release APK (see [Releases](../../releases)).
2. Open app → grant chips (usage, battery, notification, a11y, …).
3. Start MCP (or leave **auto start** on).
4. Client endpoint:

```
http://127.0.0.1:5000/mcp
```

Same Wi‑Fi: `http://<phone-lan-ip>:5000/mcp` (copy from app).

### Optional MCP Token

Set token in app. Then clients must send:

```
Authorization: Bearer <token>
```

Enforced on `tools/list` and `tools/call`. Empty token = open (LAN risk).

### Shizuku

1. Install [Shizuku](https://shizuku.rikka.app/), start via wireless debugging / root / rpk.
2. In MistConnect → Shizuku → authorize.
3. Use `shizuku_*` tools.

---

## Build

```bash
# Android Studio or CLI (JDK 21 + SDK 36)
./gradlew :app:assembleDebug
# out: app/build/outputs/apk/debug/app-debug.apk
```

Windows helper (this workspace): `build_lc.ps1` on build host — paths may need update to `MistConnect`.

---

## Notes

- **OEM kill:** Xiaomi / OPPO / vivo → battery whitelist + autostart + allow background.
- **FGS type:** `specialUse` with documented subtype (local MCP bridge / alarm ring).
- **Prefs:** still use `lc_config` / `lc_memory.json` keys for upgrade compatibility.
- **Cleartext:** MCP is plain HTTP on port 5000 (local device / LAN). Prefer token + avoid public networks.

---

## License

MIT © 2026 Lntano_starry

Upstream inspiration: LoverConnect. This fork is independently maintained as MistConnect.
