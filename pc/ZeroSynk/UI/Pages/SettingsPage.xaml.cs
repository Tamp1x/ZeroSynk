using System.Windows;
using System.Windows.Controls;
using ZeroSynk.Models;
using ZeroSynk.Services;

namespace ZeroSynk.UI.Pages;

public partial class SettingsPage : System.Windows.Controls.UserControl
{
    private readonly AppController _controller;
    private bool _loading = true;

    public SettingsPage(AppController controller)
    {
        _controller = controller;
        InitializeComponent();
        LoadFromSettings();
        Loaded += (_, _) => PortHint.Text =
            $"Сейчас слушает: {string.Join(", ", NetworkService.GetIPv4s().Take(3).Select(a => a.Ip))}:{App.Settings.Port}";
        _loading = false;
    }

    private void LoadFromSettings()
    {
        var s = App.Settings;
        RowAutoStart.IsOn = s.StartWithWindows;
        RowStartMin.IsOn = s.StartMinimized;
        RowTray.IsOn = s.ShowTrayIcon;
        RowMinToTray.IsOn = s.MinimizeToTray;
        RowSyncTo.IsOn = s.SyncClipboardToPhone;
        RowSyncFrom.IsOn = s.SyncClipboardFromPhone;
        RowImages.IsOn = s.SyncImagesToPhone;
        RowClipFiles.IsOn = s.SyncClipboardFiles;
        RowSound.IsOn = s.PlaySound;
        RowAutoAccept.IsOn = s.AutoAcceptFiles;
        RowAutoClean.IsOn = s.AutoCleanReceived;
        RowMediaToPhone.IsOn = s.MediaToPhone;
        RowControlPhone.IsOn = s.ControlPhoneMedia;
        RowMirrorPhone.IsOn = s.MirrorPhoneNotifs;
        RowMirrorWin.IsOn = s.MirrorWinNotifs;
        RowNotifications.IsOn = s.ShowNotifications;
        FolderBox.Text = s.SaveFolder;
        PortBox.Text = s.Port.ToString();
        // Password lives in the masked PasswordBox; the plain TextBox mirrors it only while visible.
        PwBox.Password = s.TransferPassword;
        PwPlainBox.Text = s.TransferPassword;
        PasswordHint.Text = string.IsNullOrEmpty(s.TransferPassword)
            ? "Пароль не задан — доступ только по токену."
            : "Пароль задан — введите его в настройках телефона.";

        var keep = s.KeepRecentBatches;
        KeepB0.IsChecked = keep == 0;
        KeepB1.IsChecked = keep == 1;
        KeepB2.IsChecked = keep == 2;
        KeepB3.IsChecked = keep == 3;
        KeepB5.IsChecked = keep == 5;
        if (KeepB0.IsChecked != true && KeepB1.IsChecked != true && KeepB2.IsChecked != true &&
            KeepB3.IsChecked != true && KeepB5.IsChecked != true)
            KeepB1.IsChecked = true;
        SetKeepEnabled(s.AutoCleanReceived);

        ThemeSystem.IsChecked = s.Theme == "system";
        ThemeLight.IsChecked = s.Theme == "light";
        ThemeDark.IsChecked = s.Theme == "dark";
        if (!ThemeSystem.IsChecked.GetValueOrDefault() && !ThemeLight.IsChecked.GetValueOrDefault() && !ThemeDark.IsChecked.GetValueOrDefault())
            ThemeSystem.IsChecked = true;

        DesignZero.IsChecked = s.Design == "zero";
        DesignOnyx.IsChecked = s.Design == "onyx";
        DesignEclipx.IsChecked = s.Design == "eclipx";
        if (!DesignZero.IsChecked.GetValueOrDefault() && !DesignOnyx.IsChecked.GetValueOrDefault() && !DesignEclipx.IsChecked.GetValueOrDefault())
            DesignZero.IsChecked = true;

        AboutTitle.Text = $"{DesignService.Current} 1.7.0";
    }

    private void OnToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var s = App.Settings;
        s.StartWithWindows = RowAutoStart.IsOn;
        s.StartMinimized = RowStartMin.IsOn;
        s.MinimizeToTray = RowMinToTray.IsOn;
        s.SyncClipboardToPhone = RowSyncTo.IsOn;
        s.SyncClipboardFromPhone = RowSyncFrom.IsOn;
        s.SyncImagesToPhone = RowImages.IsOn;
        s.SyncClipboardFiles = RowClipFiles.IsOn;
        s.PlaySound = RowSound.IsOn;
        s.AutoAcceptFiles = RowAutoAccept.IsOn;
        s.AutoCleanReceived = RowAutoClean.IsOn;
        s.ShowNotifications = RowNotifications.IsOn;
        SetKeepEnabled(s.AutoCleanReceived);
        _controller.SaveSettings();
    }

    private void OnKeepBatchesChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var s = App.Settings;
        s.KeepRecentBatches =
            KeepB0.IsChecked == true ? 0 :
            KeepB2.IsChecked == true ? 2 :
            KeepB3.IsChecked == true ? 3 :
            KeepB5.IsChecked == true ? 5 : 1;
        _controller.SaveSettings();
    }

    private void SetKeepEnabled(bool enabled)
    {
        KeepB0.IsEnabled = KeepB1.IsEnabled = KeepB2.IsEnabled =
            KeepB3.IsEnabled = KeepB5.IsEnabled = enabled;
    }

    private async void OnMediaNotifToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var s = App.Settings;
        s.MediaToPhone = RowMediaToPhone.IsOn;
        s.ControlPhoneMedia = RowControlPhone.IsOn;
        s.MirrorPhoneNotifs = RowMirrorPhone.IsOn;
        s.MirrorWinNotifs = RowMirrorWin.IsOn;
        _controller.SaveSettings();

        if (s.MirrorWinNotifs && s.SyncEnabled)
        {
            // Request access (shows system dialog if available; otherwise shows message)
            var granted = await _controller.WinNotif.RequestAccessAsync();
            if (granted)
            {
                _ = _controller.WinNotif.StartAsync();
                PortHint.Text = "Доступ к уведомлениям Windows получен ✓";
            }
            else
            {
                RowMirrorWin.IsOn = false;
                s.MirrorWinNotifs = false;
                _controller.SaveSettings();
                PortHint.Text = "Зеркало уведомлений Windows → телефон пока недоступно в этой сборке. " +
                    "Направление телефон → ПК работает полностью.";
            }
        }
        else
        {
            _controller.WinNotif.Stop();
        }
    }

    private void OnTrayToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var s = App.Settings;
        s.ShowTrayIcon = RowTray.IsOn;
        _controller.SaveSettings();

        // Tray can't be created/destroyed from a page safely if Init was skipped at boot;
        // for simplicity: if turned off, closing exits; if turned on after boot-off, restart hint.
        if (!s.ShowTrayIcon)
        {
            PortHint.Text = "Иконка в трее выключена: при закрытии окна приложение завершится.";
        }
        else
        {
            PortHint.Text = "Иконка в трее включится после перезапуска приложения.";
        }
    }

    /// <summary>Shows/hides the password: swaps the masked PasswordBox for a plain TextBox (p. 18).</summary>
    private void OnTogglePwVisibility(object sender, RoutedEventArgs e)
    {
        var visible = PwEye.IsChecked == true;
        if (visible)
        {
            PwPlainBox.Text = PwBox.Password;
            PwBox.Visibility = Visibility.Collapsed;
            PwPlainBox.Visibility = Visibility.Visible;
            PwPlainBox.Focus();
        }
        else
        {
            PwBox.Password = PwPlainBox.Text;
            PwPlainBox.Visibility = Visibility.Collapsed;
            PwBox.Visibility = Visibility.Visible;
            PwBox.Focus();
        }
        PwEyeSlash.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
    }

    /// <summary>Saves the session password (p. 18); empty turns the check off.</summary>
    private void OnApplyPassword(object sender, RoutedEventArgs e)
    {
        var pw = (PwEye.IsChecked == true ? PwPlainBox.Text : PwBox.Password).Trim();
        App.Settings.TransferPassword = pw;
        _controller.SaveSettings();
        PasswordHint.Text = pw.Length == 0
            ? "Пароль отключён — доступ только по токену."
            : "Пароль сохранён — введите его в настройках телефона.";
    }

    private void OnChooseFolder(object sender, RoutedEventArgs e)
    {
        var dlg = new System.Windows.Forms.FolderBrowserDialog
        {
            Description = "Папка для файлов с телефона",
            SelectedPath = App.Settings.SaveFolder,
            UseDescriptionForTitle = true,
        };
        if (dlg.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            App.Settings.SaveFolder = dlg.SelectedPath;
            FolderBox.Text = dlg.SelectedPath;
            _controller.SaveSettings();
        }
    }

    private void OnApplyPort(object sender, RoutedEventArgs e)
    {
        if (!int.TryParse(PortBox.Text, out var port) || port is < 1024 or > 65535)
        {
            PortHint.Text = "Порт должен быть числом от 1024 до 65535.";
            return;
        }
        if (port == App.Settings.Port)
        {
            PortHint.Text = "Порт не изменён.";
            return;
        }
        if (!NetworkService.IsPortFree(port))
        {
            PortHint.Text = $"Порт {port} уже занят другим приложением.";
            return;
        }

        App.Settings.Port = port;
        _controller.SaveSettings();
        PortHint.Text = $"Порт изменён на {port}. Перезапустите приложение для применения.";
    }

    private void OnThemeChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        if (ThemeLight.IsChecked == true) App.Settings.Theme = "light";
        else if (ThemeDark.IsChecked == true) App.Settings.Theme = "dark";
        else App.Settings.Theme = "system";
        _controller.SaveSettings();
        ThemeManager.Apply(App.Settings.Theme);
        PortHint.Text = App.Settings.Design == "zero"
            ? "Тема применена."
            : "Тема сохранена — она применяется для дизайна Zero.";
    }

    private void OnDesignChanged(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var design = DesignEclipx.IsChecked == true ? "eclipx"
            : DesignOnyx.IsChecked == true ? "onyx"
            : "zero";
        _controller.SetDesign(design);
        AboutTitle.Text = $"{DesignService.Current} 1.7.0";
        PortHint.Text = "Дизайн применён — он обновился на ПК и на телефоне.";
    }

    private void OnCopyToken(object sender, RoutedEventArgs e)
    {
        try
        {
            System.Windows.Clipboard.SetText(App.Settings.Token);
            ((System.Windows.Controls.Button)sender).Content = "Скопировано ✓";
            var btn = (System.Windows.Controls.Button)sender;
            Dispatcher.BeginInvoke(new Action(() =>
            {
                Task.Delay(1500).ContinueWith(_ =>
                    Dispatcher.BeginInvoke(new Action(() => btn.Content = "Копировать токен")));
            }));
        }
        catch { }
    }

    private void OnDiagnostics(object sender, RoutedEventArgs e)
    {
        var logView = new System.Windows.Controls.TextBox
        {
            Text = _controller.ReadRecentLogs(),
            IsReadOnly = true,
            AcceptsReturn = true,
            AcceptsTab = true,
            TextWrapping = TextWrapping.NoWrap,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            FontFamily = new System.Windows.Media.FontFamily("Consolas"),
            Margin = new Thickness(12),
        };
        var window = new Window
        {
            Title = "Диагностика ZeroSynk",
            Owner = Window.GetWindow(this),
            Width = 820,
            Height = 560,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Content = logView,
        };
        var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        timer.Tick += (_, _) =>
        {
            var latest = _controller.ReadRecentLogs();
            if (latest == logView.Text) return;
            logView.Text = latest;
            logView.CaretIndex = logView.Text.Length;
            logView.ScrollToEnd();
        };
        window.Closed += (_, _) => timer.Stop();
        timer.Start();
        window.ShowDialog();
    }

    private void OnOpenReleases(object sender, RoutedEventArgs e)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                "https://github.com/Tamp1x/ZeroSynk/releases/latest") { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show($"Не удалось открыть страницу релизов: {ex.Message}",
                "ZeroSynk", MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    private void OnResetPair(object sender, RoutedEventArgs e)
    {
        if (System.Windows.MessageBox.Show(
                "Сбросить подключение? Телефон нужно будет подключить заново (новый QR/PIN).",
                DesignService.Current, MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
        {
            _controller.RegeneratePairing();
            PortHint.Text = "Коды подключения обновлены — на Главной появится новый QR.";
        }
    }
}
