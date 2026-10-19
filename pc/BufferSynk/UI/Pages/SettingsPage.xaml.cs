using System.Windows;
using System.Windows.Controls;
using BufferSynk.Models;
using BufferSynk.Services;

namespace BufferSynk.UI.Pages;

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
        RowSound.IsOn = s.PlaySound;
        RowAutoAccept.IsOn = s.AutoAcceptFiles;
        RowAutoClean.IsOn = s.AutoCleanReceived;
        RowMediaToPhone.IsOn = s.MediaToPhone;
        RowMirrorPhone.IsOn = s.MirrorPhoneNotifs;
        RowMirrorWin.IsOn = s.MirrorWinNotifs;
        FolderBox.Text = s.SaveFolder;
        PortBox.Text = s.Port.ToString();

        ThemeSystem.IsChecked = s.Theme == "system";
        ThemeLight.IsChecked = s.Theme == "light";
        ThemeDark.IsChecked = s.Theme == "dark";
        if (!ThemeSystem.IsChecked.GetValueOrDefault() && !ThemeLight.IsChecked.GetValueOrDefault() && !ThemeDark.IsChecked.GetValueOrDefault())
            ThemeSystem.IsChecked = true;
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
        s.PlaySound = RowSound.IsOn;
        s.AutoAcceptFiles = RowAutoAccept.IsOn;
        s.AutoCleanReceived = RowAutoClean.IsOn;
        _controller.SaveSettings();
    }

    private async void OnMediaNotifToggle(object sender, RoutedEventArgs e)
    {
        if (_loading) return;
        var s = App.Settings;
        s.MediaToPhone = RowMediaToPhone.IsOn;
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
        PortHint.Text = "Тема применена.";
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

    private void OnResetPair(object sender, RoutedEventArgs e)
    {
        if (System.Windows.MessageBox.Show(
                "Сбросить подключение? Телефон нужно будет подключить заново (новый QR/PIN).",
                "ZeroSynk", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes)
        {
            _controller.RegeneratePairing();
            PortHint.Text = "Коды подключения обновлены — на Главной появится новый QR.";
        }
    }
}
