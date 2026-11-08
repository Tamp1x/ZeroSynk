# ZeroSynk

Sync clipboard, files, notifications, and media between a Windows PC and an Android phone over your local network. No cloud involved: the phone is the client (WebSocket/HTTP), the PC runs the server on port **8765**. Dark Apple-style UI, fully localized in Russian.

## Features

### PC → Phone
- **Clipboard** — text copied on the PC instantly appears on the phone
- **Files** — send via the file explorer context menu "Send to ZeroSynk" (or the tray), with a confirmation window; works even when the app is closed
- **Media player** — a notification on the phone mirrors what the PC plays: cover art, progress bar with time (`m:ss / m:ss`), ⏮ ⏸ ⏭, ±10 s seek; it never buzzes (silent + 2 s throttle)

### Phone → PC
- **Clipboard** — text from the phone lands in the Windows clipboard
- **Files and photos** — share from any app; multi-select from the gallery is supported (sent as a single batch so auto-cleanup never deletes neighboring files)
- **Notifications** — phone notifications are mirrored to the PC with inline replies (RemoteInput)
- **Media** — control the PC's playback from the phone (play/pause, next/prev, ±10 s seek)
- **Reverse media control** — the phone's own player shows up as a card on the PC's home screen ("Playing on phone": cover, progress, seek, ±10 s, volume, stop, open track folder) and is controllable from there; needs notification access (same permission as mirroring)

### Common
- **Pairing via QR / PIN** — one-click setup, token stored locally
- **Custom dark tray** — OwnerDraw menu matching the app theme
- **Floating pill-dock** navigation on Android
- **Clipboard and notification history** on a dedicated Android tab
- **Home-screen widget and Quick Settings tile** for sending the current clipboard to the PC
- **Opt-in screenshot sync** — after Android's screen-capture consent, one screenshot is sent every 10 minutes while the capture service is running (off by default)
- **Shared locations and contacts** — geo links open in maps on the PC; vCards arrive as `.vcf` files
- **Phone battery status** on the PC and a live diagnostics view
- **Material You** colors on Android 12+ and a one-time feature tour after pairing
- **Auto-cleanup of old files** — keeps the recent send batches (toggle + "Keep batches" 0/1/2/3/5) and deletes **only files received from the phone** — your own files in the save folder are never touched
- **Custom save folder (Android)** — pick any folder (SD card, DCIM, Telegram…) for files sent from the PC via the SAF folder picker; default is still Download/ZeroSynk
- **Stop/start sync** from either side — the PC pushes `status started/stopped`, so the phone never gets stuck on "not connected"
- **Pop-ups independent of the tray** — hiding the tray icon no longer kills notifications: they fall back to toast windows ("Pop-up notifications" setting)
- **Offline file delivery** — offers made while the phone is away are re-sent on its next `hello` (kept up to 24 h)
- Transfer history; port/address/theme in settings

## Project layout

```
pc/ZeroSynk/        WPF app (.NET 8, Kestrel, WebSocket)
android/              Android client (Kotlin, Jetpack Compose, minSdk 26)
tools/phone-sim.mjs   Protocol E2E tests (18 checks)
PLAN.md               Roadmap and version status
```

## Building

### PC (Windows, .NET SDK 8 required)
```powershell
dotnet publish "pc\ZeroSynk\ZeroSynk.csproj" -c Release -r win-x64 `
  --self-contained false -p:PublishSingleFile=true -o publish-fd
.\publish-fd\ZeroSynk.exe
```

### Android
```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
cd android
.\gradlew assembleRelease --no-daemon
# APK: android\app\build\outputs\apk\release\app-release.apk
```

Release signing reads `android/keystore.properties` + `android/keystore/` (deliberately not in git). Without them the build falls back to the debug signature.

### Tests
```powershell
# start ZeroSynk.exe first, then:
node tools\phone-sim.mjs 127.0.0.1 8765
```
Expected result: `18 passed, 0 failed`.

## Protocol (v1, camelCase JSON)

| Endpoint | Purpose |
|---|---|
| `WS /ws?token=…` | Duplex channel: clipboard, status, media, notifications |
| `POST /api/pair {pin}` | Pairing, issues a token |
| `POST /api/upload?token=…&name=…&batch=…` | File upload (`batch` = send-batch identifier) |
| `GET /api/file/{id}?token=…` | Download a file from the PC |
| `GET /api/ping` | Availability check |

`batch` groups a multi-file send: auto-cleanup triggers only when a **new** batch arrives and never touches files of the current one.

`hello` (phone → PC) and `welcome` (PC → phone) carry `proto` — the wire-protocol version; a mismatch is logged and shown as a warning on both sides. Pending file offers are re-sent on every `hello` and expire after 24 h; the staged copy is deleted only once the download response fully completed.

Media messages are bidirectional: `media` carries `state`, `title`, `artist`, `app`, `position`/`duration` (seconds) and `cover` (base64 album art, sent only on track change — the server re-attaches the cached cover for clients joining mid-track); the phone tags its own now-playing with `origin: "phone"`. `mediaControl` carries `action` (`play`/`pause`/`toggle`/`next`/`prev`/`stop`/`seek`/`seekBack`/`seekFwd`/`volUp`/`volDown`/`openFolder`) plus `position` for absolute seeks, in both directions.

## Known limitations

- PC → phone notification mirroring is a **stub** (WinRT `UserNotificationListener` isn't projected without CsWinRT)
- No "favorite/like" in the media player: Windows SMTC exposes no such API
- Reverse media control (phone → PC card) relies on notification access; players that don't publish a MediaSession won't show up
- On Windows 11 the context-menu entry appears under "Show more options" (the modern quick menu needs COM IExplorerCommand + a sparse package — on the roadmap)
- Local network only; no internet required

## Versions

| Version | Highlights |
|---|---|
| 1.5.0 | Rounded QR-code corners in the Windows app |
| 1.4.0 | Explorer context menu, dark tray, pill-dock, multi-photo send, auto-cleanup |
| 1.3.0 | Notifications phone → PC, stop/start sync |
| 1.2.0 | Media player, history |
| 1.0.0 | Clipboard, files, QR/PIN pairing |
