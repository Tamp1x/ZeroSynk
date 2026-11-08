using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ZeroSynk.Models;
using ZeroSynk.Services;
using QRCoder;

namespace ZeroSynk.UI.Pages;

public partial class HomePage : System.Windows.Controls.UserControl
{
    private readonly AppController _controller;
    private readonly Action _refreshHome;

    private const string PlaceholderText = "Текст для буфера телефона…";

    public HomePage(AppController controller, Action refreshHome)
    {
        _controller = controller;
        _refreshHome = refreshHome;
        InitializeComponent();
        QrImage.Clip = new RectangleGeometry(new Rect(0, 0, 148, 148), 10, 10);

        Loaded += OnLoaded;
        Unloaded += (_, _) =>
        {
            _controller.StatusChanged -= OnStatusChanged;
            _controller.ClientPresence -= OnPresence;
            _controller.PhoneMediaReceived -= OnPhoneMedia;
            ThemeManager.ThemeChanged -= OnSkinChanged;
        };

        _controller.StatusChanged += OnStatusChanged;
        _controller.ClientPresence += OnPresence;
        _controller.PhoneMediaReceived += OnPhoneMedia;

        PinText.Text = FormatPin(App.Settings.Pin);
        QrHintText.Text = BuildQrHint();
        ReceiveDirText.Text = App.Settings.SaveFolder;
        ReceiveDirSub.Text = Directory.Exists(App.Settings.SaveFolder)
            ? "Папка существует — файлы сохраняются автоматически"
            : "Будет создана при первом файле";

        StatusTitle.Text = "Ожидание подключения";
        StatusDetail.Text = $"Запустите {DesignService.Current} на телефоне, чтобы начать";
        PairHint.Text =
            $"Откройте {DesignService.Current} на телефоне и отсканируйте QR-код.\n" +
            "Всё работает через вашу сеть (Tailscale в том числе).";
        UpdateSyncButton();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        ThemeManager.ThemeChanged += OnSkinChanged;
        GenerateQr();
        UpdateStatusLine();
    }

    private void OnSkinChanged() => Dispatcher.BeginInvoke(() =>
    {
        PairHint.Text =
            $"Откройте {DesignService.Current} на телефоне и отсканируйте QR-код.\n" +
            "Всё работает через вашу сеть (Tailscale в том числе).";
    });

    private void OnStatusChanged(string s) => Dispatcher.BeginInvoke(() =>
    {
        StatusDetail.Text = s;
        UpdateStatusLine();
    });

    private void OnPresence(bool connected) => Dispatcher.BeginInvoke(() => UpdateStatusLine);

    private void UpdateStatusLine()
    {
        var connected = _controller.Server.ClientCount > 0;
        if (!App.Settings.SyncEnabled)
        {
            StatusTitle.Text = "Синхронизация остановлена";
            StatusDetail.Text = "Нажмите «Запустить», чтобы возобновить";
        }
        else if (connected)
        {
            var name = App.Settings.PairedDeviceName ?? "Телефон";
            StatusTitle.Text = "Подключено";
            StatusDetail.Text = $"{name} — синхронизация активна";
        }
        else
        {
            StatusTitle.Text = "Ожидание подключения";
            StatusDetail.Text = "Отсканируйте QR-код с телефона или введите PIN";
        }
    }

    // ---- phone media (reverse control) ----

    private void OnPhoneMedia(WsMessage msg) => Dispatcher.BeginInvoke(() =>
    {
        if (PhoneMediaCard == null) return;
        var active = msg.State != "stopped" && !string.IsNullOrWhiteSpace(msg.Title);
        if (!active)
        {
            PhoneMediaCard.Visibility = Visibility.Collapsed;
            return;
        }

        PhoneMediaCard.Visibility = Visibility.Visible;
        PhoneMediaTitle.Text = msg.Title ?? "";
        PhoneMediaSub.Text = string.Join(" — ", new[] { msg.Artist, msg.App }
            .Where(s => !string.IsNullOrWhiteSpace(s)));

        var pos = msg.Position ?? 0;
        var dur = msg.Duration ?? 0;
        if (dur > 0)
        {
            PhoneMediaProgress.IsIndeterminate = false;
            PhoneMediaProgress.Maximum = dur;
            PhoneMediaProgress.Value = Math.Min(pos, dur);
            PhoneMediaTime.Text = $"{FormatTime(pos)} / {FormatTime(dur)}";
        }
        else
        {
            PhoneMediaProgress.IsIndeterminate = true;
            PhoneMediaTime.Text = "";
        }
        PhoneMediaToggle.Content = msg.State == "playing" ? "⏸" : "▶";
    });

    private void OnPhoneMediaCmd(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement fe || fe.Tag is not string action || action.Length == 0) return;
        _controller.ControlPhoneMedia(action);
    }

    private static string FormatTime(long seconds) => $"{seconds / 60}:{seconds % 60:00}";

    // ---- QR ----

    private void GenerateQr()
    {
        try
        {
            var payload = BuildPairPayload();
            using var gen = new QRCodeGenerator();
            using var data = gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
            var png = new PngByteQRCode(data).GetGraphic(8);
            using var ms = new MemoryStream(png);
            var image = new BitmapImage();
            image.BeginInit();
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.StreamSource = ms;
            image.EndInit();
            QrImage.Source = image;
        }
        catch
        {
            QrImage.Visibility = Visibility.Collapsed;
        }
    }

    private string BuildPairPayload()
    {
        var ips = string.Join(",", NetworkService.GetAllIpsForQr(App.Settings.Port));
        // The QR already carries the token (full access), so it may carry the
        // session password too — otherwise every re-scan would need typing it (p. 18).
        var pw = string.IsNullOrEmpty(App.Settings.TransferPassword)
            ? ""
            : $"&w={Uri.EscapeDataString(App.Settings.TransferPassword)}";
        return $"zerosynk://pair?h={Uri.EscapeDataString(ips)}" +
               $"&p={App.Settings.Port}" +
               $"&t={Uri.EscapeDataString(App.Settings.Token)}" +
               $"&n={Uri.EscapeDataString(App.Settings.PcName)}" +
               pw;
    }

    private static string BuildQrHint()
    {
        var addrs = NetworkService.GetIPv4s();
        if (addrs.Count == 0) return "Нет доступных сетевых адресов";
        var parts = addrs.Take(3).Select(a => $"{a.Ip} ({a.Label})");
        return "Адреса: " + string.Join(", ", parts);
    }

    private static string FormatPin(string pin)
        => pin.Length == 6 ? $"{pin[..3]} {pin[3..]}" : pin;

    // ---- actions ----

    private void OnCopyPin(object sender, RoutedEventArgs e)
    {
        try
        {
            System.Windows.Clipboard.SetText(App.Settings.Pin);
            ((System.Windows.Controls.Button)sender).Content = "Скопировано ✓";
            var btn = (System.Windows.Controls.Button)sender;
            Dispatcher.BeginInvoke(new Action(() =>
            {
                Task.Delay(1500).ContinueWith(_ => Dispatcher.BeginInvoke(new Action(() => btn.Content = "Скопировать PIN")));
            }));
        }
        catch { }
    }

    private void OnRegenPair(object sender, RoutedEventArgs e)
    {
        var result = System.Windows.MessageBox.Show(
            "Создать новые коды подключения? Старые перестанут работать, телефон нужно будет подключить заново.",
            DesignService.Current, MessageBoxButton.YesNo, MessageBoxImage.Question);
        if (result != MessageBoxResult.Yes) return;

        _controller.RegeneratePairing();
        PinText.Text = FormatPin(App.Settings.Pin);
        GenerateQr();
        QrHintText.Text = BuildQrHint();
    }

    private void OnSendFileClick(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Title = "Выберите файлы для отправки на телефон",
            Multiselect = true,
        };
        if (dlg.ShowDialog() == true)
        {
            foreach (var f in dlg.FileNames)
                _controller.SendFile(f);
        }
    }

    private void OnQuickTextFocus(object sender, RoutedEventArgs e)
    {
        if (QuickText.Text == PlaceholderText) QuickText.Text = "";
    }

    private void OnQuickTextLostFocus(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(QuickText.Text)) QuickText.Text = PlaceholderText;
    }

    private void OnQuickTextSend(object sender, RoutedEventArgs e)
    {
        var text = QuickText.Text;
        if (text == PlaceholderText || string.IsNullOrWhiteSpace(text)) return;
        _controller.SendText(text.Trim());
        QuickText.Text = PlaceholderText;
        Notifier.ShowToast("Отправлено", "Текст передан на телефон");
    }

    private void OnSyncToggle(object sender, RoutedEventArgs e)
    {
        var newEnabled = !App.Settings.SyncEnabled;
        _controller.SetSyncEnabled(newEnabled);
        UpdateSyncButton();
        UpdateStatusLine();
    }

    private void UpdateSyncButton()
    {
        if (SyncToggleBtn == null) return;
        var enabled = App.Settings.SyncEnabled;
        SyncToggleBtn.Content = enabled ? "Остановить" : "Запустить";
        TrayManager.SetSyncEnabled(enabled);
    }

    private void OnOpenFolder(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(App.Settings.SaveFolder);
            Process.Start(new ProcessStartInfo
            {
                FileName = App.Settings.SaveFolder,
                UseShellExecute = true,
            });
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(ex.Message, DesignService.Current, MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
}
