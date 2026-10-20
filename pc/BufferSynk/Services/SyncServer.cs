using System.Collections.Concurrent;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Web;
using BufferSynk.Models;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;

namespace BufferSynk.Services;

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

    private volatile bool _paused;
    private WsMessage? _mediaState;

    public event Action<string>? Log;
    public event Action<ClientInfo>? ClientConnected;
    public event Action<string>? ClientDisconnected;
    public event Action<WsMessage>? MessageReceived;   // from phone (clipboard, pong, fileComplete)
    public event Action<InboundFile>? FileReceived;
    public event Action<WsMessage, string>? ServerInitiated; // internal: PC -> phone pushes

    public bool IsRunning => _app != null;
    public bool IsPaused { get => _paused; set => _paused = value; }
    public int BoundPort { get; private set; }

    public sealed record ClientInfo(string Id, string Name, string RemoteIp);
    public sealed record OutboxFile(string Id, string Name, string Path, long Size);
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

            // success — issue the long-lived token
            Log?.Invoke($"Paired with device at {ip}");
            return Results.Json(new { ok = true, token = settingsRef.Token, pcName = settingsRef.PcName }, Protocol.Json);
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

            _outbox.TryRemove(id, out _);
            Log?.Invoke($"Phone downloaded: {f.Name} ({FormatSize(f.Size)})");
            return Results.File(f.Path, "application/octet-stream", f.Name);
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
                    await SendAsync(conn, new WsMessage
                    {
                        Type = "welcome",
                        Ok = true,
                        PcName = settings.PcName,
                        DeviceName = conn.Name,
                    });
                    if (_mediaState != null)
                        await SendAsync(conn, _mediaState);
                    ClientConnected?.Invoke(new ClientInfo(conn.Id, conn.Name, conn.RemoteIp));
                    break;

                case "ping":
                    await SendAsync(conn, new WsMessage { Type = "pong", Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
                    break;

                case "pong":
                    break;

                case "fileComplete":
                    Log?.Invoke($"Phone completed download: {msg.FileName}");
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

    public async Task<bool> SendClipboardTextAsync(string text, string hash)
    {
        if (_paused) return false;
        return await BroadcastAsync(WsMessage.ClipboardText(text, hash));
    }

    public async Task<bool> SendClipboardImageAsync(string base64Png, string hash)
    {
        if (_paused) return false;
        return await BroadcastAsync(WsMessage.ClipboardImage(base64Png, hash));
    }

    /// <summary>Broadcast the current media session state (remembered for late joiners).</summary>
    public async Task BroadcastMediaAsync(WsMessage msg)
    {
        _mediaState = msg;
        if (_paused) return;
        await BroadcastAsync(msg);
    }

    /// <summary>Broadcast a message to all connected clients (bypasses pause for status messages).</summary>
    public Task BroadcastStatusAsync(WsMessage msg) => BroadcastAsync(msg);

    /// <summary>Broadcast a notification (respects pause — not sent when sync stopped).</summary>
    public async Task<bool> BroadcastNotifAsync(WsMessage msg)
    {
        if (_paused) return false;
        return await BroadcastAsync(msg);
    }

    /// <summary>Stage a file for the phone and offer it over WS. Returns file id.</summary>
    public string? OfferFile(string sourcePath)
    {
        if (!File.Exists(sourcePath)) return null;
        var name = Path.GetFileName(sourcePath);
        Directory.CreateDirectory(settingsOutbox);
        var staged = UniquePath(settingsOutbox, name);
        File.Copy(sourcePath, staged, true);
        var id = Guid.NewGuid().ToString("N")[..16];
        var fi = new FileInfo(staged);
        _outbox[id] = new OutboxFile(id, name, staged, fi.Length);

        _ = BroadcastAsync(WsMessage.FileOffer(id, name, fi.Length));
        Log?.Invoke($"Offered file: {name} ({FormatSize(fi.Length)})");
        return id;
    }

    private string settingsOutbox = Path.Combine(Path.GetTempPath(), "BufferSynk", "outbox");

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
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(token),
            Encoding.UTF8.GetBytes(settings.Token));
    }

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
