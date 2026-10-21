using System.IO;
using System.Windows;
using BufferSynk.Services;

namespace BufferSynk.UI;

public partial class SendWindow : Window
{
    private readonly List<string> _files;

    public SendWindow(List<string> files)
    {
        _files = files;
        InitializeComponent();

        var names = files.Select(Path.GetFileName).Where(n => !string.IsNullOrEmpty(n)).ToList();
        FileListText.Text = string.Join("\n", names.Take(10));
        if (names.Count > 10) FileListText.Text += $"\n… и ещё {names.Count - 10}";

        SubText.Text = names.Count == 1
            ? $"1 файл — {SyncServer.FormatSize(new FileInfo(files[0]).Length)}"
            : $"{names.Count} файлов";

        Loaded += (_, _) => SendBtn.Focus();

        // No title bar — drag from anywhere except interactive elements
        MouseLeftButtonDown += (_, e) =>
        {
            if (e.OriginalSource is System.Windows.Controls.Button) return;
            try { DragMove(); } catch { }
        };
    }

    private void OnSend(object sender, RoutedEventArgs e)
    {
        foreach (var f in _files)
        {
            if (File.Exists(f))
                App.Controller.SendFile(f);
        }
        if (App.Settings.ShowTrayIcon)
            TrayManager.ShowBalloon("Отправка на телефон", $"{_files.Count} файл(ов)");
        Close();
    }

    private void OnCancel(object sender, RoutedEventArgs e) => Close();
}
