using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ZeroSynk.Models;
using ZeroSynk.Services;

namespace ZeroSynk.UI.Pages;

public partial class HistoryPage : System.Windows.Controls.UserControl
{
    private readonly AppController _controller;

    public HistoryPage(AppController controller)
    {
        _controller = controller;
        InitializeComponent();
        Loaded += (_, _) => _controller.HistoryAdded += OnEntryAdded;
        Unloaded += (_, _) =>
        {
            _controller.HistoryAdded -= OnEntryAdded;
            ThemeManager.ThemeChanged -= OnThemeChanged;
        };
        ThemeManager.ThemeChanged += OnThemeChanged;
        Refresh();
    }

    private void OnThemeChanged() => Dispatcher.BeginInvoke(Refresh);

    private void OnEntryAdded(HistoryEntry _) => Dispatcher.BeginInvoke(Refresh);

    private void Refresh()
    {
        var items = HistoryService.Entries.Select(e => new HistoryItem
        {
            Entry = e,
            Preview = e.Preview,
            Meta = $"{DirLabel(e.Direction)} · {KindLabel(e.Kind)}" +
                   (e.Size > 0 ? $" · {SyncServer.FormatSize(e.Size)}" : "") +
                   $" · {e.Time:HH:mm:ss}",
            Icon = e.Kind switch
            {
                "file" => e.Direction == "toPhone" ? "↑" : "↓",
                "image" => "🖼",
                _ => "T",
            },
            StatusText = e.Status == "failed" ? "ошибка" : e.Status == "received" ? "получено" : "отправлено",
            StatusBrush = (System.Windows.Media.Brush)FindResource(e.Status == "failed" ? "RedBrush" : "GreenBrush"),
            StatusBg = new SolidColorBrush(System.Windows.Media.Color.FromArgb(0x22, 0x34, 0xC7, 0x59)),
            IconBg = (System.Windows.Media.Brush)FindResource(e.Direction == "toPhone" ? "AccentSoft" : "HoverBrush"),
            IconFg = (System.Windows.Media.Brush)FindResource(e.Direction == "toPhone" ? "AccentBrush" : "TextSecondary"),
        }).ToList();

        List.ItemsSource = items;
        EmptyText.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private static string DirLabel(string d) => d == "toPhone" ? "→ телефон" : "← телефон";
    private static string KindLabel(string k) => k switch
    {
        "file" => "файл",
        "image" => "картинка",
        _ => "текст",
    };

    private void OnClear(object sender, RoutedEventArgs e)
    {
        HistoryService.Clear();
        Refresh();
    }

    // ---- repeat from history (p. 17) ----

    private void OnRowDoubleClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        if (List.SelectedItem is not HistoryItem item) return;
        Act(item);
    }

    private void OnRepeatItem(object sender, RoutedEventArgs e)
        => Act((sender as FrameworkElement)?.Tag as HistoryItem, copy: false);

    private void OnCopyItem(object sender, RoutedEventArgs e)
        => Act((sender as FrameworkElement)?.Tag as HistoryItem, copy: true);

    /// <summary>toPhone rows are re-sent, fromPhone rows are copied back to the PC clipboard.</summary>
    private void Act(HistoryItem? item, bool? copy = null)
    {
        if (item?.Entry == null) return;
        var e = item.Entry;
        var doCopy = copy ?? e.Direction == "fromPhone";
        if (doCopy) _controller.CopyFromHistory(e);
        else _controller.RepeatFromHistory(e);
    }

    private sealed class HistoryItem
    {
        public HistoryEntry? Entry { get; set; }
        public string Preview { get; set; } = "";
        public string Meta { get; set; } = "";
        public string Icon { get; set; } = "";
        public string StatusText { get; set; } = "";
        public System.Windows.Media.Brush StatusBrush { get; set; } = System.Windows.Media.Brushes.Gray;
        public System.Windows.Media.Brush StatusBg { get; set; } = System.Windows.Media.Brushes.Transparent;
        public System.Windows.Media.Brush IconBg { get; set; } = System.Windows.Media.Brushes.Gray;
        public System.Windows.Media.Brush IconFg { get; set; } = System.Windows.Media.Brushes.Gray;
    }
}
