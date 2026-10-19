using BufferSynk.Models;

namespace BufferSynk.Services;

/// <summary>
/// Shows phone notifications as Windows toasts (via tray balloon fallback).
/// PC→phone notification mirroring: WinRT UserNotificationListener is not projected
/// in this WPF project without a companion component — stubbed with a clear message.
/// Phone→PC direction does NOT depend on this service and works fully.
/// </summary>
public sealed class WindowsNotifService : IDisposable
{
    private readonly AppController _controller;

    public event Action<string>? AccessRequested;
    public event Action<string>? Log;

    public bool IsRunning { get; private set; }
    public bool HasAccess => false;

    public WindowsNotifService(AppController controller)
    {
        _controller = controller;
    }

    public Task<bool> RequestAccessAsync()
    {
        // WinRT UserNotificationListener.RequestAccessAsync() needs a CsWinRT companion;
        // not available in this WPF build. Show a clear message instead.
        AccessRequested?.Invoke(
            "Зеркало уведомлений Windows → телефон пока недоступно в этой сборке WPF. " +
            "Направление телефон → ПК работает полностью.");
        Log?.Invoke("PC→phone notification mirroring not available in this WPF build.");
        return Task.FromResult(false);
    }

    public Task<bool> StartAsync()
    {
        Log?.Invoke("PC→phone notification mirroring not available (phone→PC works).");
        return Task.FromResult(false);
    }

    public void Stop() => IsRunning = false;

    /// <summary>Show a phone notification on the PC (tray balloon).</summary>
    public async Task ShowPhoneNotifAsync(WsMessage msg)
    {
        if (!_controller.Settings.SyncEnabled || !_controller.Settings.MirrorPhoneNotifs) return;
        try
        {
            var title = msg.AppName ?? "Телефон";
            var body = string.IsNullOrEmpty(msg.Text) ? msg.Title ?? "" : $"{msg.Title}\n{msg.Text}";
            if (App.Settings.ShowTrayIcon)
                TrayManager.ShowBalloon(title, body);
            await Task.CompletedTask;
        }
        catch (Exception ex)
        {
            Log?.Invoke($"ShowPhoneNotif error: {ex.Message}");
        }
    }

    public void Dispose() => Stop();
}
