using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Effects;

namespace ZeroSynk.Services;

/// <summary>
/// Borderless auto-dismissing toast shown when the tray icon is hidden —
/// NotifyIcon balloons require a visible icon, so notifications must not die with it.
/// Stacks bottom-right above the taskbar; click opens the main window.
/// </summary>
public sealed class ToastWindow : Window
{
    private static readonly List<ToastWindow> Active = new();
    private const double ScreenMargin = 16;
    private const double Gap = 10;
    private const int MaxVisible = 5;
    private const int HideAfterMs = 4500;

    private readonly System.Threading.Timer _closeTimer;
    private bool _dismissed;

    private ToastWindow(string title, string body, string? openPath)
    {
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        ShowActivated = false;
        ShowInTaskbar = false;
        Topmost = true;
        ResizeMode = ResizeMode.NoResize;
        SizeToContent = SizeToContent.Height;
        Width = 340;

        var card = new Border
        {
            CornerRadius = new CornerRadius(12),
            Padding = new Thickness(14, 12, 14, 12),
        };
        card.SetResourceReference(Border.BackgroundProperty, "CardBrush");
        card.SetResourceReference(Border.BorderBrushProperty, "CardBorderBrush");
        card.SetResourceReference(Border.BorderThicknessProperty, "CardBorderThickness");
        card.Effect = new DropShadowEffect
        {
            BlurRadius = 18,
            ShadowDepth = 3,
            Opacity = 0.35,
            Color = Colors.Black,
        };

        var stack = new StackPanel();
        var titleText = new TextBlock
        {
            Text = title ?? "",
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            TextWrapping = TextWrapping.Wrap,
        };
        titleText.SetResourceReference(TextBlock.ForegroundProperty, "TextPrimary");
        stack.Children.Add(titleText);

        if (!string.IsNullOrEmpty(body))
        {
            var bodyText = new TextBlock
            {
                Text = body,
                FontSize = 12,
                Margin = new Thickness(0, 3, 0, 0),
                TextWrapping = TextWrapping.Wrap,
            };
            bodyText.SetResourceReference(TextBlock.ForegroundProperty, "TextSecondary");
            stack.Children.Add(bodyText);
        }

        // «Открыть» — explicit click only, never auto-starts the file (p. 16).
        if (!string.IsNullOrEmpty(openPath))
        {
            var openBtn = new Button
            {
                Content = "Открыть",
                Margin = new Thickness(0, 10, 0, 0),
                Padding = new Thickness(14, 4, 14, 4),
                HorizontalAlignment = HorizontalAlignment.Left,
                FontSize = 12,
            };
            if (TryFindResource("AccentBrush") is Brush accent) openBtn.Background = accent;
            if (TryFindResource("AccentText") is Brush onAccent) openBtn.Foreground = onAccent;
            var target = openPath;
            openBtn.Click += (_, _) =>
            {
                Notifier.OpenTarget(target);
                Dismiss(openApp: false);
            };
            stack.Children.Add(openBtn);
        }

        card.Child = stack;
        Content = card;
        Opacity = 0;

        MouseLeftButtonDown += (_, e) =>
        {
            if (e.OriginalSource is System.Windows.Controls.Primitives.ButtonBase) return;
            Dismiss(openApp: true);
        };
        Loaded += (_, _) =>
        {
            Relayout();
            BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180)));
        };

        // A toast with an action button stays longer so it can actually be clicked.
        var hideAfter = string.IsNullOrEmpty(openPath) ? HideAfterMs : HideAfterMs * 2 + 1000;
        _closeTimer = new System.Threading.Timer(
            _ =>
            {
                // Thread-pool callback: never let a dispatcher shutdown exception escape.
                try { Dispatcher.BeginInvoke((Action)(() => Dismiss(openApp: false))); } catch { }
            },
            null, hideAfter, Timeout.Infinite);
    }

    /// <summary>Show a toast (bottom-right stack). Call on the UI thread.</summary>
    public static void ShowToast(string title, string body, string? openPath = null)
    {
        var toast = new ToastWindow(title, body, openPath);
        Active.Add(toast);
        while (Active.Count > MaxVisible)
            Active[0].Dismiss(openApp: false);
        toast.Show();
    }

    private void Dismiss(bool openApp)
    {
        if (_dismissed) return;
        _dismissed = true;
        try { _closeTimer.Dispose(); } catch { }
        Active.Remove(this);
        Relayout();
        if (openApp)
            (System.Windows.Application.Current as App)?.ShowMainWindow();

        var fade = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(220));
        fade.Completed += (_, _) =>
        {
            try { Close(); } catch { }
        };
        BeginAnimation(OpacityProperty, fade);
    }

    /// <summary>Re-stacks the active toasts: newest sits at the bottom-right corner.</summary>
    private static void Relayout()
    {
        try
        {
            var area = SystemParameters.WorkArea;
            for (var i = Active.Count - 1; i >= 0; i--)
            {
                var t = Active[i];
                var fromBottom = Active.Count - 1 - i;
                t.Left = area.Right - t.ActualWidth - ScreenMargin;
                t.Top = area.Bottom - t.ActualHeight - ScreenMargin - fromBottom * (t.ActualHeight + Gap);
            }
        }
        catch { }
    }
}
