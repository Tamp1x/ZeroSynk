using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace ZeroSynk.Services;

/// <summary>
/// Event-driven clipboard watcher via AddClipboardFormatListener (Win7+).
/// Fires only on WM_CLIPBOARDUPDATE — no polling.
/// </summary>
public sealed class ClipboardWatcher : IDisposable
{
    private const int WM_CLIPBOARDUPDATE = 0x031D;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool AddClipboardFormatListener(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);

    private HwndSource? _source;
    private bool _disposed;

    public event Action? ClipboardChanged;

    public void Start()
    {
        if (_source != null) return;

        var parameters = new HwndSourceParameters("ZeroSynkClipboardHook")
        {
            Width = 0,
            Height = 0,
            PositionX = 0,
            PositionY = 0,
            WindowStyle = unchecked((int)0x80000000), // WS_POPUP
        };
        _source = new HwndSource(parameters);
        _source.AddHook(WndProc);
        AddClipboardFormatListener(_source.Handle);
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_CLIPBOARDUPDATE)
        {
            ClipboardChanged?.Invoke();
            handled = true;
        }
        return IntPtr.Zero;
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        if (_source != null)
        {
            RemoveClipboardFormatListener(_source.Handle);
            _source.RemoveHook(WndProc);
            _source.Dispose();
            _source = null;
        }
    }
}

/// <summary>Reads text or PNG image from the Windows clipboard with retries (clipboard can be locked).</summary>
public static class ClipboardReader
{
    public static string? ReadText()
    {
        for (var i = 0; i < 5; i++)
        {
            try
            {
                if (System.Windows.Clipboard.ContainsText())
                {
                    var t = System.Windows.Clipboard.GetText();
                    return string.IsNullOrEmpty(t) ? null : t;
                }
                return null;
            }
            catch
            {
                Thread.Sleep(30 * (i + 1));
            }
        }
        return null;
    }

    public static byte[]? ReadImagePng()
    {
        for (var i = 0; i < 5; i++)
        {
            try
            {
                if (System.Windows.Clipboard.ContainsImage())
                {
                    var img = System.Windows.Clipboard.GetImage();
                    if (img == null) return null;
                    using var ms = new MemoryStream();
                    var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder();
                    encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(img));
                    encoder.Save(ms);
                    var bytes = ms.ToArray();
                    if (bytes.Length > Models.Protocol.MaxImageBytes) return null;
                    return bytes;
                }
                return null;
            }
            catch
            {
                Thread.Sleep(30 * (i + 1));
            }
        }
        return null;
    }

    /// <summary>Rich text (CF_HTML) currently on the clipboard, null when there is none.</summary>
    public static string? ReadHtml()
    {
        for (var i = 0; i < 5; i++)
        {
            try
            {
                if (!System.Windows.Clipboard.ContainsText(DataFormats.Html)) return null;
                if (System.Windows.Clipboard.GetDataObject()?.GetData(DataFormats.Html) is not string raw)
                    return null;
                return ExtractHtml(raw);
            }
            catch
            {
                Thread.Sleep(30 * (i + 1));
            }
        }
        return null;
    }

    /// <summary>File paths dropped on the clipboard (Ctrl+C in Explorer), null when there are none.</summary>
    public static List<string>? ReadFilePaths()
    {
        for (var i = 0; i < 5; i++)
        {
            try
            {
                if (!System.Windows.Clipboard.ContainsFileDropList()) return null;
                var list = System.Windows.Clipboard.GetFileDropList();
                if (list == null || list.Count == 0) return null;
                var result = new List<string>();
                foreach (var p in list)
                    if (!string.IsNullOrWhiteSpace(p)) result.Add(p);
                return result.Count > 0 ? result : null;
            }
            catch
            {
                Thread.Sleep(30 * (i + 1));
            }
        }
        return null;
    }

    /// <summary>Pulls the HTML document out of a CF_HTML payload (byte offsets in an ASCII header).</summary>
    public static string? ExtractHtml(string cfHtml)
    {
        var start = HeaderOffset(cfHtml, "StartHTML");
        var end = HeaderOffset(cfHtml, "EndHTML");
        if (start < 0 || end <= start) return null;
        var bytes = Encoding.UTF8.GetBytes(cfHtml);
        if (start >= bytes.Length) return null;
        if (end > bytes.Length) end = bytes.Length;
        var html = Encoding.UTF8.GetString(bytes, start, end - start).Trim();
        return html.Length == 0 ? null : html;
    }

    private static int HeaderOffset(string s, string name)
    {
        var i = s.IndexOf(name, StringComparison.OrdinalIgnoreCase);
        if (i < 0) return -1;
        i += name.Length;
        while (i < s.Length && !char.IsDigit(s[i])) i++;
        var begin = i;
        while (i < s.Length && char.IsDigit(s[i])) i++;
        if (begin == i) return -1;
        return int.TryParse(s[begin..i], out var v) ? v : -1;
    }

    /// <summary>Wraps plain HTML into the CF_HTML format Windows expects on the clipboard.</summary>
    public static string BuildCfHtml(string html)
    {
        const string head = "<html><body>\r\n<!--StartFragment-->";
        const string tail = "<!--EndFragment-->\r\n</body></html>";
        // The header is fixed-width (10-digit offsets) so its byte length is stable.
        var headerLen = Encoding.UTF8.GetByteCount(
            "Version:0.9\r\nStartHTML:0000000000\r\nEndHTML:0000000000\r\n" +
            "StartFragment:0000000000\r\nEndFragment:0000000000\r\n");
        var fragStart = headerLen + Encoding.UTF8.GetByteCount(head);
        var fragEnd = fragStart + Encoding.UTF8.GetByteCount(html);
        var total = fragEnd + Encoding.UTF8.GetByteCount(tail);
        var sb = new StringBuilder(headerLen + html.Length + head.Length + tail.Length);
        sb.Append("Version:0.9\r\n");
        sb.Append($"StartHTML:{headerLen:0000000000}\r\n");
        sb.Append($"EndHTML:{total:0000000000}\r\n");
        sb.Append($"StartFragment:{fragStart:0000000000}\r\n");
        sb.Append($"EndFragment:{fragEnd:0000000000}\r\n");
        sb.Append(head).Append(html).Append(tail);
        return sb.ToString();
    }
}
