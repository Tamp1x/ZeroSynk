namespace ZeroSynk.Services;

/// <summary>Applies the selected design skin (zero | onyx | eclipx) everywhere:
/// color palette, window titles/icons, tray, Start menu shortcut and the
/// Explorer context-menu label. The phone is synced separately over WS.</summary>
public static class DesignService
{
    public static string Normalize(string? design) =>
        design is "onyx" or "eclipx" ? design : "zero";

    /// <summary>App display name for a design: ZeroSynk | OnyxSynk | Eclipx.</summary>
    public static string NameFor(string? design) => Normalize(design) switch
    {
        "onyx" => "OnyxSynk",
        "eclipx" => "Eclipx",
        _ => "ZeroSynk",
    };

    /// <summary>Display name for the currently applied design.</summary>
    public static string Current => NameFor(CurrentDesign);

    /// <summary>Design from live settings; falls back to a settings file read
    /// during early startup (before App.Settings is populated).</summary>
    private static string CurrentDesign
    {
        get
        {
            var s = App.Settings;
            if (s != null) return s.Design;
            try { return SettingsService.Load().Design; } catch { return "zero"; }
        }
    }

    /// <summary>Icon resource path for a design.</summary>
    public static string IconFileFor(string? design) => Normalize(design) switch
    {
        "onyx" => "Assets/icon_onyx.ico",
        "eclipx" => "Assets/icon_eclipx.ico",
        _ => "Assets/icon.ico",
    };

    /// <summary>Switches palette + all branded surfaces. Does not persist —
    /// callers save settings themselves.</summary>
    public static void Apply(string design)
    {
        design = Normalize(design);
        if (App.Settings != null)
            App.Settings.Design = design;

        // Swaps the palette dictionary; raises ThemeChanged on the UI thread,
        // which makes open windows refresh titles/backdrops.
        ThemeManager.Apply(App.Settings?.Theme ?? "system");

        var app = System.Windows.Application.Current;
        if (app == null) return;
        app.Dispatcher.Invoke(() =>
        {
            TrayManager.ApplyDesign();
            var exe = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(exe))
            {
                StartMenuService.Register(exe);   // rewrites the shortcut under the new name
                ContextMenuService.Register(exe); // refreshes the Explorer menu label
            }
        });
    }
}
