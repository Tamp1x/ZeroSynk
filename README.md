# ZeroSynk

Sync clipboard, files, notifications, and media between a Windows PC and an Android phone over your local network. No cloud involved: the phone is the client (WebSocket/HTTP), the PC runs the server on port **8765**. Dark Apple-style UI, fully localized in Russian.

## Features

### PC → Phone
- **Clipboard** — text copied on the PC instantly appears on the phone
- **Files** — send via the file explorer context menu "Send to ZeroSynk" (or the tray), with a confirmation window; works even when the app is closed
- **Media control** — mini player on the phone: ⏮ ⏸ ⏭

### Phone → PC
- **Clipboard** — text from the phone lands in the Windows clipboard
- **Files and photos** — share from any app; multi-select from the gallery is supported (sent as a single batch so auto-cleanup never deletes neighboring files)
- **Notifications** — phone notifications are mirrored to the PC with inline replies (RemoteInput)
- **Media** — control playback from the PC

### Common
- **Pairing via QR / PIN** — one-click setup, token stored locally
- **Custom dark tray** — OwnerDraw menu matching the app theme
- **Floating pill-dock** navigation on Android
- **Auto-cleanup of old files** — the save folder only keeps the current send batch (configurable: "Clean old files" toggle)
- **Stop/start sync** from either side
- Transfer history; port/address/theme in settings

## Project layout

```
pc/BufferSynk/        WPF app (.NET 8, Kestrel, WebSocket)
android/              Android client (Kotlin, Jetpack Compose, minSdk 26)
tools/phone-sim.mjs   Protocol E2E tests (18 checks)
PLAN.md               Roadmap and version status
```

## Building

### PC (Windows, .NET SDK 8 required)
```powershell
dotnet publish "pc\BufferSynk\BufferSynk.csproj" -c Release -r win-x64 `
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

## Known limitations

- PC → phone notification mirroring is a **stub** (WinRT `UserNotificationListener` isn't projected without CsWinRT)
- On Windows 11 the context-menu entry appears under "Show more options" (the modern quick menu needs COM IExplorerCommand + a sparse package — on the roadmap)
- Local network only; no internet required

## Versions

| Version | Highlights |
|---|---|
| 1.4.0 | Explorer context menu, dark tray, pill-dock, multi-photo send, auto-cleanup |
| 1.3.0 | Notifications phone → PC, stop/start sync |
| 1.2.0 | Media player, history |
| 1.0.0 | Clipboard, files, QR/PIN pairing |
