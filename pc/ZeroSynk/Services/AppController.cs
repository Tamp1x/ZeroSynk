using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using ZeroSynk.Models;

namespace ZeroSynk.Services;

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
    private string? _coverSentKey;      // track whose album art we already pushed

    public event Action<string>? StatusChanged;
    public event Action<string>? Log;
    public event Action<bool>? ClientPresence;      // true = phone connected
    public event Action<HistoryEntry>? HistoryAdded;
    public event Action<string, string, string?>? FileSaved; // (title, body, openPath) — raised ONCE per batch
    public event Action? SettingsSaved;
    public event Action<WsMessage>? NotifReceived;  // phone -> PC notification mirror
    public event Action<WsMessage>? PhoneMediaReceived; // phone -> PC "now playing" (reverse control)
    public event Action<int, bool>? PhoneBatteryChanged;
    public int? PhoneBatteryLevel { get; private set; }
    public bool PhoneCharging { get; private set; }

    public AppController(AppSettings settings)
    {
        Settings = settings;
        WinNotif = new WindowsNotifService(this);
        WinNotif.Log += m => Log?.Invoke(m);
        Log += WriteFileLog;
    }

    private static readonly object _logLock = new();

    public string ReadRecentLogs()
    {
        try
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk", "debug.log");
            if (!File.Exists(path)) return "Лог пока пуст";
            var lines = File.ReadAllLines(path);
            return string.Join(Environment.NewLine, lines.TakeLast(300));
        }
        catch (Exception ex) { return $"Не удалось прочитать лог: {ex.Message}"; }
    }

    private static void WriteFileLog(string msg)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk");
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
        Server.ProtocolWarning += warning => StatusChanged?.Invoke(warning);
        Server.ClientConnected += info =>
        {
            StatusChanged?.Invoke($"Телефон подключён: {info.Name}");
            ClientPresence?.Invoke(true);
            SendInitialClipboard(info.Id);
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
        Server.DesignReceived += msg => SetDesign(msg.Design);
        NotifReceived += msg => _ = WinNotif.ShowPhoneNotifAsync(msg);
        MediaService.StateChanged += snap =>
        {
            if (!Settings.MediaToPhone || !Settings.SyncEnabled)
            {
                _coverSentKey = null;
                _ = Server.BroadcastMediaAsync(WsMessage.Media("stopped", "", "", ""));
                return;
            }

            // Album art is sent once per track — the1/s position ticks stay tiny.
            var key = $"{snap.Title}|{snap.Artist}";
            string? cover = null;
            if (snap.Active && snap.Cover != null && key != _coverSentKey)
            {
                cover = snap.Cover;
                _coverSentKey = key;
            }
            if (!snap.Active) _coverSentKey = null;

            _ = Server.BroadcastMediaAsync(
            WsMessage.Media(
                snap.Active ? snap.State : "stopped",
                snap.Title,
                snap.Artist,
                snap.App,
                snap.Position,
                snap.Duration,
                cover));
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
            QueueFileNotification(file);
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
                        Text = msg.Text,
                        Size = Encoding.UTF8.GetByteCount(msg.Text),
                        Status = "received",
                    });
                    break;

                case "clipboard" when msg.Kind == "html" && !string.IsNullOrEmpty(msg.Text):
                    if (!Settings.SyncClipboardFromPhone) return;
                    if (msg.Hash != null && string.Equals(msg.Hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return;
                    ApplyHtmlToClipboard(msg.Text!, msg.Hash);
                    AddHistory(new HistoryEntry
                    {
                        Direction = "fromPhone",
                        Kind = "text",
                        Preview = Truncate(StripTags(msg.Text!), 120),
                        Text = msg.Text,
                        IsHtml = true,
                        Size = Encoding.UTF8.GetByteCount(msg.Text!),
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
                    _ = MediaService.ExecuteAsync(msg.Action!, msg.Position);
                    break;

                case "media" when msg.Origin == "phone":
                    // Reverse direction: what the phone is playing, for the PC's player card.
                    if (!Settings.ControlPhoneMedia || !Settings.SyncEnabled) return;
                    PhoneMediaReceived?.Invoke(msg);
                    break;

                case "notif" when msg.Origin == "phone" && Settings.MirrorPhoneNotifs && Settings.SyncEnabled:
                    Log?.Invoke($"notif from phone: {msg.AppName}/{msg.Title}");
                    RecordNotif(msg);
                    break;

                case "location" when !string.IsNullOrWhiteSpace(msg.Location):
                    OpenSharedLocation(msg.Location!);
                    break;

                case "phoneStatus" when msg.BatteryLevel is >= 0 and <= 100:
                    PhoneBatteryLevel = msg.BatteryLevel;
                    PhoneCharging = msg.Charging == true;
                    PhoneBatteryChanged?.Invoke(PhoneBatteryLevel.Value, PhoneCharging);
                    break;

                case "notifReply":
                    Log?.Invoke($"notif reply from phone for key {msg.Key}");
                    // Windows exposes no public API to inject a reply into the target
                    // app (p. 23) — put the answer on the clipboard and say so.
                    var reply = msg.Text?.Trim();
                    if (!string.IsNullOrEmpty(reply))
                    {
                        ApplyToClipboard(reply, null);
                        Notifier.ShowToast(
                            "Ответ с телефона",
                            $"«{Truncate(reply, 60)}» — скопирован в буфер обмена");
                    }
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

    private void OpenSharedLocation(string value)
    {
        // Accept only a geo URI or a maps URL received through Android's share sheet.
        var isGeo = value.StartsWith("geo:", StringComparison.OrdinalIgnoreCase);
        var trustedMapsUrl = Uri.TryCreate(value, UriKind.Absolute, out var parsed) &&
            parsed.Scheme == Uri.UriSchemeHttps &&
            new[] { "maps.google.com", "www.google.com", "maps.app.goo.gl" }
                .Contains(parsed.Host, StringComparer.OrdinalIgnoreCase);
        if (value.Length > 2048 || !(isGeo || trustedMapsUrl)) return;
        try
        {
            var target = isGeo
                ? "https://www.google.com/maps/search/?api=1&query=" + Uri.EscapeDataString(value[4..])
                : value;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(target) { UseShellExecute = true });
            StatusChanged?.Invoke("Местоположение открыто в браузере");
        }
        catch (Exception ex) { Log?.Invoke($"open shared location failed: {ex.Message}"); }
    }

    /// <summary>Control the phone's media player from the PC (reverse direction).</summary>
    public void ControlPhoneMedia(string action, long? position = null)
    {
        if (!Settings.ControlPhoneMedia || !Settings.SyncEnabled) return;
        Log?.Invoke($"media control to phone: {action}");
        _ = Server.BroadcastMediaControlAsync(action, position);
    }

    /// <summary>
    /// Initial clipboard sync (p. 11): on every connect push the recent clipboard stack
    /// (records only) and the current clipboard (applied on the phone). Android can't
    /// read the clipboard from a background service, so only this direction syncs.
    /// </summary>
    private void SendInitialClipboard(string clientId)
    {
        if (!Settings.SyncEnabled || !Settings.SyncClipboardToPhone) return;
        _ = System.Windows.Application.Current?.Dispatcher?.InvokeAsync(async () =>
        {
            try
            {
                var stack = ClipboardStack.Snapshot();
                if (stack.Count > 0)
                    await Server.SendToAsync(clientId, WsMessage.ClipboardStack(stack));

                var text = ClipboardReader.ReadText();
                if (string.IsNullOrEmpty(text)) return;
                var hash = HashText(text);
                // The phone already has it (it sent it, or we pushed it before).
                if (string.Equals(hash, _lastAppliedHash, StringComparison.OrdinalIgnoreCase) ||
                    string.Equals(hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return;
                _lastSentHash = hash;
                var sent = await Server.SendToAsync(clientId, WsMessage.ClipboardText(text, hash));
                if (!sent) _lastSentHash = null;
            }
            catch (Exception ex)
            {
                Log?.Invoke($"initial clipboard sync failed: {ex.Message}");
            }
        });
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

    /// <summary>Puts rich HTML from the phone on the clipboard (HTML + plain text slots).</summary>
    private void ApplyHtmlToClipboard(string html, string? hash)
    {
        _ = System.Windows.Application.Current?.Dispatcher?.InvokeAsync(() =>
        {
            Interlocked.Exchange(ref _sendingClipboard, 1);
            try
            {
                _lastAppliedHash = hash ?? HashText(html);
                var plain = StripTags(html);
                var data = new System.Windows.DataObject();
                data.SetData(System.Windows.DataFormats.UnicodeText, plain);
                data.SetData(System.Windows.DataFormats.Html, ClipboardReader.BuildCfHtml(html));
                System.Windows.Clipboard.SetDataObject(data, true);
            }
            catch { }
            finally
            {
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
                // 1) Files copied in Explorer (Ctrl+C) — optional, gated by SyncClipboardFiles.
                if (Settings.SyncClipboardFiles)
                {
                    var paths = ClipboardReader.ReadFilePaths();
                    if (paths != null)
                    {
                        foreach (var p in paths.Take(20))
                            _ = Task.Run(() => SendFile(p)); // SendFile zips folders too
                        Log?.Invoke($"pushed {paths.Count} clipboard file(s) to phone");
                        return;
                    }
                }

                // 2) Rich text: HTML when the source provides it, plain text otherwise.
                string? html = null;
                string? text = null;
                byte[]? png = null;
                try
                {
                    html = ClipboardReader.ReadHtml();
                    text = ClipboardReader.ReadText();
                    if (html == null && text == null && Settings.SyncImagesToPhone)
                        png = ClipboardReader.ReadImagePng();
                }
                catch (Exception rex)
                {
                    Log?.Invoke($"Clipboard read error: {rex.Message}");
                    return;
                }

                if (html != null || text != null)
                {
                    var content = html ?? text!;
                    var isHtml = html != null;
                    var hash = HashText(content);
                    if (string.Equals(hash, _lastAppliedHash, StringComparison.OrdinalIgnoreCase) || string.Equals(hash, _lastSentHash, StringComparison.OrdinalIgnoreCase)) return;
                    _lastSentHash = hash;
                    ClipboardStack.Push(isHtml ? "html" : "text", content, hash);
                    _ = Task.Run(async () =>
                    {
                        var sent = isHtml
                            ? await Server.SendClipboardHtmlAsync(html!, hash)
                            : await Server.SendClipboardTextAsync(text!, hash);
                        if (sent)
                        {
                            AddHistory(new HistoryEntry
                            {
                                Direction = "toPhone",
                                Kind = "text",
                                Preview = Truncate(isHtml ? StripTags(content) : content, 120),
                                Text = content,
                                IsHtml = isHtml,
                                Size = Encoding.UTF8.GetByteCount(content),
                                Status = "sent",
                            });
                            Log?.Invoke($"pushed {(isHtml ? "html" : "text")} to phone ({content.Length} chars)");
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
        // Folders are zipped on the fly (p. 13); the archive lives only until offered.
        string source = path;
        string? tempZip = null;
        string displayName;
        long size;

        if (Directory.Exists(path))
        {
            tempZip = ZipFolder(path);
            if (tempZip == null)
            {
                StatusChanged?.Invoke("Не удалось упаковать папку в zip");
                return null;
            }
            source = tempZip;
            var folderName = Path.GetFileName(path.TrimEnd('\\', '/'));
            displayName = (string.IsNullOrWhiteSpace(folderName) ? "folder" : folderName) + ".zip";
            size = new FileInfo(tempZip).Length;
        }
        else if (File.Exists(path))
        {
            displayName = Path.GetFileName(path);
            size = new FileInfo(path).Length;
        }
        else
        {
            return null;
        }

        var id = Server.OfferFile(source, displayName);
        if (tempZip != null)
        {
            try { File.Delete(tempZip); } catch { } // already copied into the outbox
        }
        if (id == null) return null;
        AddHistory(new HistoryEntry
        {
            Direction = "toPhone",
            Kind = "file",
            Preview = displayName,
            Size = size,
            Status = Server.ClientCount > 0 ? "sent" : "failed",
            FilePath = path,
        });
        if (Server.ClientCount == 0)
            StatusChanged?.Invoke("Файл в очереди — телефон не в сети");
        return id;
    }

    /// <summary>Packs a folder into a temp zip archive. Returns the archive path or null.</summary>
    private static string? ZipFolder(string folder)
    {
        try
        {
            var dir = Path.Combine(Path.GetTempPath(), "ZeroSynk");
            Directory.CreateDirectory(dir);
            var zip = Path.Combine(dir, $"zip-{Guid.NewGuid():N}.zip");
            System.IO.Compression.ZipFile.CreateFromDirectory(
                folder, zip, System.IO.Compression.CompressionLevel.Optimal, includeBaseDirectory: false);
            return zip;
        }
        catch
        {
            return null;
        }
    }

    public void SendText(string text, bool html = false)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        _ = Task.Run(async () =>
        {
            var hash = HashText(text);
            _lastSentHash = hash;
            var sent = html
                ? await Server.SendClipboardHtmlAsync(text, hash)
                : await Server.SendClipboardTextAsync(text, hash);
            if (!sent) _lastSentHash = null;
        });
    }

    /// <summary>Re-sends a history entry to the phone (p. 17). Text goes as clipboard, files via SendFile.</summary>
    public void RepeatFromHistory(HistoryEntry e)
    {
        switch (e.Kind)
        {
            case "text" when !string.IsNullOrEmpty(e.Text):
                SendText(e.Text!, e.IsHtml);
                StatusChanged?.Invoke("Текст отправлен повторно");
                break;
            case "file" when !string.IsNullOrEmpty(e.FilePath):
                if (SendFile(e.FilePath!) == null)
                    StatusChanged?.Invoke("Файл недоступен — повтор не удался");
                break;
            case "image":
                StatusChanged?.Invoke("Картинки повторно не отправляются — скопируйте заново");
                break;
            default:
                StatusChanged?.Invoke("Повтор недоступен для этой записи");
                break;
        }
    }

    /// <summary>Puts a history entry back on the PC clipboard (tap on a received row).</summary>
    public void CopyFromHistory(HistoryEntry e)
    {
        if (string.IsNullOrEmpty(e.Text)) return;
        if (e.IsHtml) ApplyHtmlToClipboard(e.Text, null);
        else ApplyToClipboard(e.Text, null);
        StatusChanged?.Invoke("Скопировано в буфер обмена");
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

    /// <summary>Applies a design skin (from the settings UI or from the phone),
    /// persists it and broadcasts it so all connected devices switch together.</summary>
    public void SetDesign(string? design)
    {
        var d = DesignService.Normalize(design);
        if (Settings.Design == d) return;

        DesignService.Apply(d);
        SaveSettings();
        _ = Server.BroadcastStatusAsync(WsMessage.DesignChanged(d));
        Log?.Invoke($"Design applied: {d}");
    }

    // ---- batch-aware auto-clean of received files ----
    // Files are uploaded one-by-one; cleaning must happen only when a NEW batch
    // starts, otherwise each file of a multi-file send would delete the previous one.
    private readonly object _batchLock = new();
    private string? _currentBatchId;
    private readonly HashSet<string> _currentBatchFiles = new(StringComparer.OrdinalIgnoreCase);
    private readonly Queue<(string Id, List<string> Files)> _keptBatches = new();

    // Full paths we have ever received (persisted) — auto-clean must never delete
    // anything else: the save folder may contain the user's own files.
    private HashSet<string>? _receivedFiles;
    private static string ReceivedManifestPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk", "received-files.txt");

    // Batched "file received" notifications: ONE balloon + ONE sound per batch.
    private sealed class NotifBatch
    {
        public readonly List<SyncServer.InboundFile> Files = new();
        public int? Total;
        public System.Threading.Timer? Timer;
    }

    private readonly object _notifLock = new();
    private readonly Dictionary<string, NotifBatch> _pendingNotifs = new();

    private void QueueFileNotification(SyncServer.InboundFile file)
    {
        // No batch id or no total (legacy/sim/single) => notify immediately.
        if (file.Batch == null || file.Total == null)
        {
            NotifyFilesReceived(new[] { file });
            return;
        }
        NotifBatch? flushNow = null;
        lock (_notifLock)
        {
            if (!_pendingNotifs.TryGetValue(file.Batch, out var entry))
            {
                entry = new NotifBatch { Total = file.Total };
                _pendingNotifs[file.Batch] = entry;
            }
            entry.Files.Add(file);
            entry.Timer?.Dispose();
            entry.Timer = null;
            if (entry.Files.Count >= entry.Total.Value)
            {
                _pendingNotifs.Remove(file.Batch);
                flushNow = entry;
            }
            else
            {
                // Fallback: flush what we have if the rest of the batch never arrives.
                entry.Timer = new System.Threading.Timer(
                    _ => FlushNotifBatch(file.Batch!), null, 5000, System.Threading.Timeout.Infinite);
            }
        }
        if (flushNow != null) NotifyFilesReceived(flushNow.Files);
    }

    private void FlushNotifBatch(string batch)
    {
        NotifBatch? entry;
        lock (_notifLock)
        {
            if (!_pendingNotifs.TryGetValue(batch, out entry)) return;
            _pendingNotifs.Remove(batch);
            entry.Timer?.Dispose();
        }
        NotifyFilesReceived(entry.Files);
    }

    private void NotifyFilesReceived(IReadOnlyList<SyncServer.InboundFile> files)
    {
        var totalSize = files.Sum(f => f.Size);
        var title = files.Count == 1 ? "Файл получен" : "Получены файлы";
        var body = files.Count == 1
            ? $"{files[0].Name} — {SyncServer.FormatSize(totalSize)}"
            : $"{files.Count} файлов — {SyncServer.FormatSize(totalSize)}";
        // One file → open it by type; several → reveal the save folder (p. 16).
        string? openPath = files.Count == 1 ? files[0].Path : Settings.SaveFolder;
        FileSaved?.Invoke(title, body, openPath);
        if (Settings.PlaySound)
            try { System.Media.SystemSounds.Exclamation.Play(); } catch { }
    }

    private void TrackReceivedBatch(SyncServer.InboundFile file)
    {
        lock (_batchLock)
        {
            _receivedFiles ??= LoadReceivedManifest();
            var received = _receivedFiles;
            var manifestDirty = received.Add(file.Path);

            // No batch id (legacy/sim) => every file is its own batch.
            var isNewBatch = file.Batch == null || file.Batch != _currentBatchId;

            if (isNewBatch)
            {
                if (_currentBatchId != null)
                    _keptBatches.Enqueue((_currentBatchId, _currentBatchFiles.ToList()));

                _currentBatchId = file.Batch;
                _currentBatchFiles.Clear();

                // Keep at most KeepRecentBatches-1 previous batches alongside the current
                // one — but only while auto-clean is on (otherwise keep everything).
                if (Settings.AutoCleanReceived)
                {
                    var keepPrev = Math.Max(0, Settings.KeepRecentBatches - 1);
                    while (_keptBatches.Count > keepPrev)
                    {
                        var old = _keptBatches.Dequeue();
                        foreach (var p in old.Files)
                        {
                            try { File.Delete(p); } catch { }
                            manifestDirty |= received.Remove(p);
                        }
                    }
                }
            }

            _currentBatchFiles.Add(file.Path);

            if (Settings.AutoCleanReceived && isNewBatch)
            {
                // Remove only files WE received (previous runs, batches beyond the keep
                // limit). Anything not in the manifest is foreign and is never touched.
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
                            if (owned.Contains(f) || !received.Contains(f)) continue;
                            try
                            {
                                File.Delete(f);
                                received.Remove(f);
                                removed++;
                                manifestDirty = true;
                            }
                            catch { }
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

            if (manifestDirty)
                SaveReceivedManifest(received);
        }
    }

    private static HashSet<string> LoadReceivedManifest()
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        try
        {
            if (File.Exists(ReceivedManifestPath))
                foreach (var line in File.ReadAllLines(ReceivedManifestPath))
                    if (!string.IsNullOrWhiteSpace(line))
                        set.Add(line.Trim());
        }
        catch { }
        return set;
    }

    private static void SaveReceivedManifest(HashSet<string> files)
    {
        try
        {
            files.RemoveWhere(p => !File.Exists(p)); // user deleted it manually — forget
            Directory.CreateDirectory(Path.GetDirectoryName(ReceivedManifestPath)!);
            File.WriteAllLines(ReceivedManifestPath, files);
        }
        catch { }
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
            // Tell the phone sync is back: it may be showing "not connected" from
            // the "stopped" we sent earlier (its socket stayed open all along).
            _ = Task.Run(async () =>
            {
                await Server.BroadcastStatusAsync(WsMessage.Status("started"));
            });
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

    /// <summary>Persists an incoming notification (p. 20) and raises NotifReceived
    /// so the page shows it and the tray toast fires.</summary>
    public void RecordNotif(WsMessage msg)
    {
        NotifHistory.Add(msg);
        NotifReceived?.Invoke(msg);
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

    /// <summary>Crude HTML → plain text (for the clipboard's text slot and history preview).</summary>
    private static string StripTags(string html)
    {
        try
        {
            var noTags = Regex.Replace(html, "<[^>]*>", " ");
            var text = System.Net.WebUtility.HtmlDecode(noTags);
            return Regex.Replace(text, @"\s+", " ").Trim();
        }
        catch
        {
            return html;
        }
    }

    public async ValueTask DisposeAsync()
    {
        _started = false;
        _watcher?.Dispose();
        await Server.DisposeAsync();
    }
}
