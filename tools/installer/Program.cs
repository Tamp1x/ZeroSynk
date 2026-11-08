using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;

const string releasesApi = "https://api.github.com/repos/Tamp1x/ZeroSynk/releases/latest";
const string packageName = "ZeroSynk-Windows-x64.zip";
const long maxPackageBytes = 512L * 1024 * 1024;
const string desktopRuntimeUrl = "https://aka.ms/dotnet/8.0/windowsdesktop-runtime-win-x64.exe";

using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
http.DefaultRequestHeaders.UserAgent.ParseAdd("ZeroSynk-Setup/1.0");
var work = Path.Combine(Path.GetTempPath(), "ZeroSynk-Setup", Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(work);

try
{
    Console.WriteLine("ZeroSynk Setup — installing the latest version from GitHub\n");
    Console.WriteLine("Checking the latest release…");
    using var response = await http.GetAsync(releasesApi);
    response.EnsureSuccessStatusCode();
    using var json = JsonDocument.Parse(await response.Content.ReadAsStreamAsync());
    var root = json.RootElement;
    var tag = root.GetProperty("tag_name").GetString() ?? "latest";
    var assets = root.GetProperty("assets").EnumerateArray().ToArray();
    var asset = assets.FirstOrDefault(a => string.Equals(
        a.GetProperty("name").GetString(), packageName, StringComparison.OrdinalIgnoreCase));
    if (asset.ValueKind == JsonValueKind.Undefined)
        throw new InvalidOperationException($"Release {tag} does not contain {packageName}. The project maintainer must upload this Windows package to GitHub Releases.");

    var url = asset.GetProperty("browser_download_url").GetString();
    if (!Uri.TryCreate(url, UriKind.Absolute, out var packageUri) || packageUri.Scheme != Uri.UriSchemeHttps)
        throw new InvalidOperationException("GitHub returned an invalid package URL.");
    var expectedDigest = asset.TryGetProperty("digest", out var digestElement) ? digestElement.GetString() : null;
    var packageFile = Path.Combine(work, packageName);
    Console.WriteLine($"Latest release: {tag}. Downloading Windows package…");
    using (var packageResponse = await http.GetAsync(packageUri, HttpCompletionOption.ResponseHeadersRead))
    {
        packageResponse.EnsureSuccessStatusCode();
        if (packageResponse.Content.Headers.ContentLength is > maxPackageBytes)
            throw new InvalidOperationException("The Windows package is larger than the allowed download size.");
        await using var output = File.Create(packageFile);
        await using var input = await packageResponse.Content.ReadAsStreamAsync();
        var buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = await input.ReadAsync(buffer)) > 0)
        {
            total += read;
            if (total > maxPackageBytes) throw new InvalidOperationException("The Windows package exceeded the allowed download size.");
            await output.WriteAsync(buffer.AsMemory(0, read));
        }
    }
    if (!string.IsNullOrWhiteSpace(expectedDigest))
    {
        var expected = expectedDigest.StartsWith("sha256:", StringComparison.OrdinalIgnoreCase)
            ? expectedDigest[7..] : expectedDigest;
        await using var packageStream = File.OpenRead(packageFile);
        var actual = Convert.ToHexString(await SHA256.HashDataAsync(packageStream));
        if (!actual.Equals(expected, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("The package checksum did not match the GitHub release digest.");
    }

    await EnsureDesktopRuntime(http, work, desktopRuntimeUrl);

    var installDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "ZeroSynk");
    var stageDir = Path.Combine(work, "unpacked");
    Directory.CreateDirectory(stageDir);
    ZipFile.ExtractToDirectory(packageFile, stageDir, overwriteFiles: true);
    if (!File.Exists(Path.Combine(stageDir, "ZeroSynk.exe")) ||
        !File.Exists(Path.Combine(stageDir, "ZeroSynk.ShellExtension.comhost.dll")))
        throw new InvalidOperationException("The release package is incomplete: required ZeroSynk files are missing.");
    var packagedVersionPath = Path.Combine(stageDir, "version.txt");
    if (!File.Exists(packagedVersionPath) || !string.Equals(
        File.ReadAllText(packagedVersionPath).Trim().TrimStart('v'), tag.Trim().TrimStart('v'), StringComparison.OrdinalIgnoreCase))
        throw new InvalidOperationException("The Windows package version does not match the latest GitHub release.");

    Console.WriteLine($"Installing ZeroSynk {tag} to {installDir}…");
    foreach (var runningApp in Process.GetProcessesByName("ZeroSynk"))
    {
        using (runningApp)
        {
            runningApp.CloseMainWindow();
            if (!runningApp.WaitForExit(10_000))
                throw new InvalidOperationException("Close ZeroSynk and run the installer again to finish updating.");
        }
    }
    Directory.CreateDirectory(installDir);
    foreach (var file in Directory.EnumerateFiles(stageDir, "*", SearchOption.AllDirectories))
    {
        var relative = Path.GetRelativePath(stageDir, file);
        var target = Path.Combine(installDir, relative);
        Directory.CreateDirectory(Path.GetDirectoryName(target)!);
        File.Copy(file, target, overwrite: true);
    }
    File.WriteAllText(Path.Combine(installDir, ".zerosynk-install"), tag);
    try { CreateStartMenuShortcut(Path.Combine(installDir, "ZeroSynk.exe")); }
    catch (Exception ex) { Console.WriteLine($"Start menu shortcut was not created: {ex.Message}"); }

    Console.WriteLine("Installation complete. Starting ZeroSynk…");
    Process.Start(new ProcessStartInfo(Path.Combine(installDir, "ZeroSynk.exe")) { UseShellExecute = true });
}
catch (Exception ex)
{
    Console.Error.WriteLine($"\nInstallation failed: {ex.Message}");
    Console.WriteLine("Press Enter to close this window.");
    Console.ReadLine();
    Environment.ExitCode = 1;
}
finally
{
    try { Directory.Delete(work, recursive: true); } catch { }
}

static async Task EnsureDesktopRuntime(HttpClient http, string work, string runtimeUrl)
{
    var programFiles = Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles);
    var runtimes = Path.Combine(programFiles, "dotnet", "shared", "Microsoft.WindowsDesktop.App");
    if (Directory.Exists(runtimes) && Directory.EnumerateDirectories(runtimes, "8.*").Any()) return;

    Console.WriteLine("Installing the Microsoft .NET 8 Desktop Runtime (required once)…");
    var installer = Path.Combine(work, "windowsdesktop-runtime.exe");
    await using (var stream = await http.GetStreamAsync(runtimeUrl))
    await using (var output = File.Create(installer))
        await stream.CopyToAsync(output);

    using var process = Process.Start(new ProcessStartInfo(installer, "/install /quiet /norestart")
    {
        UseShellExecute = true,
        Verb = "runas",
    }) ?? throw new InvalidOperationException("Could not launch the .NET Desktop Runtime installer.");
    await process.WaitForExitAsync();
    if (process.ExitCode is not (0 or 3010))
        throw new InvalidOperationException($"The .NET Desktop Runtime installer exited with code {process.ExitCode}.");
}

static void CreateStartMenuShortcut(string executable)
{
    var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.StartMenu), "Programs", "ZeroSynk");
    Directory.CreateDirectory(folder);
    var shortcutPath = Path.Combine(folder, "ZeroSynk.lnk");
    var shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host is unavailable; shortcut could not be created.");
    dynamic shell = Activator.CreateInstance(shellType)!;
    dynamic shortcut = shell.CreateShortcut(shortcutPath);
    shortcut.TargetPath = executable;
    shortcut.WorkingDirectory = Path.GetDirectoryName(executable);
    shortcut.IconLocation = $"{executable},0";
    shortcut.Save();
    MarshalRelease(shortcut);
    MarshalRelease(shell);
}

static void MarshalRelease(object instance)
{
    if (System.Runtime.InteropServices.Marshal.IsComObject(instance))
        System.Runtime.InteropServices.Marshal.FinalReleaseComObject(instance);
}
