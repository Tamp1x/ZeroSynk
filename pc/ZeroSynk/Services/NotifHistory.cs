using System.Text.Json;
using ZeroSynk.Models;

namespace ZeroSynk.Services;

/// <summary>One entry of the notification history (phone- or PC-origin).</summary>
public sealed record NotifRecord(string Key, string AppName, string Title, string Text, long Ts, string Origin);

/// <summary>
/// Notification history (p. 20): the page used to keep its 50 cards in a StackPanel,
/// which died with the window — this stores them as JSON under %APPDATA%\ZeroSynk.
/// </summary>
public static class NotifHistory
{
    private const int Max = 50;

    private static readonly object _lock = new();
    private static List<NotifRecord>? _items;

    private static string StorePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk", "notifhist.json");

    /// <summary>Stores a notification (same key replaces its older copy) and drops the oldest beyond 50.</summary>
    public static void Add(WsMessage msg)
    {
        var title = msg.Title?.Trim() ?? "";
        var text = msg.Text?.Trim() ?? "";
        if (title.Length == 0 && text.Length == 0) return;

        var key = msg.Key ?? $"{msg.AppName}|{title}";
        var rec = new NotifRecord(
            key,
            msg.AppName ?? msg.Pkg ?? "—",
            title,
            text,
            msg.Ts is > 0 ? msg.Ts.Value : DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            msg.Origin ?? "phone");

        lock (_lock)
        {
            var items = Load();
            items.RemoveAll(i => string.Equals(i.Key, rec.Key, StringComparison.Ordinal));
            items.Insert(0, rec);
            while (items.Count > Max) items.RemoveAt(items.Count - 1);
            Save(items);
        }
    }

    /// <summary>Newest-first copy of the whole history (never null).</summary>
    public static List<NotifRecord> Snapshot()
    {
        lock (_lock)
        {
            return Load().ToList();
        }
    }

    public static void Clear()
    {
        lock (_lock)
        {
            Save(new List<NotifRecord>());
        }
    }

    private static List<NotifRecord> Load()
    {
        if (_items != null) return _items;
        try
        {
            if (File.Exists(StorePath))
            {
                var items = JsonSerializer.Deserialize<List<NotifRecord>>(File.ReadAllText(StorePath), Protocol.Json);
                if (items != null)
                {
                    _items = items;
                    return _items;
                }
            }
        }
        catch { }
        _items = new List<NotifRecord>();
        return _items;
    }

    private static void Save(List<NotifRecord> items)
    {
        _items = items;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(StorePath)!);
            File.WriteAllText(StorePath, JsonSerializer.Serialize(items, Protocol.Json));
        }
        catch { }
    }
}
