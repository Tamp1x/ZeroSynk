using System.IO;
using System.Text.Json;
using BufferSynk.Models;

namespace BufferSynk.Services;

public static class SettingsService
{
    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "BufferSynk");
    private static readonly string Path_ = Path.Combine(Dir, "settings.json");

    private static readonly JsonSerializerOptions Opts = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(Path_))
            {
                var s = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(Path_), Opts);
                if (s != null)
                {
                    if (s.Port is < 1024 or > 65535) s.Port = 8765;
                    if (string.IsNullOrWhiteSpace(s.Token)) s.Token = AppSettings.GenerateToken();
                    if (string.IsNullOrWhiteSpace(s.Pin)) s.Pin = AppSettings.GeneratePin();
                    if (string.IsNullOrWhiteSpace(s.SaveFolder))
                        s.SaveFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "ZeroSynk");
                    Save(s); // normalize on-disk format
                    return s;
                }
            }
        }
        catch { /* corrupted -> fall through to defaults */ }

        var fresh = new AppSettings();
        Save(fresh);
        return fresh;
    }

    public static void Save(AppSettings s)
    {
        try
        {
            Directory.CreateDirectory(Dir);
            var tmp = Path_ + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(s, Opts));
            File.Move(tmp, Path_, true);
        }
        catch { }
    }

    public static string HistoryPath => Path.Combine(Dir, "history.json");
}
