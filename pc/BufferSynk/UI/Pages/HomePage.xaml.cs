using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using BufferSynk.Models;
using BufferSynk.Services;
using QRCoder;

namespace BufferSynk.UI.Pages;

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

        Loaded += OnLoaded;
        Unloaded += (_, _) =>
        {
            _controller.StatusChanged -= OnStatusChanged;
            _controller.ClientPresence -= OnPresence;
        };

        _controller.StatusChanged += OnStatusChanged;
        _controller.ClientPresence += OnPresence;

        PinText.Text = FormatPin(App.Settings.Pin);
        QrHintText.Text = BuildQrHint();
        ReceiveDirText.Text = App.Settings.SaveFolder;
        ReceiveDirSub.Text = Directory.Exists(App.Settings.SaveFolder)
            ? "Папка существует — файлы сохраняются автоматически"
            : "Будет создана при первом файле";

        StatusTitle.Text = "Ожидание подключения";
        StatusDetail.Text = "Запустите ZeroSynk на телефоне, чтобы начать";
        UpdateSyncButton();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        GenerateQr();
        UpdateStatusLine();
    }

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
        return $"zerosynk://pair?h={Uri.EscapeDataString(ips)}" +
               $"&p={App.Settings.Port}" +
               $"&t={Uri.EscapeDataString(App.Settings.Token)}" +
               $"&n={Uri.EscapeDataString(App.Settings.PcName)}";
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
            "ZeroSynk", MessageBoxButton.YesNo, MessageBoxImage.Question);
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
        if (App.Settings.ShowTrayIcon)
            TrayManager.ShowBalloon("Отправлено", "Текст передан на телефон");
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
            System.Windows.MessageBox.Show(ex.Message, "ZeroSynk", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }
}
