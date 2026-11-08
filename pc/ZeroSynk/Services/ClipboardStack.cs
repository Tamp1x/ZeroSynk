using System.Text.Json;
using ZeroSynk.Models;

namespace ZeroSynk.Services;

/// <summary>
/// Rolling stack of the most recent clipboard texts on the PC (up to 10 entries).
/// Seeded to the phone on every connect so its clipboard history starts populated
/// even though Android can't read the clipboard from a background service.
/// </summary>
public static class ClipboardStack
{
    private const int MaxEntries = 10;
    private const int MaxTextChars = 20_000;

    private static readonly object _lock = new();
    private static List<ClipboardItem>? _items;

    private static string StackPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk", "clipstack.json");

    /// <summary>Remembers a clipboard entry (deduped by hash, newest first).</summary>
    public static void Push(string kind, string text, string hash)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        if (text.Length > MaxTextChars) text = text[..MaxTextChars];
        lock (_lock)
        {
            var items = Load();
            items.RemoveAll(i => string.Equals(i.Hash, hash, StringComparison.OrdinalIgnoreCase));
            items.Insert(0, new ClipboardItem { Kind = kind, Text = text, Hash = hash, Ts = DateTimeOffset.UtcNow.ToUnixTimeSeconds() });
            while (items.Count > MaxEntries) items.RemoveAt(items.Count - 1);
            Save(items);
        }
    }

    /// <summary>Newest-first copy of the stack (never null).</summary>
    public static List<ClipboardItem> Snapshot()
    {
        lock (_lock)
        {
            return Load().Select(i => new ClipboardItem { Kind = i.Kind, Text = i.Text, Hash = i.Hash, Ts = i.Ts }).ToList();
        }
    }

    private static List<ClipboardItem> Load()
    {
        if (_items != null) return _items;
        try
        {
            if (File.Exists(StackPath))
            {
                var items = JsonSerializer.Deserialize<List<ClipboardItem>>(File.ReadAllText(StackPath), Protocol.Json);
                if (items != null)
                {
                    _items = items;
                    return _items;
                }
            }
        }
        catch { }
        _items = new List<ClipboardItem>();
        return _items;
    }

    private static void Save(List<ClipboardItem> items)
    {
        _items = items;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(StackPath)!);
            File.WriteAllText(StackPath, JsonSerializer.Serialize(items, Protocol.Json));
        }
        catch { }
    }
}
