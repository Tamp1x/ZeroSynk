using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Interop;
using BufferSynk.Services;
using BufferSynk.UI.Pages;

namespace BufferSynk;

public partial class MainWindow : Window
{
    private readonly AppController _controller;

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

    private const int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private const int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private const int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private const int DWMWCP_ROUND = 2;
    private const int DWMSBT_MAINWINDOW = 2;     // Mica

    public MainWindow(AppController controller)
    {
        _controller = controller;
        InitializeComponent();

        Loaded += OnLoaded;
        StateChanged += (_, _) =>
        {
            if (WindowState == WindowState.Minimized && App.Settings.MinimizeToTray && App.Settings.ShowTrayIcon)
            {
                Hide();
                ShowInTaskbar = false;
            }
        };

        // --- controller -> UI events (marshal to dispatcher) ---
        _controller.StatusChanged += s => Dispatcher.BeginInvoke(() =>
        {
            StatusText.Text = s;
            TrayManager.SetIconConnected(_controller.Server.ClientCount > 0);
        });

        _controller.ClientPresence += connected => Dispatcher.BeginInvoke(() =>
        {
            StatusDot.Fill = (System.Windows.Media.Brush)FindResource(connected ? "GreenBrush" : "OrangeBrush");
            TrayManager.SetIconConnected(connected);
        });

        _controller.Log += _ => { /* log sink: could be shown in UI later */ };

        _controller.FileSaved += (title, body) => Dispatcher.BeginInvoke(() =>
        {
            if (App.Settings.ShowTrayIcon)
                TrayManager.ShowBalloon(title, body);
        });

        _controller.HistoryAdded += _ => { };

        _controller.SettingsSaved += () => Dispatcher.BeginInvoke(() =>
        {
            UpdateIpText();
        });

        ThemeManager.ThemeChanged += OnThemeChanged;
    }

    private void OnThemeChanged() => Dispatcher.BeginInvoke(() =>
    {
        if (IsLoaded) ApplyBackdrop();
    });

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ApplyBackdrop();

        // Drag & drop
        AllowDrop = true;
        DragEnter += (_, e2) =>
        {
            if (e2.Data.GetDataPresent(System.Windows.DataFormats.FileDrop))
            {
                DropOverlay.Visibility = Visibility.Visible;
                e2.Effects = System.Windows.DragDropEffects.Copy;
            }
            else e2.Effects = System.Windows.DragDropEffects.None;
            e2.Handled = true;
        };
        DragLeave += (_, _) => DropOverlay.Visibility = Visibility.Collapsed;
        Drop += (_, e2) =>
        {
            DropOverlay.Visibility = Visibility.Collapsed;
            if (e2.Data.GetData(System.Windows.DataFormats.FileDrop) is string[] files)
            {
                foreach (var f in files)
                    _controller.SendFile(f);
                Navigate("home");
            }
            e2.Handled = true;
        };

        UpdateIpText();
        Navigate("home");
    }

    private void UpdateIpText()
    {
        var addrs = NetworkService.GetIPv4s();
        var primary = addrs.Count > 0 ? addrs[0] : null;
        IpText.Text = primary == null
            ? "Нет сетевых адресов"
            : $"{primary.Ip}:{App.Settings.Port}" +
              (addrs.Count > 1 ? $"  (+{addrs.Count - 1} ещё)" : "");
    }

    private void ApplyBackdrop()
    {
        var hwnd = new WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero) return;

        // Rounded corners (Win11)
        var corner = DWMWCP_ROUND;
        DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, ref corner, sizeof(int));

        // Mica backdrop (Win11 22H2+): window + root stay transparent
        var backdrop = DWMSBT_MAINWINDOW;
        var hr = DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, ref backdrop, sizeof(int));
        if (hr != 0)
        {
            // Fallback: Win10 / older Win11 — solid background
            RootBorder.SetResourceReference(System.Windows.Controls.Control.BackgroundProperty, "BgBrush");
        }

        // Sync dark titlebar with theme
        var darkValue = ThemeManager.IsDark ? 1 : 0;
        DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, ref darkValue, sizeof(int));
    }

    private void OnNavClick(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded || sender is not ToggleButton tb || tb.Tag is not string tag) return;
        Navigate(tag);
    }

    private void Navigate(string tag)
    {
        switch (tag)
        {
            case "home":
                if (ContentHost.Content is not HomePage)
                    ContentHost.Content = new HomePage(_controller, () => Navigate("home"));
                break;
            case "history":
                if (ContentHost.Content is not HistoryPage)
                    ContentHost.Content = new HistoryPage(_controller);
                break;
            case "notifs":
                if (ContentHost.Content is not NotifsPage)
                    ContentHost.Content = new NotifsPage(_controller);
                break;
            case "settings":
                if (ContentHost.Content is not SettingsPage)
                    ContentHost.Content = new SettingsPage(_controller);
                break;
        }

        NavHome.IsChecked = tag == "home";
        NavHistory.IsChecked = tag == "history";
        NavNotifs.IsChecked = tag == "notifs";
        NavSettings.IsChecked = tag == "settings";
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (App.Settings.ShowTrayIcon && App.Settings.MinimizeToTray)
        {
            e.Cancel = true;
            Hide();
            ShowInTaskbar = false;
        }
        // else: allow close -> App.OnExit cleans up
    }
}
