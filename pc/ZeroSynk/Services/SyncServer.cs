using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Web;
using ZeroSynk.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace ZeroSynk.Services;

/// <summary>
/// Kestrel HTTP + WebSocket server. Phone connects as WS client; token auth on every request.
/// Endpoints:
///   WS   /ws?token=...                      — realtime channel
///   POST /api/pair                          — {pin} exchange for token (rate-limited)
///   POST /api/upload?token=...&name=...     — phone -> PC file upload (chunked body)
///   GET  /api/file/{id}?token=...           — PC -> phone file download (from outbox)
///   GET  /api/ping                          — liveness
/// </summary>
public sealed class SyncServer : IAsyncDisposable
{
    private WebApplication? _app;
    private Task? _runTask;

    private readonly ConcurrentDictionary<string, ClientConn> _clients = new();
    private readonly ConcurrentDictionary<string, OutboxFile> _outbox = new();
    private readonly ConcurrentQueue<(DateTime At, string Ip)> _pairAttempts = new();
    private System.Threading.Timer? _outboxSweep;
    private static readonly TimeSpan OutboxTtl = TimeSpan.FromHours(24);

    private volatile bool _paused;
    private WsMessage? _mediaState;
    private string? _mediaCover; // last album art seen — for phones connecting mid-track

    public event Action<string>? Log;
    public event Action<ClientInfo>? ClientConnected;
    public event Action<string>? ClientDisconnected;
    public event Action<WsMessage>? MessageReceived;   // from phone (clipboard, pong, fileComplete)
    public event Action<WsMessage>? DesignReceived;    // from phone (design skin change)
    public event Action<InboundFile>? FileReceived;
    public event Action<WsMessage, string>? ServerInitiated; // internal: PC -> phone pushes
    public event Action<string>? ProtocolWarning;      // phone reported an incompatible proto version

    public bool IsRunning => _app != null;
    public bool IsPaused { get => _paused; set => _paused = value; }
    public int BoundPort { get; private set; }

    public sealed record ClientInfo(string Id, string Name, string RemoteIp);
    public sealed record OutboxFile(string Id, string Name, string Path, long Size, DateTime CreatedAt, string? Hash = null);
    public sealed record InboundFile(string Name, string Path, long Size, string? Batch, int? Total);

    private sealed class ClientConn
    {
        public required System.Net.WebSockets.WebSocket Socket { get; init; }
        public required string Id { get; init; }
        public string Name { get; set; } = "unknown";
        public string RemoteIp { get; set; } = "";
        public SemaphoreSlim SendLock { get; } = new(1, 1);
        public DateTime LastSeen { get; set; } = DateTime.UtcNow;
    }

    public async Task StartAsync(AppSettings settings)
    {
        if (_app != null) return;

        var builder = WebApplication.CreateSlimBuilder();
        builder.Logging.ClearProviders();
        builder.Logging.AddSimpleConsole(o =>
        {
            o.SingleLine = true;
            o.TimestampFormat = "HH:mm:ss ";
        });
        builder.Logging.SetMinimumLevel(LogLevel.Warning);
        builder.WebHost.UseUrls($"http://0.0.0.0:{settings.Port}");
        builder.WebHost.ConfigureKestrel(o =>
        {
            o.Limits.MaxRequestBodySize = null; // files of any size
            o.Limits.KeepAliveTimeout = TimeSpan.FromMinutes(10);
            o.Limits.RequestHeadersTimeout = TimeSpan.FromSeconds(30);
        });

        var app = builder.Build();
        app.UseWebSockets(new WebSocketOptions
        {
            KeepAliveInterval = TimeSpan.FromSeconds(30),
        });

        var settingsRef = settings;

        app.Map("/api/ping", (HttpContext ctx) => Results.Json(new
        {
            ok = true,
            pc = settingsRef.PcName,
            proto = Protocol.Version,
            ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
        }, Protocol.Json));

        app.Map("/api/pair", async (HttpContext ctx) =>
        {
            if (!ctx.Request.Method.Equals(HttpMethods.Post, StringComparison.OrdinalIgnoreCase))
                return Results.StatusCode(405);

            var ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "?";
            if (!CheckRateLimit(ip))
                return Results.Json(new { ok = false, error = "too_many_attempts" }, Protocol.Json, statusCode: 429);

            using var reader = new StreamReader(ctx.Request.Body, Encoding.UTF8);
            var body = await reader.ReadToEndAsync();
            string? pin = null;
            try
            {
                var doc = System.Text.Json.JsonDocument.Parse(body);
                if (doc.RootElement.TryGetProperty("pin", out var p)) pin = p.GetString();
            }
            catch { }

            if (pin == null || !CryptographicOperations.FixedTimeEquals(
                    Encoding.UTF8.GetBytes(pin.Trim()),
                    Encoding.UTF8.GetBytes(settingsRef.Pin)))
            {
                Log?.Invoke($"Pair attempt rejected from {ip} (bad PIN)");
                return Results.Json(new { ok = false, error = "bad_pin" }, Protocol.Json, statusCode: 403);
            }

            // success — issue the long-lived token (and the session password, if one is set)
            Log?.Invoke($"Paired with device at {ip}");
            return Results.Json(new
            {
                ok = true,
                token = settingsRef.Token,
                pcName = settingsRef.PcName,
                pw = string.IsNullOrEmpty(settingsRef.TransferPassword) ? null : settingsRef.TransferPassword,
            }, Protocol.Json);
        });

        app.Map("/api/upload", async (HttpContext ctx) =>
        {
            if (!ctx.Request.Method.Equals(HttpMethods.Post, StringComparison.OrdinalIgnoreCase))
                return Results.StatusCode(405);

            if (!Authorize(ctx, settingsRef))
                return Results.Json(new { ok = false, error = "unauthorized" }, Protocol.Json, statusCode: 401);

            var name = ctx.Request.Query["name"].FirstOrDefault() ?? "file.bin";
            name = SanitizeFileName(name);
            var batch = ctx.Request.Query["batch"].FirstOrDefault();
            var totalRaw = ctx.Request.Query["total"].FirstOrDefault();
            int? total = int.TryParse(totalRaw, out var t) && t > 0 ? t : null;
            var hash = ctx.Request.Query["hash"].FirstOrDefault();

            // Dedupe (p. 15): the phone hashes the file before uploading — when we've
            // seen the same full SHA-256 in the last 24 h, drop the bytes on the floor.
            if (!string.IsNullOrEmpty(hash) && WasReceivedRecently(hash))
            {
                try { await ctx.Request.Body.CopyToAsync(Stream.Null, ctx.RequestAborted); }
                catch { }
                Log?.Invoke($"Skipped duplicate upload: {name} (sha256 match)");
                return Results.Json(new { ok = true, dedupe = true, name, hash }, Protocol.Json);
            }

            var folder = settingsRef.SaveFolder;
            Directory.CreateDirectory(folder);

            var dest = UniquePath(folder, name);
            long size = 0;
            await using (var fs = File.Create(dest))
            {
                var buffer = new byte[81920];
                int read;
                while ((read = await ctx.Request.Body.ReadAsync(buffer)) > 0)
                {
                    await fs.WriteAsync(buffer.AsMemory(0, read));
                    size += read;
                }
            }

            if (!string.IsNullOrEmpty(hash)) RememberReceived(hash!);
            Log?.Invoke($"Received file: {name} ({FormatSize(size)})");
            FileReceived?.Invoke(new InboundFile(name, dest, size, batch, total));
            return Results.Json(new { ok = true, name = Path.GetFileName(dest), size }, Protocol.Json);
        });

        app.MapGet("/api/file/{id}", async (HttpContext ctx, string id) =>
        {
            if (!Authorize(ctx, settingsRef))
                return Results.Json(new { ok = false, error = "unauthorized" }, Protocol.Json, statusCode: 401);

            if (!_outbox.TryGetValue(id, out var f) || !File.Exists(f.Path))
                return Results.Json(new { ok = false, error = "not_found" }, Protocol.Json, statusCode: 404);

            Log?.Invoke($"Phone downloading: {f.Name} ({FormatSize(f.Size)})");

            // Drop the staged copy only after the response fully reached the phone:
            // an aborted transfer keeps the entry, so the next hello re-offers it
            // (and the TTL sweep expires it if the phone never comes back).
            ctx.Response.OnCompleted(() =>
            {
                try
                {
                    if (ctx.Response.StatusCode == 200 && !ctx.RequestAborted.IsCancellationRequested)
                    {
                        _outbox.TryRemove(id, out _);
                        try { File.Delete(f.Path); } catch { }
                        Log?.Invoke($"Phone downloaded: {f.Name} ({FormatSize(f.Size)})");
                    }
                }
                catch { }
                return Task.CompletedTask;
            });

            // enableRangeProcessing lets the phone resume a partial download with
            // "Range: bytes=N-" (p. 14) — a .part file continues where it stopped.
            return Results.File(f.Path, "application/octet-stream", f.Name, enableRangeProcessing: true);
        });

        // File manager from the phone (p. 12): folder listing + raw file streaming.
        app.MapGet("/api/list", async (HttpContext ctx) =>
        {
            if (!Authorize(ctx, settingsRef))
                return Results.Json(new { ok = false, error = "unauthorized" }, Protocol.Json, statusCode: 401);

            var raw = ctx.Request.Query["path"].FirstOrDefault();
            string dir;
            try
            {
                dir = string.IsNullOrWhiteSpace(raw)
                    ? Environment.GetFolderPath(Environment.SpecialFolder.UserProfile)
                    : Path.GetFullPath(raw);
            }
            catch
            {
                return Results.Json(new { ok = false, error = "bad_path" }, Protocol.Json, statusCode: 400);
            }
            if (!Directory.Exists(dir))
                return Results.Json(new { ok = false, error = "not_found" }, Protocol.Json, statusCode: 404);

            string[] subDirs;
            string[] files;
            try
            {
                subDirs = Directory.GetDirectories(dir);
                files = Directory.GetFiles(dir);
            }
            catch (Exception ex)
            {
                return Results.Json(new { ok = false, error = "denied", message = ex.Message }, Protocol.Json, statusCode: 403);
            }

            var dirs = subDirs
                .Select(Path.GetFileName)
                .Where(n => !string.IsNullOrEmpty(n))
                .OrderBy(n => n, StringComparer.OrdinalIgnoreCase)
                .ToList();
            var entries = files
                .Select(p =>
                {
                    var fi = new FileInfo(p);
                    return new
                    {
                        name = fi.Name,
                        size = fi.Length,
                        mtime = new DateTimeOffset(fi.LastWriteTimeUtc, TimeSpan.Zero).ToUnixTimeSeconds(),
                    };
                })
                .OrderBy(f => f.name, StringComparer.OrdinalIgnoreCase)
                .ToList();

            var parent = Directory.GetParent(dir)?.FullName;
            return Results.Json(new { ok = true, path = dir, parent, dirs, files = entries }, Protocol.Json);
        });

        app.MapGet("/api/raw", async (HttpContext ctx) =>
        {
            if (!Authorize(ctx, settingsRef))
                return Results.Json(new { ok = false, error = "unauthorized" }, Protocol.Json, statusCode: 401);

            var raw = ctx.Request.Query["path"].FirstOrDefault();
            if (string.IsNullOrWhiteSpace(raw))
                return Results.Json(new { ok = false, error = "bad_path" }, Protocol.Json, statusCode: 400);

            string file;
            try { file = Path.GetFullPath(raw); }
            catch
            {
                return Results.Json(new { ok = false, error = "bad_path" }, Protocol.Json, statusCode: 400);
            }
            if (!File.Exists(file))
                return Results.Json(new { ok = false, error = "not_found" }, Protocol.Json, statusCode: 404);

            var provider = new Microsoft.AspNetCore.StaticFiles.FileExtensionContentTypeProvider();
            if (!provider.TryGetContentType(file, out var contentType))
                contentType = "application/octet-stream";

            Log?.Invoke($"Phone opened: {Path.GetFileName(file)}");
            // Range enabled so a viewer can seek inside long videos/audio (p. 12).
            return Results.File(file, contentType, null, enableRangeProcessing: true);
        });

        app.Map("/ws", async (HttpContext ctx) =>
        {
            if (!ctx.WebSockets.IsWebSocketRequest)
            {
                ctx.Response.StatusCode = 400;
                return;
            }

            var token = ctx.Request.Query["token"].FirstOrDefault();
            var hasToken = !string.IsNullOrEmpty(token) &&
                CryptographicOperations.FixedTimeEquals(
                    Encoding.UTF8.GetBytes(token ?? ""),
                    Encoding.UTF8.GetBytes(settingsRef.Token));
            if (!hasToken)
            {
                Log?.Invoke($"WS rejected (bad token) from {ctx.Connection.RemoteIpAddress}");
                ctx.Response.StatusCode = 401;
                return;
            }
            if (!PasswordOk(GetPw(ctx), settingsRef))
            {
                Log?.Invoke($"WS rejected (bad password) from {ctx.Connection.RemoteIpAddress}");
                ctx.Response.StatusCode = 401;
                return;
            }

            using var socket = await ctx.WebSockets.AcceptWebSocketAsync();
            var conn = new ClientConn
            {
                Socket = socket,
                Id = Guid.NewGuid().ToString("N")[..12],
                RemoteIp = ctx.Connection.RemoteIpAddress?.ToString() ?? "?",
            };
            _clients[conn.Id] = conn;
            Log?.Invoke($"WS connected: {conn.RemoteIp} ({conn.Id})");

            try
            {
                await ReceiveLoop(conn, settingsRef);
            }
            catch (Exception ex)
            {
                Log?.Invoke($"WS error {conn.Id}: {ex.Message}");
            }
            finally
            {
                _clients.TryRemove(conn.Id, out _);
                Log?.Invoke($"WS disconnected: {conn.Id}");
                ClientDisconnected?.Invoke(conn.Id);
            }
        });

        _app = app;
        BoundPort = settings.Port;
        RestoreOutbox();
        _outboxSweep = new System.Threading.Timer(
            _ => SweepOutbox(), null, TimeSpan.FromMinutes(15), TimeSpan.FromMinutes(15));
        _runTask = app.RunAsync();
        await Task.Delay(400); // let Kestrel bind; surface port-in-use quickly
        if (_runTask.IsFaulted)
        {
            var ex = _runTask.Exception?.GetBaseException();
            _app = null;
            throw new IOException($"Не удалось запустить сервер на порту {settings.Port}: {ex?.Message}", ex);
        }
        Log?.Invoke($"Server listening on :{settings.Port}");
    }

    private async Task ReceiveLoop(ClientConn conn, AppSettings settings)
    {
        var buffer = new byte[64 * 1024];
        using var ms = new MemoryStream();

        while (conn.Socket.State == System.Net.WebSockets.WebSocketState.Open)
        {
            ms.SetLength(0);
            System.Net.WebSockets.WebSocketReceiveResult result;
            do
            {
                result = await conn.Socket.ReceiveAsync(buffer, CancellationToken.None);
                if (result.MessageType == System.Net.WebSockets.WebSocketMessageType.Close)
                    return;
                ms.Write(buffer, 0, result.Count);
            } while (!result.EndOfMessage);

            if (ms.Length > 8 * 1024 * 1024)
            {
                Log?.Invoke($"Message too large from {conn.Id}, closing");
                await conn.Socket.CloseAsync(System.Net.WebSockets.WebSocketCloseStatus.MessageTooBig, "too_big", CancellationToken.None);
                return;
            }

            var text = Encoding.UTF8.GetString(ms.GetBuffer(), 0, (int)ms.Length);
            var msg = Protocol.Parse(text);
            if (msg == null) continue;

            conn.LastSeen = DateTime.UtcNow;

            switch (msg.Type)
            {
                case "hello":
                    conn.Name = msg.DeviceName ?? "unknown";
                    settings.PairedDeviceName = conn.Name;
                    settings.PairedDeviceId = msg.DeviceId ?? conn.Id;
                    settings.LastSeen = DateTime.Now;
                    SettingsService.Save(settings);

                    // Protocol compatibility: the phone declares its proto version in hello.
                    if (msg.Proto is int phoneProto && phoneProto != Protocol.Version)
                    {
                        Log?.Invoke($"Protocol mismatch: {conn.Name} speaks v{phoneProto}, server v{Protocol.Version}");
                        ProtocolWarning?.Invoke(
                            $"{conn.Name} использует протокол v{phoneProto}, ПК — v{Protocol.Version}. Обновите приложения на обоих устройствах.");
                    }
                    else if (msg.Proto == null)
                    {
                        Log?.Invoke($"hello without proto from {conn.Name} (older client?)");
                    }

                    await SendAsync(conn, new WsMessage
                    {
                        Type = "welcome",
                        Ok = true,
                        PcName = settings.PcName,
                        DeviceName = conn.Name,
                        Design = settings.Design,
                        Proto = Protocol.Version,
                    });
                    if (_mediaState != null)
                        await SendAsync(conn, WithCachedCover(_mediaState));
                    // Sync is off on the PC — say so right away, otherwise a phone that
                    // reconnects while we're paused would show "connected" until the
                    // next SetSyncEnabled edge.
                    if (_paused)
                        await SendAsync(conn, WsMessage.Status("stopped"));

                    ClientConnected?.Invoke(new ClientInfo(conn.Id, conn.Name, conn.RemoteIp));

                    // Offline delivery: re-offer everything the phone hasn't downloaded yet.
                    foreach (var f in _outbox.Values)
                    {
                        if (!File.Exists(f.Path)) { _outbox.TryRemove(f.Id, out _); continue; }
                        await SendAsync(conn, WsMessage.FileOffer(f.Id, f.Name, f.Size, f.Hash));
                    }
                    if (!_outbox.IsEmpty)
                        Log?.Invoke($"Re-offered {_outbox.Count} pending file(s) to {conn.Name}");
                    break;

                case "ping":
                    await SendAsync(conn, new WsMessage { Type = "pong", Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
                    break;

                case "pong":
                    break;

                case "fileComplete":
                    // The phone confirms a finished download: drop the staged copy so it
                    // isn't re-offered (also covers resumed Range downloads, p. 14).
                    ClearOutbox(msg.FileId, msg.FileName);
                    break;

                case "design":
                    // Design switches must apply even while sync is paused.
                    DesignReceived?.Invoke(msg);
                    break;

                default:
                    if (_paused)
                    {
                        Log?.Invoke($"Paused — dropping {msg.Type}");
                        break;
                    }
                    MessageReceived?.Invoke(msg);
                    break;
            }
        }
    }

    // ---- PC -> phone API ----

    /// <summary>Sends a message to one specific client (used for initial sync on connect).</summary>
    public async Task<bool> SendToAsync(string clientId, WsMessage msg)
    {
        if (!_clients.TryGetValue(clientId, out var conn)) return false;
        return await SendAsync(conn, msg);
    }

    public async Task<bool> SendClipboardTextAsync(string text, string hash)
    {
        if (_paused) return false;
        return await BroadcastAsync(WsMessage.ClipboardText(text, hash));
    }

    public async Task<bool> SendClipboardHtmlAsync(string html, string hash)
    {
        if (_paused) return false;
        return await BroadcastAsync(WsMessage.ClipboardHtml(html, hash));
    }

    /// <summary>Recent clipboard texts for a freshly connected phone (history seed).</summary>
    public async Task<bool> SendClipboardStackAsync(List<ClipboardItem> items)
    {
        if (_paused || items.Count == 0) return false;
        return await BroadcastAsync(WsMessage.ClipboardStack(items));
    }

    public async Task<bool> SendClipboardImageAsync(string base64Png, string hash)
    {
        if (_paused) return false;
        return await BroadcastAsync(WsMessage.ClipboardImage(base64Png, hash));
    }

    /// <summary>Broadcast the current media session state (remembered for late joiners).</summary>
    public async Task BroadcastMediaAsync(WsMessage msg)
    {
        // Album art only rides along on track-change messages (position ticks are 1/s);
        // remember it so a phone connecting mid-track still gets the cover.
        if (msg.Cover != null) _mediaCover = msg.Cover;
        else if (msg.State == "stopped") _mediaCover = null;
        _mediaState = msg;
        if (_paused) return;
        await BroadcastAsync(msg);
    }

    /// <summary>Media control command to the phone (PC -> phone reverse control).</summary>
    public async Task BroadcastMediaControlAsync(string action, long? position = null)
    {
        if (_paused) return;
        await BroadcastAsync(WsMessage.MediaControl(action, position));
    }

    /// <summary>Cached media state with the remembered cover re-attached (does not mutate).</summary>
    private WsMessage WithCachedCover(WsMessage m)
    {
        if (m.Cover != null || _mediaCover == null) return m;
        return new WsMessage
        {
            Type = m.Type,
            State = m.State,
            Title = m.Title,
            Artist = m.Artist,
            App = m.App,
            Position = m.Position,
            Duration = m.Duration,
            Cover = _mediaCover,
            Ts = m.Ts,
        };
    }

    /// <summary>Broadcast a message to all connected clients (bypasses pause for status messages).</summary>
    public Task BroadcastStatusAsync(WsMessage msg) => BroadcastAsync(msg);

    /// <summary>Broadcast a notification (respects pause — not sent when sync stopped).</summary>
    public async Task<bool> BroadcastNotifAsync(WsMessage msg)
    {
        if (_paused) return false;
        return await BroadcastAsync(msg);
    }

    /// <summary>Stage a file for the phone and offer it over WS. Returns file id.
    /// [nameOverride] — display name on the wire (zip archives of folders).
    /// The offer (with the file's full SHA-256) goes out once hashing finishes off-thread.</summary>
    public string? OfferFile(string sourcePath, string? nameOverride = null)
    {
        if (!File.Exists(sourcePath)) return null;
        var name = nameOverride ?? Path.GetFileName(sourcePath);
        Directory.CreateDirectory(settingsOutbox);
        var staged = UniquePath(settingsOutbox, name);
        File.Copy(sourcePath, staged, true);
        var id = Guid.NewGuid().ToString("N")[..16];
        var fi = new FileInfo(staged);
        _outbox[id] = new OutboxFile(id, name, staged, fi.Length, DateTime.UtcNow);

        _ = Task.Run(() =>
        {
            var hash = ComputeSha256(staged);
            if (!_outbox.TryGetValue(id, out var cur) || cur.Path != staged) return; // swept meanwhile
            _outbox[id] = cur with { Hash = hash.Length > 0 ? hash : null };
            _ = BroadcastAsync(WsMessage.FileOffer(id, name, fi.Length, hash.Length > 0 ? hash : null));
            Log?.Invoke($"Offered file: {name} ({FormatSize(fi.Length)})");
        });
        return id;
    }

    /// <summary>Full SHA-256 of a file as lowercase hex (never throws — "" on failure).</summary>
    public static string ComputeSha256(string path)
    {
        try
        {
            using var fs = File.OpenRead(path);
            using var sha = SHA256.Create();
            return Convert.ToHexString(sha.ComputeHash(fs)).ToLowerInvariant();
        }
        catch
        {
            return "";
        }
    }

    // ---- received-file dedupe manifest (p. 15, 24 h window) ----

    private readonly ConcurrentDictionary<string, DateTime> _receivedHashes = new();

    private void RememberReceived(string hash)
        => _receivedHashes[hash.ToLowerInvariant()] = DateTime.UtcNow;

    private bool WasReceivedRecently(string hash)
        => _receivedHashes.TryGetValue(hash.ToLowerInvariant(), out var at) &&
           DateTime.UtcNow - at < TimeSpan.FromHours(24);

    private void SweepReceivedHashes()
    {
        var cutoff = DateTime.UtcNow - TimeSpan.FromHours(24);
        foreach (var kv in _receivedHashes)
            if (kv.Value < cutoff) _receivedHashes.TryRemove(kv.Key, out _);
    }

    private string settingsOutbox = Path.Combine(Path.GetTempPath(), "ZeroSynk", "outbox");

    /// <summary>
    /// The outbox dict dies with the process but its files don't: re-adopt everything
    /// staged by a previous run (younger than the TTL) so the next hello re-offers it,
    /// and drop anything older — %TEMP% would otherwise grow forever.
    /// </summary>
    private void RestoreOutbox()
    {
        try
        {
            if (!Directory.Exists(settingsOutbox)) return;
            var restored = 0;
            foreach (var path in Directory.GetFiles(settingsOutbox))
            {
                try
                {
                    var fi = new FileInfo(path);
                    if (DateTime.UtcNow - fi.CreationTimeUtc > OutboxTtl)
                    {
                        File.Delete(path);
                        continue;
                    }
                    var id = Guid.NewGuid().ToString("N")[..16];
                    if (_outbox.TryAdd(id, new OutboxFile(id, fi.Name, path, fi.Length, fi.CreationTimeUtc)))
                    {
                        restored++;
                        var restoredId = id;
                        // Hash in the background so the re-offer carries the SHA-256 too.
                        _ = Task.Run(() =>
                        {
                            var h = ComputeSha256(path);
                            if (h.Length > 0 && _outbox.TryGetValue(restoredId, out var cur))
                                _outbox[restoredId] = cur with { Hash = h };
                        });
                    }
                }
                catch { }
            }
            if (restored > 0)
                Log?.Invoke($"Outbox: restored {restored} undelivered file(s) from previous run");
        }
        catch { }
    }

    /// <summary>Drops an outbox entry once the phone confirms it has the file (by id, falling back to name).</summary>
    private void ClearOutbox(string? fileId, string? fileName)
    {
        OutboxFile? done = null;
        if (!string.IsNullOrEmpty(fileId))
        {
            _outbox.TryRemove(fileId!, out done);
        }
        else if (!string.IsNullOrEmpty(fileName))
        {
            foreach (var kv in _outbox)
            {
                if (!string.Equals(kv.Value.Name, fileName, StringComparison.OrdinalIgnoreCase)) continue;
                if (_outbox.TryRemove(kv.Key, out done)) break;
            }
        }
        if (done == null) return;
        try { File.Delete(done.Path); } catch { }
        Log?.Invoke($"Phone confirmed download: {done.Name} — outbox cleared");
    }

    /// <summary>Expires undelivered offers older than the TTL (runs every 15 minutes).</summary>
    private void SweepOutbox()
    {
        SweepReceivedHashes();
        var cutoff = DateTime.UtcNow - OutboxTtl;
        foreach (var kv in _outbox)
        {
            if (kv.Value.CreatedAt > cutoff) continue;
            if (!_outbox.TryRemove(kv.Key, out _)) continue;
            try { File.Delete(kv.Value.Path); } catch { }
            Log?.Invoke($"Outbox TTL expired: {kv.Value.Name}");
        }
    }

    public int ClientCount => _clients.Count;
    public IEnumerable<ClientInfo> Clients => _clients.Values.Select(c => new ClientInfo(c.Id, c.Name, c.RemoteIp));

    public async Task CloseAllAsync()
    {
        foreach (var c in _clients.Values)
        {
            try
            {
                await c.Socket.CloseAsync(System.Net.WebSockets.WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
            }
            catch { }
        }
    }

    private async Task<bool> BroadcastAsync(WsMessage msg)
    {
        if (_clients.IsEmpty) return false;
        var json = Protocol.Serialize(msg);
        var ok = false;
        foreach (var c in _clients.Values)
            ok |= await SendAsync(c, msg, json);
        return ok;
    }

    private async Task<bool> SendAsync(ClientConn conn, WsMessage msg, string? json = null)
    {
        try
        {
            var bytes = Encoding.UTF8.GetBytes(json ?? Protocol.Serialize(msg));
            await conn.SendLock.WaitAsync(CancellationToken.None);
            try
            {
                await conn.Socket.SendAsync(
                    bytes,
                    System.Net.WebSockets.WebSocketMessageType.Text,
                    true,
                    CancellationToken.None);
                return true;
            }
            finally
            {
                conn.SendLock.Release();
            }
        }
        catch
        {
            return false;
        }
    }

    // ---- helpers ----

    private bool Authorize(HttpContext ctx, AppSettings settings)
    {
        var q = ctx.Request.Query["token"].FirstOrDefault();
        var h = ctx.Request.Headers["X-Token"].FirstOrDefault();
        var token = q ?? h;
        if (string.IsNullOrEmpty(token)) return false;
        var tokenOk = CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(token),
            Encoding.UTF8.GetBytes(settings.Token));
        return tokenOk && PasswordOk(GetPw(ctx), settings);
    }

    /// <summary>Session password (p. 18): checked on every HTTP request and on the WS
    /// handshake. Empty setting = no password required.</summary>
    private static bool PasswordOk(string? pw, AppSettings settings)
    {
        var required = settings.TransferPassword;
        if (string.IsNullOrEmpty(required)) return true;
        if (string.IsNullOrEmpty(pw)) return false;
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(pw),
            Encoding.UTF8.GetBytes(required));
    }

    private static string? GetPw(HttpContext ctx)
        => ctx.Request.Query["pw"].FirstOrDefault() ?? ctx.Request.Headers["X-Pw"].FirstOrDefault();

    private bool CheckRateLimit(string ip)
    {
        var now = DateTime.UtcNow;
        _pairAttempts.Enqueue((now, ip));
        while (_pairAttempts.TryPeek(out var head) && now - head.At > TimeSpan.FromMinutes(1))
            _pairAttempts.TryDequeue(out _);
        return _pairAttempts.Count(a => a.Ip == ip) <= 5;
    }

    public static string SanitizeFileName(string name)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var sb = new StringBuilder(name.Length);
        foreach (var ch in name)
            sb.Append(Array.IndexOf(invalid, ch) >= 0 ? '_' : ch);
        var result = sb.ToString().Trim();
        return string.IsNullOrEmpty(result) ? "file.bin" : result;
    }

    public static string UniquePath(string folder, string name)
    {
        var dest = Path.Combine(folder, name);
        if (!File.Exists(dest)) return dest;
        var stem = Path.GetFileNameWithoutExtension(name);
        var ext = Path.GetExtension(name);
        for (var i = 1; ; i++)
        {
            var candidate = Path.Combine(folder, $"{stem} ({i}){ext}");
            if (!File.Exists(candidate)) return candidate;
        }
    }

    public static string FormatSize(long bytes) => bytes switch
    {
        < 1024 => $"{bytes} B",
        < 1024 * 1024 => $"{bytes / 1024.0:0.#} KB",
        < 1024L * 1024 * 1024 => $"{bytes / (1024.0 * 1024):0.#} MB",
        _ => $"{bytes / (1024.0 * 1024 * 1024):0.#} GB",
    };

    public async ValueTask DisposeAsync()
    {
        _outboxSweep?.Dispose();
        _outboxSweep = null;
        await CloseAllAsync();
        if (_app != null)
        {
            try { await _app.StopAsync(new CancellationTokenSource(TimeSpan.FromSeconds(2)).Token); } catch { }
            try { await _app.DisposeAsync(); } catch { }
            _app = null;
        }
        if (_runTask != null)
        {
            try { await _runTask; } catch { }
        }
        foreach (var c in _clients.Values) c.SendLock.Dispose();
        _clients.Clear();
    }
}
