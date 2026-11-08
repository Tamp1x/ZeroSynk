using System.Diagnostics;
using System.Runtime.InteropServices;
using Windows.Media.Control;
using Windows.Storage.Streams;

namespace ZeroSynk.Services;

/// <summary>Snapshot of the PC's current media session (like KDE Connect's MPRIS bridge).</summary>
public sealed record MediaSnapshot(
    bool Active,
    string State,       // "playing" | "paused" | "stopped"
    string Title,
    string Artist,
    string App,
    long Position,      // seconds inside the item
    long Duration,      // seconds, 0 = unknown
    string? Cover);     // base64 album art (raw thumbnail), null = none

/// <summary>
/// Watches the Windows global media transport controls session (YouTube in a browser,
/// VLC, media players) and exposes play/pause/next/prev/seek/stop commands for the phone.
/// </summary>
public static class MediaService
{
    /// <summary>Skip absurdly large thumbnails instead of pushing them over the wire.</summary>
    private const long MaxCoverBytes = 400 * 1024;
    private const int PositionTickMs = 1000;

    private static GlobalSystemMediaTransportControlsSessionManager? _manager;
    private static GlobalSystemMediaTransportControlsSession? _session;
    private static MediaSnapshot _last = new(false, "stopped", "", "", "", 0, 0, null);

    private static int _tick;          // invalidates stale position tickers
    private static string? _coverKey;  // media item we already fetched art for
    private static string? _cover;     // base64 art belonging to _coverKey

    /// <summary>Raised whenever the media state changes. Any thread.</summary>
    public static event Action<MediaSnapshot>? StateChanged;

    public static MediaSnapshot Current => _last;

    public static async Task InitAsync()
    {
        try
        {
            _manager = await GlobalSystemMediaTransportControlsSessionManager.RequestAsync();
            _manager.CurrentSessionChanged += (_, _) => { _ = RefreshSessionAsync(); };
            _manager.SessionsChanged += (_, _) => { _ = RefreshSessionAsync(); };
            await RefreshSessionAsync();
        }
        catch
        {
            // Media integration unavailable — feature simply stays off.
        }
    }

    private static async Task RefreshSessionAsync()
    {
        try
        {
            if (_session != null)
            {
                _session.MediaPropertiesChanged -= OnSessionChanged;
                _session.PlaybackInfoChanged -= OnSessionChanged;
                _session.TimelinePropertiesChanged -= OnSessionChanged;
            }

            _session = _manager?.GetCurrentSession();

            if (_session != null)
            {
                _session.MediaPropertiesChanged += OnSessionChanged;
                _session.PlaybackInfoChanged += OnSessionChanged;
                _session.TimelinePropertiesChanged += OnSessionChanged;
            }
            else
            {
                _coverKey = null;
                _cover = null;
            }

            await PushStateAsync();
        }
        catch { }
    }

    private static async void OnSessionChanged(GlobalSystemMediaTransportControlsSession s, object args)
    {
        try { await PushStateAsync(); } catch { }
    }

    private static async Task PushStateAsync()
    {
        try
        {
            var s = _session;
            if (s == null)
            {
                _coverKey = null;
                _cover = null;
                Publish(new MediaSnapshot(false, "stopped", "", "", "", 0, 0, null));
                return;
            }

            var info = s.GetPlaybackInfo();
            var state = info.PlaybackStatus switch
            {
                GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing => "playing",
                GlobalSystemMediaTransportControlsSessionPlaybackStatus.Paused => "paused",
                _ => "stopped",
            };

            var title = "";
            var artist = "";
            try
            {
                var props = await s.TryGetMediaPropertiesAsync();
                title = props.Title ?? "";
                artist = string.IsNullOrWhiteSpace(props.Artist) ? props.AlbumTitle ?? "" : props.Artist;
            }
            catch { }

            var app = "";
            try { app = s.SourceAppUserModelId ?? ""; } catch { }

            // Timeline: all SMTC timestamps share one scale, so the item-relative
            // position is Position - StartTime and the duration is End - Start.
            long position = 0;
            long duration = 0;
            try
            {
                var tl = s.GetTimelineProperties();
                duration = (long)Math.Max(0, (tl.EndTime - tl.StartTime).TotalSeconds);
                var rel = tl.Position - tl.StartTime;
                position = rel < TimeSpan.Zero ? 0 : (long)rel.TotalSeconds;
                if (duration > 0 && position > duration) position = duration;
            }
            catch { }

            var active = state != "stopped" && !string.IsNullOrWhiteSpace(title);

            // Album art is fetched once per track: the thumbnail stream already holds an
            // encoded image (JPEG/PNG), so it can be forwarded to the phone as-is.
            var key = $"{title}|{artist}|{app}";
            if (active)
            {
                if (key != _coverKey)
                {
                    _coverKey = key;
                    _cover = await FetchCoverAsync(s);
                }
            }
            else
            {
                _coverKey = null;
                _cover = null;
            }

            Publish(new MediaSnapshot(active, state, title, artist, app, position, duration, _cover));

            // While playing, tick every second so the phone's progress bar keeps moving.
            if (active && state == "playing")
            {
                var gen = Interlocked.Increment(ref _tick);
                _ = TickerAsync(gen);
            }
        }
        catch { }
    }

    private static async Task TickerAsync(int gen)
    {
        await Task.Delay(PositionTickMs);
        if (gen != _tick) return; // a newer tick (or event push) already took over
        await PushStateAsync();
    }

    private static void Publish(MediaSnapshot snap)
    {
        if (snap == _last) return;
        _last = snap;
        StateChanged?.Invoke(snap);
    }

    /// <summary>
    /// Reads the raw thumbnail bytes once per track. No decode/re-encode: the stream is
    /// already a complete image, the phone downscales it for the notification.
    /// </summary>
    private static async Task<string?> FetchCoverAsync(GlobalSystemMediaTransportControlsSession s)
    {
        try
        {
            var props = await s.TryGetMediaPropertiesAsync();
            var thumb = props.Thumbnail;
            if (thumb == null) return null;

            var stream = await thumb.OpenReadAsync();
            if (stream.Size == 0 || stream.Size > MaxCoverBytes) return null;

            var data = new byte[stream.Size];
            var reader = new DataReader(stream);
            await reader.LoadAsync((uint)stream.Size);
            reader.ReadBytes(data);
            return Convert.ToBase64String(data);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Execute a control command received from the phone (or the PC's own UI).</summary>
    public static async Task ExecuteAsync(string action, long? position = null)
    {
        try
        {
            var s = _manager?.GetCurrentSession() ?? _session;
            if (s == null)
            {
                // No player session: volume keys are global, still honour them.
                if (action == "volUp") MediaKeys.Press(MediaKeys.VolumeUp);
                else if (action == "volDown") MediaKeys.Press(MediaKeys.VolumeDown);
                return;
            }

            var info = s.GetPlaybackInfo();
            var c = info.Controls;
            switch (action)
            {
                case "play":
                    if (c.IsPlayEnabled) await s.TryPlayAsync();
                    break;
                case "pause":
                    if (c.IsPauseEnabled) await s.TryPauseAsync();
                    break;
                case "toggle":
                    if (info.PlaybackStatus == GlobalSystemMediaTransportControlsSessionPlaybackStatus.Playing)
                    {
                        if (c.IsPauseEnabled) await s.TryPauseAsync();
                    }
                    else if (c.IsPlayEnabled)
                    {
                        await s.TryPlayAsync();
                    }
                    break;
                case "next":
                    if (c.IsNextEnabled) await s.TrySkipNextAsync();
                    break;
                case "prev":
                    if (c.IsPreviousEnabled) await s.TrySkipPreviousAsync();
                    break;
                case "stop":
                    await s.TryStopAsync();
                    break;
                case "seek" when position.HasValue:
                    {
                        var tl = s.GetTimelineProperties();
                        var target = tl.StartTime + TimeSpan.FromSeconds(Math.Max(0, position.Value));
                        if (tl.EndTime > tl.StartTime && target > tl.EndTime) target = tl.EndTime;
                        await s.TryChangePlaybackPositionAsync(target.Ticks);
                    }
                    break;
                case "seekBack":
                    await SeekByAsync(s, -10);
                    break;
                case "seekFwd":
                    await SeekByAsync(s, +10);
                    break;
                case "volUp":
                    MediaKeys.Press(MediaKeys.VolumeUp);
                    break;
                case "volDown":
                    MediaKeys.Press(MediaKeys.VolumeDown);
                    break;
                case "openFolder":
                    // Local file lookup — runs off the WS/WinRT threads.
                    _ = Task.Run(OpenTrackFolder);
                    break;
            }

            // The playback event will follow and re-broadcast the fresh state;
            // push a best-effort update right away so the phone reacts fast.
            await PushStateAsync();
        }
        catch { }
    }

    private static async Task SeekByAsync(GlobalSystemMediaTransportControlsSession s, long deltaSeconds)
    {
        var tl = s.GetTimelineProperties();
        var target = tl.StartTime + (tl.Position - tl.StartTime) + TimeSpan.FromSeconds(deltaSeconds);
        if (target < tl.StartTime) target = tl.StartTime;
        if (tl.EndTime > tl.StartTime && target > tl.EndTime) target = tl.EndTime;
        await s.TryChangePlaybackPositionAsync(target.Ticks);
    }

    /// <summary>
    /// "Open the track's folder on the PC". SMTC exposes no file path, so we look for a
    /// local file whose name contains the track title and reveal it in Explorer.
    /// Streaming tracks (browser, cloud players) have no file — then just open Music.
    /// </summary>
    private static void OpenTrackFolder()
    {
        var music = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);
        var found = FindTrackFile(_last.Title);
        try
        {
            if (found != null)
            {
                Process.Start(new ProcessStartInfo
                {
                    FileName = "explorer.exe",
                    Arguments = $"/select,\"{found}\"",
                    UseShellExecute = true,
                });
            }
            else if (Directory.Exists(music))
            {
                Process.Start(new ProcessStartInfo { FileName = music, UseShellExecute = true });
            }
        }
        catch { }
    }

    private static string? FindTrackFile(string title)
    {
        var needle = title.Trim();
        if (needle.Length == 0) return null;

        var roots = new[]
        {
            Environment.GetFolderPath(Environment.SpecialFolder.MyMusic),
            Environment.GetFolderPath(Environment.SpecialFolder.MyVideos),
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads"),
            Environment.GetFolderPath(Environment.SpecialFolder.Desktop),
        };

        foreach (var root in roots)
        {
            try
            {
                if (!Directory.Exists(root)) continue;
                var scanned = 0;
                foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
                {
                    if (++scanned > 20_000) break;
                    var name = Path.GetFileNameWithoutExtension(file);
                    if (name.Contains(needle, StringComparison.OrdinalIgnoreCase))
                        return file;
                }
            }
            catch
            {
                // Access denied / drive gone — move on to the next root.
            }
        }
        return null;
    }

    /// <summary>
    /// SMTC has no volume API, so volume is done with the standard multimedia keys —
    /// exactly the ones on a keyboard, they change the system volume globally.
    /// </summary>
    private static class MediaKeys
    {
        public const byte VolumeUp = 0xAF;
        public const byte VolumeDown = 0xAE;

        private const uint KeyUp = 0x0002;

        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        public static void Press(byte vk)
        {
            keybd_event(vk, 0, 0, UIntPtr.Zero);
            keybd_event(vk, 0, KeyUp, UIntPtr.Zero);
        }
    }
}
