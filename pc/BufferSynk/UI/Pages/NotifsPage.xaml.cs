using System.Windows;
using System.Windows.Controls;
using BufferSynk.Models;
using BufferSynk.Services;
using Brush = System.Windows.Media.Brush;
using Button = System.Windows.Controls.Button;
using TextBox = System.Windows.Controls.TextBox;
using Orientation = System.Windows.Controls.Orientation;

namespace BufferSynk.UI.Pages;

public partial class NotifsPage : System.Windows.Controls.UserControl
{
    private readonly AppController _controller;
    private const int MaxItems = 50;

    public NotifsPage(AppController controller)
    {
        _controller = controller;
        InitializeComponent();

        _controller.NotifReceived += OnNotifReceived;
        Unloaded += (_, _) => _controller.NotifReceived -= OnNotifReceived;
    }

    private void OnNotifReceived(WsMessage msg) => Dispatcher.BeginInvoke(() =>
    {
        if (NotifList == null) return;
        EmptyText.Visibility = Visibility.Collapsed;

        var card = BuildCard(msg);
        NotifList.Children.Insert(0, card);

        while (NotifList.Children.Count > MaxItems)
            NotifList.Children.RemoveAt(NotifList.Children.Count - 1);
    });

    private UIElement BuildCard(WsMessage msg)
    {
        var border = new Border
        {
            Padding = new Thickness(16, 12, 16, 12),
            BorderBrush = (Brush)FindResource("SepBrush"),
            BorderThickness = new Thickness(0, 0, 0, 1),
        };

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var left = new StackPanel();
        var headerRow = new StackPanel { Orientation = Orientation.Horizontal };
        var appName = new TextBlock
        {
            Text = msg.AppName ?? msg.Pkg ?? "—",
            FontWeight = FontWeights.SemiBold,
            FontSize = 12.5,
            Foreground = (Brush)FindResource("AccentBrush"),
        };
        var time = new TextBlock
        {
            Text = msg.Ts is > 0
                ? DateTimeOffset.FromUnixTimeSeconds(msg.Ts.Value).ToLocalTime().ToString("HH:mm")
                : "",
            FontSize = 11,
            Margin = new Thickness(8, 0, 0, 0),
            Foreground = (Brush)FindResource("TextTertiary"),
        };
        headerRow.Children.Add(appName);
        headerRow.Children.Add(time);
        left.Children.Add(headerRow);

        var title = new TextBlock
        {
            Text = msg.Title ?? "",
            FontSize = 13.5,
            FontWeight = FontWeights.Medium,
            Margin = new Thickness(0, 4, 0, 0),
            TextWrapping = TextWrapping.Wrap,
        };
        left.Children.Add(title);

        if (!string.IsNullOrEmpty(msg.Text))
        {
            var text = new TextBlock
            {
                Text = msg.Text,
                FontSize = 12.5,
                Foreground = (Brush)FindResource("TextSecondary"),
                Margin = new Thickness(0, 2, 0, 0),
                TextWrapping = TextWrapping.Wrap,
                MaxHeight = 80,
            };
            left.Children.Add(text);
        }

        grid.Children.Add(left);

        if (msg.Replyable == true)
        {
            var replyBtn = new Button
            {
                Content = "Ответить",
                Style = (Style)FindResource("SecondaryButton"),
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(12, 0, 0, 0),
                Padding = new Thickness(12, 4, 12, 4),
                FontSize = 12,
            };
            var key = msg.Key;
            replyBtn.Click += (_, _) => OpenReplyDialog(key);
            Grid.SetColumn(replyBtn, 1);
            grid.Children.Add(replyBtn);
        }

        border.Child = grid;
        return border;
    }

    private void OpenReplyDialog(string? key)
    {
        if (string.IsNullOrEmpty(key)) return;

        var dialog = new Window
        {
            Title = "Ответ на уведомление",
            Width = 420,
            Height = 180,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Owner = Window.GetWindow(this),
            ResizeMode = ResizeMode.NoResize,
        };

        var panel = new StackPanel { Margin = new Thickness(16) };
        var box = new TextBox
        {
            MinHeight = 60,
            VerticalContentAlignment = VerticalAlignment.Top,
            TextWrapping = TextWrapping.Wrap,
            AcceptsReturn = true,
            MaxLength = 500,
        };
        panel.Children.Add(new TextBlock { Text = "Введите ответ:", Margin = new Thickness(0, 0, 0, 8) });
        panel.Children.Add(box);

        var btnRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = System.Windows.HorizontalAlignment.Right,
            Margin = new Thickness(0, 12, 0, 0),
        };
        var cancel = new Button { Content = "Отмена", MinWidth = 80, Margin = new Thickness(0, 0, 8, 0) };
        var send = new Button { Content = "Отправить", MinWidth = 80, Style = (Style)FindResource("PrimaryButton") };
        btnRow.Children.Add(cancel);
        btnRow.Children.Add(send);
        panel.Children.Add(btnRow);

        dialog.Content = panel;

        cancel.Click += (_, _) => dialog.Close();
        send.Click += (_, _) =>
        {
            var text = box.Text.Trim();
            if (!string.IsNullOrEmpty(text))
            {
                _ = _controller.Server.BroadcastNotifAsync(WsMessage.NotifReply(key, text));
            }
            dialog.Close();
        };
        box.Focus();
        dialog.ShowDialog();
    }
}
