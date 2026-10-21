namespace BufferSynk.Services;

/// <summary>Ensures a Start menu shortcut exists so Windows Search finds the app by name.
/// Writes to the user's Programs folder (no admin rights) and rewrites the link on every
/// start, keeping the target in sync after the exe is moved or republished.</summary>
public static class StartMenuService
{
    public static void Register(string exePath)
    {
        try
        {
            var dir = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
            var lnkPath = Path.Combine(dir, "ZeroSynk.lnk");

            var shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) return;
            dynamic shell = Activator.CreateInstance(shellType)!;
            dynamic lnk = shell.CreateShortcut(lnkPath);
            lnk.TargetPath = exePath;
            lnk.WorkingDirectory = Path.GetDirectoryName(exePath) ?? "";
            lnk.IconLocation = $"{exePath},0";
            lnk.Description = "Clipboard and file sync between PC and phone over local network";
            lnk.Save();
        }
        catch { }
    }
}
