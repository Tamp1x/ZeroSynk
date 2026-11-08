using System.Collections.ObjectModel;
using System.IO;
using System.Text.Json;
using ZeroSynk.Models;

namespace ZeroSynk.Services;

public static class HistoryService
{
    private const int MaxEntries = 50;

    private static readonly JsonSerializerOptions Opts = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    public static ObservableCollection<HistoryEntry> Entries { get; } = new();

    public static void Load()
    {
        try
        {
            var path = SettingsService.HistoryPath;
            if (!File.Exists(path)) return;
            var store = JsonSerializer.Deserialize<HistoryStore>(File.ReadAllText(path), Opts);
            if (store?.Entries == null) return;
            Entries.Clear();
            foreach (var e in store.Entries.OrderByDescending(e => e.Time).Take(MaxEntries))
                Entries.Add(e);
        }
        catch { }
    }

    public static void Add(HistoryEntry entry)
    {
        Entries.Insert(0, entry);
        while (Entries.Count > MaxEntries)
            Entries.RemoveAt(Entries.Count - 1);
        Persist();
    }

    public static void Persist()
    {
        try
        {
            var store = new HistoryStore { Entries = Entries.ToList() };
            File.WriteAllText(SettingsService.HistoryPath, JsonSerializer.Serialize(store, Opts));
        }
        catch { }
    }

    public static void Clear()
    {
        Entries.Clear();
        try { File.Delete(SettingsService.HistoryPath); } catch { }
    }
}
