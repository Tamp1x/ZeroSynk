using Microsoft.Win32;

namespace BufferSynk.Services;

/// <summary>Registers/unregisters "Send to ZeroSynk" in the Windows Explorer context menu.
/// Writes to HKCU\Software\Classes so no admin rights are required.</summary>
public static class ContextMenuService
{
    private const string MenuLabel = "Отправить в ZeroSynk";
    private const string ClassesRoot = @"Software\Classes";

    public static void Register(string exePath)
    {
        try
        {
            // Files: HKCU\Software\Classes\*\shell\ZeroSynk
            AddKey($@"{ClassesRoot}\*\shell\ZeroSynk", exePath);
            // Folders: HKCU\Software\Classes\Directory\shell\ZeroSynk
            AddKey($@"{ClassesRoot}\Directory\shell\ZeroSynk", exePath);
        }
        catch { }
    }

    public static void Unregister()
    {
        try
        {
            RemoveKey($@"{ClassesRoot}\*\shell\ZeroSynk");
            RemoveKey($@"{ClassesRoot}\Directory\shell\ZeroSynk");
        }
        catch { }
    }

    private static void AddKey(string subKey, string exePath)
    {
        using var key = Registry.CurrentUser.CreateSubKey(subKey);
        if (key == null) return;
        key.SetValue(null, MenuLabel);
        key.SetValue("MUIVerb", MenuLabel);
        key.SetValue("Icon", $"\"{exePath}\",0");

        using var cmd = key.CreateSubKey("command");
        cmd?.SetValue(null, $"\"{exePath}\" --send-interactive \"%1\"");
    }

    private static void RemoveKey(string subKey)
    {
        Registry.CurrentUser.DeleteSubKeyTree(subKey, throwOnMissingSubKey: false);
    }
}
