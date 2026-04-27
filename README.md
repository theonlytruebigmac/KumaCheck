# KumaCheck

A native Android client for [Uptime Kuma](https://github.com/louislam/uptime-kuma) — open source, real-time, with push alerts.

KumaCheck connects to your self-hosted Uptime Kuma instance over Socket.IO and gives you a phone-native interface for the things you actually do on the go: glancing at monitor health, getting alerted when something falls over, and managing maintenance windows. Direct device-to-server connection — no middleman, no cloud sync, no analytics.


## Features

**Dashboard**

- Live "all systems operational" view with aggregate 30-day uptime and average response time
- Status chips for UP / DOWN / MAINT / PAUSED counts (tap to filter the Monitors tab)
- Pinned monitor heartbeats with a custom-selection picker; expand to show all monitors
- Uptime ring charts (24h and 30d) and per-monitor response-time sparklines
- Recent incidents derived from server-side heartbeat history

**Monitors**

- Live status with folder grouping, status filters, and search
- Per-monitor detail: response chart, recent-checks strip, uptime metrics
- Edit common fields (name, intervals, retries, description) — the raw monitor JSON is round-tripped so unsupported fields aren't clobbered
- Delete with confirmation
- Per-monitor notification mute

**Maintenance**

- Full CRUD: list, detail, create, edit, delete
- All six Kuma strategies: Manual / Single / Weekly / Interval / Monthly / Cron
- Pause and resume from the detail screen
- Affected-monitors view via `getMonitorMaintenance`

**Notifications**

- Foreground service keeps the socket alive while the app is backgrounded
- Push alerts on `important=true` heartbeats — incidents and recoveries
- De-duped against repeated transitions; honours per-monitor mute
- Auto-restarts on device boot or app upgrade (`BootReceiver`)
- Tap an incident notification to deep-link straight to the monitor detail
- "Send test notification" button to verify channel + permission setup

## Requirements

- Android 8.0 (API 26) or later
- An [Uptime Kuma](https://github.com/louislam/uptime-kuma) server you can reach from your phone
- Your Kuma username + password (TOTP supported if 2FA is enabled)

## Build and install

```bash
git clone <this-repo-url>
cd KumaCheck
./gradlew :app:installDebug
```

Open the app, enter your server URL (e.g. `https://kuma.example.com`), then sign in. The app stores your JWT locally and auto-resumes the session on next launch and after pull-to-refresh.

To work on the code, Android Studio Ladybug (2024.2) or newer is the easiest path; otherwise the project builds with `./gradlew` from any terminal.

**Tech stack:** Kotlin 2.0, Jetpack Compose, Material 3, AGP 8.7, Gradle 8.14.3 on JDK 21+. Socket I/O via `socket.io-client`, charts via Vico, persistence via DataStore.

## Setup notes

**Notifications.** Off by default — toggle on in Settings → Notifications. The app requests `POST_NOTIFICATIONS` on Android 13+ before flipping the pref true. The foreground service runs while you have an active session, holding the socket open so alerts arrive in real time.

**Pinned heartbeats.** The Live Heartbeat list on Overview shows the first 5 monitors by default. Tap the pencil icon to pick which monitors should be pinned. The selection persists across launches.

**Maintenance timezone.** The maintenance edit form sends the device's local timezone. Editing windows across regions is best handled in the Kuma web UI for now.

**Connection state.** A small chip in the hero card shows the live socket status. On a transient network blip, the app re-authenticates automatically using the stored token — no need to re-enter credentials.

## Project structure

```
app/src/main/java/app/kumacheck/
├── KumaCheckApp.kt              # Application + reactive service lifecycle
├── MainActivity.kt              # Single-task activity with deep-link handling
├── data/
│   ├── auth/                    # KumaPrefs (DataStore), AuthRepository
│   ├── model/                   # Plain data classes (Monitor, Heartbeat, etc.)
│   └── socket/                  # KumaSocket wrapper + JSON parsers
├── notify/
│   ├── MonitorService.kt        # Foreground service for push alerts
│   ├── BootReceiver.kt          # Auto-restart on boot / app upgrade
│   └── Notifications.kt         # Channels + notification builders
└── ui/
    ├── overview/                # Dashboard (uptime rings, sparklines, incidents)
    ├── monitors/                # List with filters + search
    ├── manage/                  # Pause/resume toggle list
    ├── settings/                # Server info, alerts, maintenance shortcut
    ├── detail/                  # Monitor detail (chart, history, mute)
    ├── edit/                    # Monitor edit (common fields)
    ├── maintenance/             # List / detail / create + edit screens
    ├── login/, splash/          # Auth flows
    └── main/                    # MainShell + bottom nav
```

## Roadmap

[`docs/ROADMAP.md`](docs/ROADMAP.md) tracks remaining work with status markers (`Not started` / `WIP` / `Done`). Notable gaps still to close: type-specific monitor edit fields, "add new monitor" flow, certificate expiry surfacing, relative timestamps, quiet hours, home-screen widgets, light/system theme, multi-server.

## Inspiration and acknowledgments

KumaCheck is inspired by [kumaalert.app](https://kumaalert.app), the paid iOS Kuma client by another developer. KumaCheck is an **independent open-source Android implementation** — not affiliated with kumaalert.app, not a port of its code, no shared assets. Different platform, different codebase, similar spirit.

Built on top of [Uptime Kuma](https://github.com/louislam/uptime-kuma), the self-hosted monitoring tool by Louis Lam and contributors.

This project is not affiliated with or endorsed by Uptime Kuma, kumaalert.app, or any of their authors.

## License

MIT — see [LICENSE](LICENSE).
