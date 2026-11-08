using System.Text.Json.Serialization;

namespace ZeroSynk.Models;

public sealed class AppSettings
{
    public int Port { get; set; } = 8765;
    public string SaveFolder { get; set; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "ZeroSynk");

    public bool StartWithWindows { get; set; }
    public bool StartMinimized { get; set; } = true;
    public bool ShowTrayIcon { get; set; } = true;
    public bool MinimizeToTray { get; set; } = true;
    /// <summary>Pop-up notifications (tray balloon or toast) — independent of the tray icon.</summary>
    public bool ShowNotifications { get; set; } = true;

    public bool SyncClipboardToPhone { get; set; } = true;
    public bool SyncClipboardFromPhone { get; set; } = true;
    public bool SyncImagesToPhone { get; set; } = true;
    /// <summary>Send files copied in Explorer (Ctrl+C) to the phone as file offers.</summary>
    public bool SyncClipboardFiles { get; set; }
    public bool AutoAcceptFiles { get; set; } = true;
    public bool PlaySound { get; set; } = true;

    public bool SyncEnabled { get; set; } = true;
    public bool MirrorPhoneNotifs { get; set; } = true;
    public bool MirrorWinNotifs { get; set; }
    public bool MediaToPhone { get; set; } = true;
    /// <summary>Show what the phone is playing on the PC and control it (reverse media).</summary>
    public bool ControlPhoneMedia { get; set; } = true;

    /// <summary>Delete previously received files when a new batch arrives.</summary>
    public bool AutoCleanReceived { get; set; } = true;
    /// <summary>How many recent batches to keep (0 = delete all old on each new receive).</summary>
    public int KeepRecentBatches { get; set; } = 1;

    /// <summary>system | light | dark</summary>
    public string Theme { get; set; } = "system";

    /// <summary>zero | onyx | eclipx — design skin shared with the phone.</summary>
    public string Design { get; set; } = "zero";

    public string Token { get; set; } = GenerateToken();
    public string Pin { get; set; } = GeneratePin();
    /// <summary>Optional session password: when set, every request must also carry it (pw=…).</summary>
    public string TransferPassword { get; set; } = "";
    public string PcName { get; set; } = Environment.MachineName;

    public string? PairedDeviceName { get; set; }
    public string? PairedDeviceId { get; set; }
    public DateTime? LastSeen { get; set; }

    public static string GenerateToken()
    {
        var bytes = new byte[24];
        using var rng = System.Security.Cryptography.RandomNumberGenerator.Create();
        rng.GetBytes(bytes);
        return Convert.ToHexString(bytes).ToLowerInvariant();
    }

    public static string GeneratePin()
    {
        var n = System.Security.Cryptography.RandomNumberGenerator.GetInt32(0, 1_000_000);
        return n.ToString("D6");
    }

    [JsonIgnore]
    public string OutboxFolder => Path.Combine(Path.GetTempPath(), "ZeroSynk", "outbox");
}
