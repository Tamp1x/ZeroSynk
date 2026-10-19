using System.Windows;
using Microsoft.Win32;

namespace BufferSynk.Services;

/// <summary>Swaps the Light/Dark theme resource dictionary at runtime and
/// follows the system theme when the app setting is "system".</summary>
public static class ThemeManager
{
    private static ResourceDictionary? _current;
    private static bool _initialized;

    public static bool IsDark { get; private set; }

    /// <summary>Raised on the UI thread after the theme resources were swapped.</summary>
    public static event Action? ThemeChanged;

    public static void Init(string theme)
    {
        if (!_initialized)
        {
            SystemEvents.UserPreferenceChanged += (_, _) =>
            {
                if (App.Settings.Theme == "system")
                    Apply("system");
            };
            _initialized = true;
        }
        Apply(theme);
    }

    public static void Apply(string theme)
    {
        var dark = theme switch
        {
            "dark" => true,
            "light" => false,
            _ => IsSystemDark(),
        };
        IsDark = dark;

        var app = System.Windows.Application.Current;
        if (app == null) return;

        app.Dispatcher.Invoke(() =>
        {
            var uri = new Uri($"pack://application:,,,/Themes/{(dark ? "Dark" : "Light")}.xaml");
            var dict = new ResourceDictionary { Source = uri };
            var dicts = app.Resources.MergedDictionaries;

            if (_current != null)
            {
                var idx = dicts.IndexOf(_current);
                if (idx >= 0) dicts[idx] = dict;
                else dicts.Add(dict);
            }
            else
            {
                // Replace the placeholder dictionary declared in App.xaml.
                var replaced = false;
                for (var i = 0; i < dicts.Count; i++)
                {
                    var src = dicts[i].Source?.ToString();
                    if (src != null && src.Contains("/Themes/", StringComparison.OrdinalIgnoreCase))
                    {
                        dicts[i] = dict;
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) dicts.Add(dict);
            }

            _current = dict;
            ThemeChanged?.Invoke();
        });
    }

    private static bool IsSystemDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
        catch { return false; }
    }
}
