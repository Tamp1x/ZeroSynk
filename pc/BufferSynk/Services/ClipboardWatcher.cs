using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace BufferSynk.Services;

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

        var parameters = new HwndSourceParameters("BufferSynkClipboardHook")
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
}
