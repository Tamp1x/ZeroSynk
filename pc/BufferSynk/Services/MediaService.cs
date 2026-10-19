using Windows.Media.Control;

namespace BufferSynk.Services;

/// <summary>Snapshot of the PC's current media session (like KDE Connect's MPRIS bridge).</summary>
public sealed record MediaSnapshot(
    bool Active,
    string State,       // "playing" | "paused" | "stopped"
    string Title,
    string Artist,
    string App);

/// <summary>
/// Watches the Windows global media transport controls session (YouTube in a browser,
/// VLC, media players) and exposes play/pause/next/prev commands for the phone.
/// </summary>
public static class MediaService
{
    private static GlobalSystemMediaTransportControlsSessionManager? _manager;
    private static GlobalSystemMediaTransportControlsSession? _session;
    private static MediaSnapshot _last = new(false, "stopped", "", "", "");

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
            }

            _session = _manager?.GetCurrentSession();

            if (_session != null)
            {
                _session.MediaPropertiesChanged += OnSessionChanged;
                _session.PlaybackInfoChanged += OnSessionChanged;
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
                Publish(new MediaSnapshot(false, "stopped", "", "", ""));
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

            var active = state != "stopped" && !string.IsNullOrWhiteSpace(title);
            Publish(new MediaSnapshot(active, state, title, artist, app));
        }
        catch { }
    }

    private static void Publish(MediaSnapshot snap)
    {
        if (snap == _last) return;
        _last = snap;
        StateChanged?.Invoke(snap);
    }

    /// <summary>Execute a control command received from the phone.</summary>
    public static async Task ExecuteAsync(string action)
    {
        try
        {
            var s = _manager?.GetCurrentSession() ?? _session;
            if (s == null) return;

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
            }

            // The playback event will follow and re-broadcast the fresh state;
            // push a best-effort update right away so the phone reacts fast.
            await PushStateAsync();
        }
        catch { }
    }
}
