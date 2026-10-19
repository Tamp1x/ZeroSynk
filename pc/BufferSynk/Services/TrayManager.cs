using System.Drawing;
using System.Windows.Forms;

namespace BufferSynk.Services;

/// <summary>WinForms NotifyIcon tray with a custom dark-styled OwnerDraw menu.</summary>
public static class TrayManager
{
    private static NotifyIcon? _icon;
    private static ToolStripMenuItem? _syncItem;
    private static Action<string>? _command;

    // Colors matching the app theme
    private static readonly Color MenuBg = Color.FromArgb(28, 28, 30);
    private static readonly Color MenuHover = Color.FromArgb(42, 42, 45);
    private static readonly Color MenuBorder = Color.FromArgb(56, 56, 58);
    private static readonly Color TextColor = Color.FromArgb(242, 242, 247);
    private static readonly Color TextDim = Color.FromArgb(152, 152, 158);
    private static readonly Color Accent = Color.FromArgb(10, 132, 255);
    private static readonly Color SepColor = Color.FromArgb(56, 56, 58);

    public static bool Visible => _icon != null;

    public static void Init(Action<string> command)
    {
        if (_icon != null) return;
        _command = command;

        var menu = new ContextMenuStrip
        {
            Renderer = new DarkMenuRenderer(),
            Font = new Font("Segoe UI", 9.5f),
            ShowImageMargin = false,
            ShowCheckMargin = false,
            Padding = new Padding(4, 6, 4, 6),
            BackColor = MenuBg,
            ForeColor = TextColor,
            AutoSize = true,
            MaximumSize = new Size(260, 0),
        };

        var openItem = MakeItem("Открыть ZeroSynk", () => _command?.Invoke("open"), bold: true);
        _syncItem = MakeItem("Остановить синхронизацию", () => _command?.Invoke("toggleSync"));

        menu.Items.Add(openItem);
        menu.Items.Add(new ToolStripSeparator { Margin = new Padding(8, 4, 8, 4) });
        menu.Items.Add(_syncItem);
        menu.Items.Add(new ToolStripSeparator { Margin = new Padding(8, 4, 8, 4) });
        menu.Items.Add(MakeItem("Выход", () => _command?.Invoke("exit")));

        _icon = new NotifyIcon
        {
            Icon = LoadIcon(),
            Text = "ZeroSynk",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => _command?.Invoke("open");
    }

    private static ToolStripMenuItem MakeItem(string text, Action onClick, bool bold = false)
    {
        var item = new ToolStripMenuItem(text)
        {
            ForeColor = TextColor,
            Padding = new Padding(12, 7, 12, 7),
            AutoSize = true,
        };
        if (bold) item.Font = new Font(item.Font, System.Drawing.FontStyle.Bold);
        item.Click += (_, _) => onClick();
        return item;
    }

    private sealed class DarkMenuRenderer : ToolStripProfessionalRenderer
    {
        protected override void OnRenderItemBackground(ToolStripItemRenderEventArgs e)
        {
            var g = e.Graphics;
            var rect = new Rectangle(Point.Empty, e.Item.Size);
            var color = e.Item.Selected ? MenuHover : MenuBg;
            using var brush = new SolidBrush(color);
            g.FillRectangle(brush, rect);
        }

        protected override void OnRenderToolStripBackground(ToolStripRenderEventArgs e)
        {
            using var brush = new SolidBrush(MenuBg);
            e.Graphics.FillRectangle(brush, new Rectangle(Point.Empty, e.ToolStrip.Size));
            using var pen = new Pen(MenuBorder, 1);
            e.Graphics.DrawRectangle(pen, 0, 0, e.ToolStrip.Size.Width - 1, e.ToolStrip.Size.Height - 1);
        }

        protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
        {
            if (e.Item is ToolStripSeparator sep)
            {
                int y = sep.Height / 2;
                using var pen = new Pen(SepColor, 1);
                e.Graphics.DrawLine(pen, 12, y, e.Item.Width - 12, y);
            }
        }

        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = e.Item.Selected ? Color.White : TextColor;
            base.OnRenderItemText(e);
        }

        protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e)
        {
            // No submenus — suppress arrow
        }
    }

    public static void SetSyncEnabled(bool enabled)
    {
        if (_syncItem != null)
            _syncItem.Text = enabled ? "Остановить синхронизацию" : "Запустить синхронизацию";
    }

    public static void ShowBalloon(string title, string text)
    {
        if (_icon == null) return;
        _icon.BalloonTipTitle = title;
        _icon.BalloonTipText = text;
        _icon.ShowBalloonTip(4000);
    }

    public static void SetIconConnected(bool connected)
    {
        if (_icon == null) return;
        _icon.Text = connected ? "ZeroSynk — подключено" : "ZeroSynk — ожидание";
    }

    private static Icon LoadIcon()
    {
        try
        {
            var uri = new Uri("pack://application:,,,/Assets/icon.ico");
            using var stream = System.Windows.Application.GetResourceStream(uri)?.Stream;
            if (stream != null) return new Icon(stream);
        }
        catch { }
        return SystemIcons.Application;
    }

    public static void Dispose()
    {
        if (_icon != null)
        {
            _icon.Visible = false;
            _icon.Dispose();
            _icon = null;
        }
    }
}
