using Microsoft.Win32;
using ZeroSynk.Shell;

namespace ZeroSynk.Services;

/// <summary>Registers/unregisters "Send to ZeroSynk" in the Windows Explorer context menu.
/// Writes to HKCU\Software\Classes so no admin rights are required.</summary>
public static class ContextMenuService
{
    private static string MenuLabel => $"Отправить в {DesignService.Current}";
    private const string ClassesRoot = @"Software\Classes";
    private const string ExplorerCommandClsid = "2B9B4D83-167B-4F74-AC1E-3C9454AD6C2A";

    public static void Register(string exePath)
    {
        try
        {
            // Files: HKCU\Software\Classes\*\shell\ZeroSynk
            AddKey($@"{ClassesRoot}\*\shell\ZeroSynk", exePath);
            // Folders: HKCU\Software\Classes\Directory\shell\ZeroSynk
            AddKey($@"{ClassesRoot}\Directory\shell\ZeroSynk", exePath);
            RegisterExplorerCommand(exePath);
        }
        catch { }
    }

    public static void Unregister()
    {
        try
        {
            RemoveKey($@"{ClassesRoot}\*\shell\ZeroSynk");
            RemoveKey($@"{ClassesRoot}\Directory\shell\ZeroSynk");
            UnregisterExplorerCommand();
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

    private static void RegisterExplorerCommand(string exePath)
    {
        var comHost = Path.Combine(Path.GetDirectoryName(exePath)!, "ZeroSynk.ShellExtension.comhost.dll");
        if (!File.Exists(comHost)) return;
        using var clsid = Registry.CurrentUser.CreateSubKey($@"{ClassesRoot}\CLSID\{ExplorerCommandClsid}");
        clsid?.SetValue(null, "ZeroSynk Explorer command");
        using var inproc = clsid?.CreateSubKey("InprocServer32");
        inproc?.SetValue(null, comHost);
        inproc?.SetValue("ThreadingModel", "Apartment");
        AddExplorerVerb($@"{ClassesRoot}\*\shell\ZeroSynkModern");
        AddExplorerVerb($@"{ClassesRoot}\Directory\shell\ZeroSynkModern");
    }

    private static void AddExplorerVerb(string path)
    {
        using var verb = Registry.CurrentUser.CreateSubKey(path);
        verb?.SetValue("ExplorerCommandHandler", $"{{{ExplorerCommandClsid}}}");
        verb?.SetValue("MUIVerb", MenuLabel);
        verb?.SetValue("Icon", $"\"{Environment.ProcessPath}\",0");
    }

    private static void UnregisterExplorerCommand()
    {
        RemoveKey($@"{ClassesRoot}\*\shell\ZeroSynkModern");
        RemoveKey($@"{ClassesRoot}\Directory\shell\ZeroSynkModern");
        RemoveKey($@"{ClassesRoot}\CLSID\{ExplorerCommandClsid}");
    }
}
