using Windows.UI.Notifications;
using ZeroSynk.Models;

namespace ZeroSynk.Services;

/// <summary>
/// PC → phone notification mirroring (p. 19) on top of the WinRT UserNotificationListener:
/// every Windows toast is pushed to the phone over the existing WS channel.
/// The listener needs an explicit one-time access grant (system dialog, p. 19).
/// The opposite direction (phone → PC) lives in AppController and never depends on this.
/// </summary>
public sealed class WindowsNotifService : IDisposable
{
    private readonly AppController _controller;
    private UserNotificationListener? _listener;
    private bool _subscribed;

    public event Action<string>? AccessRequested;
    public event Action<string>? Log;

    public bool IsRunning { get; private set; }

    public bool HasAccess => _listener?.AccessStatus == UserNotificationAccessStatus.Allowed;

    public WindowsNotifService(AppController controller)
    {
        _controller = controller;
    }

    public async Task<bool> RequestAccessAsync()
    {
        try
        {
            _listener ??= UserNotificationListener.Default;
            var status = await _listener.RequestAccessAsync();
            var ok = status == UserNotificationAccessStatus.Allowed;
            AccessRequested?.Invoke(ok
                ? "Доступ к уведомлениям Windows получен — зеркало включено."
                : "Система не выдала доступ к уведомлениям. Разрешите его в «Параметры → " +
                  "Конфиденциальность и безопасность → Уведомления» и повторите попытку.");
            Log?.Invoke($"UserNotificationListener access: {status}");
            return ok;
        }
        catch (Exception ex)
        {
            AccessRequested?.Invoke($"Доступ к уведомлениям недоступен: {ex.Message}");
            Log?.Invoke($"RequestAccessAsync failed: {ex.Message}");
            return false;
        }
    }

    public Task<bool> StartAsync()
    {
        try
        {
            _listener ??= UserNotificationListener.Default;
            if (_listener.AccessStatus != UserNotificationAccessStatus.Allowed)
            {
                Log?.Invoke("Not mirroring: notification access has not been granted yet.");
                return Task.FromResult(false);
            }
            if (!_subscribed)
            {
                _listener.NotificationChanged += OnNotificationChanged;
                _subscribed = true;
            }
            IsRunning = true;
            Log?.Invoke("PC → phone notification mirroring started.");
            return Task.FromResult(true);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"Notification mirroring start failed: {ex.Message}");
            return Task.FromResult(false);
        }
    }

    public void Stop()
    {
        if (_subscribed && _listener != null)
        {
            try { _listener.NotificationChanged -= OnNotificationChanged; }
            catch { }
            _subscribed = false;
        }
        IsRunning = false;
    }

    private void OnNotificationChanged(UserNotificationListener sender, UserNotificationChangedEventArgs args)
    {
        try
        {
            var n = args.UserNotification;
            if (n == null) return;
            var key = $"win:{n.Id}";

            if (args.ChangeType == UserNotificationChangedType.Removed)
            {
                _ = _controller.Server.BroadcastNotifAsync(WsMessage.NotifRemoved(key));
                return;
            }

            var texts = n.Notification?.Visual?.GetTexts();
            var title = texts is { Count: > 0 } ? texts[0].Text ?? "" : "";
            var body = texts is { Count: > 1 } ? texts[1].Text ?? "" : "";
            if (string.IsNullOrWhiteSpace(title) && string.IsNullOrWhiteSpace(body)) return;

            var appName = n.AppInfo?.DisplayName ?? "Windows";
            // Never mirror our own toasts back to the phone.
            if (string.Equals(appName, DesignService.Current, StringComparison.OrdinalIgnoreCase)) return;

            // Windows apps can't be replied to programmatically (p. 23) — replyable stays false.
            var msg = WsMessage.Notif(key, "windows", appName, title, body, false, "pc");
            _ = _controller.Server.BroadcastNotifAsync(msg);
            _controller.RecordNotif(msg);
        }
        catch (Exception ex)
        {
            Log?.Invoke($"NotificationChanged error: {ex.Message}");
        }
    }

    /// <summary>Show a phone notification on the PC (tray balloon or toast).</summary>
    public async Task ShowPhoneNotifAsync(WsMessage msg)
    {
        // PC-origin entries land in the same history, but must not echo back as toasts.
        if (string.Equals(msg.Origin, "pc", StringComparison.OrdinalIgnoreCase)) return;
        if (!_controller.Settings.SyncEnabled || !_controller.Settings.MirrorPhoneNotifs) return;
        try
        {
            var title = msg.AppName ?? "Телефон";
            var body = string.IsNullOrEmpty(msg.Text) ? msg.Title ?? "" : $"{msg.Title}\n{msg.Text}";
            Notifier.ShowToast(title, body);
            await Task.CompletedTask;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"ShowPhoneNotif error: {ex.Message}");
        }
    }

    public void Dispose() => Stop();
}
