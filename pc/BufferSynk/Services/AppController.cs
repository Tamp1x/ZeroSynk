using System.IO;
using System.Security.Cryptography;
using System.Text;
using BufferSynk.Models;

namespace BufferSynk.Services;

/// <summary>
/// Central coordinator: glues SyncServer + ClipboardWatcher together,
/// runs loop-prevention and history recording. Raises UI-thread events via Dispatcher.
/// </summary>
public sealed class AppController : IAsyncDisposable
{
    public AppSettings Settings { get; }
    public SyncServer Server { get; } = new();
    public WindowsNotifService WinNotif { get; }

    private ClipboardWatcher? _watcher;
    private string? _lastAppliedHash;   // content we just wrote locally (don't echo back)
    private string? _lastSentHash;      // content we just pushed to the phone
    private int _sendingClipboard;      // 1 while our own SetClipboard is in flight
    private bool _started;

    public event Action<string>? StatusChanged;
    public event Action<string>? Log;
    public event Action<bool>? ClientPresence;      // true = phone connected
    public event Action<HistoryEntry>? HistoryAdded;
    public event Action<string, string>? FileSaved; // (path, sizeText)
    public event Action? SettingsSaved;
    public event Action<WsMessage>? NotifReceived;  // phone -> PC notification mirror

    public AppController(AppSettings settings)
    {
        Settings = settings;
        WinNotif = new WindowsNotifService(this);
        WinNotif.Log += m => Log?.Invoke(m);
        Log += WriteFileLog;
    }

    private static readonly object _logLock = new();

    private static void WriteFileLog(string msg)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BufferSynk");
            Directory.CreateDirectory(dir);
            lock (_logLock)
                File.AppendAllText(Path.Combine(dir, "debug.log"),
                    $"[{DateTime.Now:HH:mm:ss.fff}] {msg}{Environment.NewLine}");
        }
        catch { }
    }

    public async Task StartAsync()
    {
        if (_started) return;
        _started = true;

        Server.Log += m => Log?.Invoke(m);
        Server.ClientConnected += info =>
        {
            StatusChanged?.Invoke($"Телефон подключён: {info.Name}");
            ClientPresence?.Invoke(true);
        };
        Server.ClientDisconnected += _ =>
        {
            if (Server.ClientCount == 0)
            {
                StatusChanged?.Invoke("Ожидание подключения телефона");
                ClientPresence?.Invoke(false);
            }
        };
        Server.MessageReceived += msg => _ = HandlePhoneMessageAsync(msg);
        NotifReceived += msg => _ = WinNotif.ShowPhoneNotifAsync(msg);
        MediaService.StateChanged += snap =>
        {
            if (!Settings.MediaToPhone || !Settings.SyncEnabled)
            {
                _ = Server.BroadcastMediaAsync(WsMessage.Media("stopped", "", "", ""));
                return;
            }
            _ = Server.BroadcastMediaAsync(
            WsMessage.Media(
                snap.Active ? snap.State : "stopped",
                snap.Title,
                snap.Artist,
                snap.App));
        };
        Server.FileReceived += file =>
        {
            TrackReceivedBatch(file);

            AddHistory(new HistoryEntry
            {
                Direction = "fromPhone",
                Kind = "file",
                Preview = file.Name,
                Size = file.Size,
                Status = "received",
                FilePath = file.Path,
            });
            FileSaved?.Invoke(file.Path, SyncServer.FormatSize(file.Size));
            if (Settings.PlaySound)
                try { System.Media.SystemSounds.Exclamation.Play(); } catch { }
        };

        await Server.StartAsync(Settings);
        _ = MediaService.InitAsync();

        // Start Windows notification mirroring if enabled
        if (Settings.MirrorWinNotifs && Settings.SyncEnabled)
        {
            _ = Task.Run(async () => await WinNotif.StartAsync());
        }

        try
        {
            Directory.CreateDirectory(Settings.SaveFolder);
        }
        catch { }

        // Clipboard watcher needs a WMT-sourced HwndSource; caller runs on UI thread.
        _watcher = new ClipboardWatcher();
        _watcher.ClipboardChanged += OnClipboardChanged;
        _watcher.Start();

        StatusChanged?.Invoke(Server.ClientCount > 0 ? "Телефон подключён" : "Ожидание подключения телефона");
    }

    // ---- phone -> PC ----

    private async Task HandlePhoneMessageAsync(WsMessage msg)
    {
        try
        {
            switch (msg.Type)
            {
                case "clipboard" when msg.Kind == "text" && !string.IsNullOrEmpty(msg.Text):
                    if (!Settings.SyncClipboardFromPhone) return;
                    if (msg.Hash != null && string.Equals(msg.Hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return; // echo
                    ApplyToClipboard(msg.Text, msg.Hash);
                    AddHistory(new HistoryEntry
                    {
                        Direction = "fromPhone",
                        Kind = "text",
                        Preview = Truncate(msg.Text, 120),
                        Size = Encoding.UTF8.GetByteCount(msg.Text),
                        Status = "received",
                    });
                    break;

                case "clipboard" when msg.Kind == "image" && !string.IsNullOrEmpty(msg.Data):
                    if (!Settings.SyncClipboardFromPhone) return;
                    if (msg.Hash != null && string.Equals(msg.Hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return;
                    var bytes = Convert.FromBase64String(msg.Data);
                    if (bytes.Length > Protocol.MaxImageBytes) return;
                    ApplyImageToClipboard(bytes, msg.Hash);
                    AddHistory(new HistoryEntry
                    {
                        Direction = "fromPhone",
                        Kind = "image",
                        Preview = $"Изображение {SyncServer.FormatSize(bytes.Length)}",
                        Size = bytes.Length,
                        Status = "received",
                    });
                    break;

                case "mediaControl" when !string.IsNullOrEmpty(msg.Action):
                    Log?.Invoke($"media control from phone: {msg.Action}");
                    _ = MediaService.ExecuteAsync(msg.Action!);
                    break;

                case "notif" when msg.Origin == "phone" && Settings.MirrorPhoneNotifs && Settings.SyncEnabled:
                    Log?.Invoke($"notif from phone: {msg.AppName}/{msg.Title}");
                    NotifReceived?.Invoke(msg);
                    break;

                case "notifReply":
                    Log?.Invoke($"notif reply from phone for key {msg.Key}");
                    // No public API to inject replies into Windows apps — log only.
                    break;

                case "notifRemoved":
                    Log?.Invoke($"notif removed on phone: {msg.Key}");
                    break;

                case "status" when msg.State == "stopped":
                    Log?.Invoke("phone stopped sync");
                    ClientPresence?.Invoke(false);
                    StatusChanged?.Invoke("Телефон остановил синхронизацию");
                    break;

                case "bye":
                    Log?.Invoke("phone disconnected (bye)");
                    ClientPresence?.Invoke(false);
                    break;
            }
        }
        catch (Exception ex)
        {
            Log?.Invoke($"HandlePhoneMessage error: {ex.Message}");
        }
    }

    private void ApplyToClipboard(string text, string? hash)
    {
        _ = System.Windows.Application.Current?.Dispatcher?.InvokeAsync(() =>
        {
            Interlocked.Exchange(ref _sendingClipboard, 1);
            try
            {
                _lastAppliedHash = hash ?? HashText(text);
                System.Windows.Clipboard.Clear();
                System.Windows.Clipboard.SetText(text);
            }
            catch { }
            finally
            {
                // give WM_CLIPBOARDUPDATE a chance to fire before re-enabling echo suppression
                Task.Delay(600).ContinueWith(_ => Interlocked.Exchange(ref _sendingClipboard, 0));
            }
        });
    }

    private void ApplyImageToClipboard(byte[] png, string? hash)
    {
        _ = System.Windows.Application.Current?.Dispatcher?.InvokeAsync(() =>
        {
            Interlocked.Exchange(ref _sendingClipboard, 1);
            try
            {
                _lastAppliedHash = hash ?? HashBytes(png);
                using var ms = new MemoryStream(png);
                var src = System.Windows.Media.Imaging.BitmapFrame.Create(ms,
                    System.Windows.Media.Imaging.BitmapCreateOptions.None,
                    System.Windows.Media.Imaging.BitmapCacheOption.OnLoad);
                var bmp = new System.Windows.Media.Imaging.TransformedBitmap(src, new System.Windows.Media.ScaleTransform(1, 1));
                System.Windows.Clipboard.Clear();
                System.Windows.Clipboard.SetImage(bmp);
            }
            catch { }
            finally
            {
                Task.Delay(600).ContinueWith(_ => Interlocked.Exchange(ref _sendingClipboard, 0));
            }
        });
    }

    // ---- PC clipboard -> phone ----

    private void OnClipboardChanged()
    {
        if (!_started) return;
        if (Volatile.Read(ref _sendingClipboard) == 1) return;  // our own write
        if (!Settings.SyncClipboardToPhone) return;
        if (Server.ClientCount == 0) { Log?.Invoke("clipboard changed: no clients, skip"); return; }
        Log?.Invoke("clipboard changed: local event received");

        // WPF Clipboard can only be read from an STA thread -> read on dispatcher, send in background.
        _ = System.Windows.Application.Current?.Dispatcher?.InvokeAsync(async () =>
        {
            await Task.Delay(120); // debounce: clipboard fires multiple events per copy
            if (Volatile.Read(ref _sendingClipboard) == 1) return;
            if (Server.ClientCount == 0) return;
            try
            {
                string? text = null;
                byte[]? png = null;
                try
                {
                    text = ClipboardReader.ReadText();
                    if (text == null && Settings.SyncImagesToPhone)
                        png = ClipboardReader.ReadImagePng();
                }
                catch (Exception rex)
                {
                    Log?.Invoke($"Clipboard read error: {rex.Message}");
                    return;
                }

                if (text != null)
                {
                    var hash = HashText(text);
                    if (string.Equals(hash, _lastAppliedHash, StringComparison.OrdinalIgnoreCase) || string.Equals(hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return;
                    _lastSentHash = hash;
                    _ = Task.Run(async () =>
                    {
                        var sent = await Server.SendClipboardTextAsync(text, hash);
                        if (sent)
                        {
                            AddHistory(new HistoryEntry
                            {
                                Direction = "toPhone",
                                Kind = "text",
                                Preview = Truncate(text, 120),
                                Size = Encoding.UTF8.GetByteCount(text),
                                Status = "sent",
                            });
                            Log?.Invoke($"pushed text to phone ({text.Length} chars)");
                        }
                        else
                        {
                            _lastSentHash = null;
                            Log?.Invoke("push text failed: no connection");
                        }
                    });
                    return;
                }

                if (png != null)
                {
                    var hash = HashBytes(png);
                    if (string.Equals(hash, _lastAppliedHash, StringComparison.OrdinalIgnoreCase) || string.Equals(hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return;
                    _lastSentHash = hash;
                    var base64 = Convert.ToBase64String(png);
                    _ = Task.Run(async () =>
                    {
                        var sent = await Server.SendClipboardImageAsync(base64, hash);
                        if (sent)
                        {
                            AddHistory(new HistoryEntry
                            {
                                Direction = "toPhone",
                                Kind = "image",
                                Preview = $"Изображение {SyncServer.FormatSize(png.Length)}",
                                Size = png.Length,
                                Status = "sent",
                            });
                            Log?.Invoke($"pushed image to phone ({png.Length} bytes)");
                        }
                        else
                        {
                            _lastSentHash = null;
                        }
                    });
                }
            }
            catch (Exception ex)
            {
                Log?.Invoke($"Clipboard sync error: {ex}");
            }
        });
    }

    // ---- public actions ----

    public string? SendFile(string path)
    {
        var id = Server.OfferFile(path);
        if (id == null) return null;
        var fi = new FileInfo(path);
        AddHistory(new HistoryEntry
        {
            Direction = "toPhone",
            Kind = "file",
            Preview = fi.Name,
            Size = fi.Length,
            Status = Server.ClientCount > 0 ? "sent" : "failed",
            FilePath = path,
        });
        if (Server.ClientCount == 0)
            StatusChanged?.Invoke("Файл в очереди — телефон не в сети");
        return id;
    }

    public void SendText(string text)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        _ = Task.Run(async () =>
        {
            var hash = HashText(text);
            _lastSentHash = hash;
            await Server.SendClipboardTextAsync(text, hash);
        });
    }

    public void RegeneratePairing()
    {
        Settings.Token = AppSettings.GenerateToken();
        Settings.Pin = AppSettings.GeneratePin();
        Settings.PairedDeviceId = null;
        Settings.PairedDeviceName = null;
        SettingsService.Save(Settings);
        Log?.Invoke("Pairing credentials regenerated");
        SettingsSaved?.Invoke();
    }

    public void SaveSettings()
    {
        SettingsService.Save(Settings);
        AutostartService.SetEnabled(Settings.StartWithWindows);
        SettingsSaved?.Invoke();
    }

    // ---- batch-aware auto-clean of received files ----
    // Files are uploaded one-by-one; cleaning must happen only when a NEW batch
    // starts, otherwise each file of a multi-file send would delete the previous one.
    private readonly object _batchLock = new();
    private string? _currentBatchId;
    private readonly HashSet<string> _currentBatchFiles = new(StringComparer.OrdinalIgnoreCase);
    private readonly Queue<(string Id, List<string> Files)> _keptBatches = new();

    private void TrackReceivedBatch(SyncServer.InboundFile file)
    {
        lock (_batchLock)
        {
            // No batch id (legacy/sim) => every file is its own batch.
            var isNewBatch = file.Batch == null || file.Batch != _currentBatchId;

            if (isNewBatch)
            {
                if (_currentBatchId != null)
                    _keptBatches.Enqueue((_currentBatchId, _currentBatchFiles.ToList()));

                _currentBatchId = file.Batch;
                _currentBatchFiles.Clear();

                // Keep at most KeepRecentBatches-1 previous batches alongside the current one.
                var keepPrev = Math.Max(0, Settings.KeepRecentBatches - 1);
                while (_keptBatches.Count > keepPrev)
                {
                    var old = _keptBatches.Dequeue();
                    foreach (var p in old.Files)
                    {
                        try { File.Delete(p); } catch { }
                    }
                }
            }

            _currentBatchFiles.Add(file.Path);

            if (!Settings.AutoCleanReceived || !isNewBatch) return;

            // Remove anything in the save folder that no kept batch owns
            // (files from previous runs, old batches beyond the keep limit).
            try
            {
                var owned = new HashSet<string>(
                    _currentBatchFiles.Concat(_keptBatches.SelectMany(b => b.Files)),
                    StringComparer.OrdinalIgnoreCase);

                var dir = Path.GetDirectoryName(file.Path);
                if (dir != null && Directory.Exists(dir))
                {
                    var removed = 0;
                    foreach (var f in Directory.GetFiles(dir))
                    {
                        if (owned.Contains(f)) continue;
                        try { File.Delete(f); removed++; } catch { }
                    }
                    if (removed > 0)
                        Log?.Invoke($"Auto-cleaned {removed} old file(s), kept batch {file.Batch ?? "-"}");
                }
            }
            catch (Exception ex)
            {
                Log?.Invoke($"Auto-clean error: {ex.Message}");
            }
        }
    }

    public void SetPaused(bool paused)
    {
        Server.IsPaused = paused;
        StatusChanged?.Invoke(paused ? "Синхронизация на паузе" : "Синхронизация активна");
    }

    /// <summary>Full stop/start of all sync (clipboard, files, notifs, media).</summary>
    public void SetSyncEnabled(bool enabled)
    {
        Settings.SyncEnabled = enabled;
        SettingsService.Save(Settings);
        Server.IsPaused = !enabled;

        if (enabled)
        {
            StatusChanged?.Invoke("Синхронизация запущена");
            // Restart clipboard watcher
            if (_watcher == null)
            {
                _watcher = new ClipboardWatcher();
                _watcher.ClipboardChanged += OnClipboardChanged;
                _watcher.Start();
            }
            // Restart Windows notif mirroring if enabled
            if (Settings.MirrorWinNotifs)
                _ = Task.Run(async () => await WinNotif.StartAsync());
        }
        else
        {
            StatusChanged?.Invoke("Синхронизация остановлена");
            // Stop clipboard watcher
            _watcher?.Dispose();
            _watcher = null;
            WinNotif.Stop();
            // Notify phone
            _ = Task.Run(async () =>
            {
                await Server.BroadcastStatusAsync(WsMessage.Status("stopped"));
            });
        }

        SettingsSaved?.Invoke();
        Log?.Invoke($"Sync {(enabled ? "enabled" : "disabled")}");
    }

    private void AddHistory(HistoryEntry e)
    {
        HistoryService.Add(e);
        HistoryAdded?.Invoke(e);
    }

    // ---- helpers ----

    public static string HashText(string text)
    {
        var bytes = SHA256.HashData(Encoding.UTF8.GetBytes(text));
        return Convert.ToHexString(bytes)[..16].ToLowerInvariant();
    }

    public static string HashBytes(byte[] data)
    {
        var bytes = SHA256.HashData(data);
        return Convert.ToHexString(bytes)[..16].ToLowerInvariant();
    }

    private static string Truncate(string s, int max)
        => s.Length <= max ? s : s[..max] + "…";

    public async ValueTask DisposeAsync()
    {
        _started = false;
        _watcher?.Dispose();
        await Server.DisposeAsync();
    }
}
