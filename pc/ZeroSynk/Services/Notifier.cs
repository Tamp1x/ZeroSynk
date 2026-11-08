namespace ZeroSynk.Services;

/// <summary>
/// Pop-up notifications, independent of the tray-icon setting: turning the tray
/// icon off must not kill "file received" / phone-notification pop-ups.
/// Uses a tray balloon while the icon is visible, a WPF toast otherwise.
/// </summary>
public static class Notifier
{
    /// <summary>Show a short pop-up. Safe to call from any thread;
    /// no-ops when "Всплывающие уведомления" is disabled in settings.
    /// [openPath] — file/folder opened by the balloon's «Открыть» click (p. 16).</summary>
    public static void ShowToast(string title, string body, string? openPath = null)
    {
        try
        {
            if (!App.Settings.ShowNotifications) return;
            var dispatcher = System.Windows.Application.Current?.Dispatcher;
            if (dispatcher == null) return;
            dispatcher.BeginInvoke(() =>
            {
                if (TrayManager.Visible)
                    TrayManager.ShowBalloon(title, body, openPath);
                else
                    ToastWindow.ShowToast(title, body, openPath);
            });
        }
        catch { }
    }

    /// <summary>Opens a file with its associated app, or a folder in Explorer. Never auto-starts on receive — only on explicit click.</summary>
    public static void OpenTarget(string? path)
    {
        if (string.IsNullOrWhiteSpace(path)) return;
        try
        {
            if (!File.Exists(path) && !Directory.Exists(path)) return;
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = path,
                UseShellExecute = true,
            });
        }
        catch { }
    }
}
