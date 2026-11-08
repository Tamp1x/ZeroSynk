using System.IO;
using System.Windows;
using ZeroSynk.Models;
using ZeroSynk.Services;

namespace ZeroSynk;

public partial class App : System.Windows.Application
{
    private Mutex? _singleInstance;
    private AppController? _controller;
    private MainWindow? _mainWindow;

    public static AppSettings Settings { get; private set; } = null!;
    public static AppController Controller => _instance._controller ?? throw new InvalidOperationException("Not started");
    private static App _instance = null!;

    private bool _startMinimized;
    private bool _exitRequested;

    private const string ShowEventName = @"Local\ZeroSynkShow";

    private static string SendQueueDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk", "send-queue");

    private static string InteractiveQueueDir => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk", "interactive-queue");

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        _instance = this;

        MigrateLegacyDataDir();

        // --register-context-menu: install shell entries and exit
        if (e.Args.Contains("--register-context-menu", StringComparer.OrdinalIgnoreCase))
        {
            var exe = Environment.ProcessPath ?? "ZeroSynk.exe";
            ContextMenuService.Register(exe);
            Shutdown(0);
            return;
        }
        if (e.Args.Contains("--unregister-context-menu", StringComparer.OrdinalIgnoreCase))
        {
            ContextMenuService.Unregister();
            Shutdown(0);
            return;
        }

        bool isInteractive = e.Args.Contains("--send-interactive", StringComparer.OrdinalIgnoreCase);
        var collected = CollectSendArgs(e.Args, isInteractive ? "--send-interactive" : "--send");

        if (isInteractive)
            EnqueueFiles(InteractiveQueueDir, collected);
        else
            EnqueueFiles(SendQueueDir, collected);

        _singleInstance = new Mutex(true, @"Local\ZeroSynkSingleInstance", out var isNew);
        if (!isNew)
        {
            // Another instance running: signal it to drain queues / show window, then quit.
            try
            {
                using var ev = EventWaitHandle.OpenExisting(ShowEventName);
                ev.Set();
            }
            catch { }
            Shutdown(0);
            return;
        }

        _startMinimized = e.Args.Contains("--minimized") || collected.Count > 0;

        // Keep Explorer context-menu entries in sync (harmless if run repeatedly)
        {
            var exePath = Environment.ProcessPath;
            if (!string.IsNullOrEmpty(exePath))
            {
                ContextMenuService.Register(exePath);
                // Start menu shortcut so the app is findable via Windows Search
                StartMenuService.Register(exePath);
            }
        }

        DispatcherUnhandledException += (_, args) =>
        {
            LogError(args.Exception);
            args.Handled = true;
        };

        Settings = SettingsService.Load();
        HistoryService.Load();
        ThemeManager.Init(Settings.Theme);

        // Persist the actual autostart state on boot (also rewrites the path
        // after the exe was renamed/moved).
        AutostartService.SetEnabled(Settings.StartWithWindows);

        _controller = new AppController(Settings);

        if (Settings.ShowTrayIcon)
            TrayManager.Init(OnTrayCommand);

        _mainWindow = new MainWindow(_controller);

        try
        {
            await _controller.StartAsync();
        }
        catch (Exception ex)
        {
            System.Windows.MessageBox.Show(
                $"Не удалось запустить локальный сервер:\n\n{ex.Message}\n\n" +
                $"Проверьте, не занят ли порт {Settings.Port} другим приложением. Порт можно изменить в настройках.",
                DesignService.Current,
                MessageBoxButton.OK,
                MessageBoxImage.Error);
        }

        DrainInteractiveQueue();
        DrainSendQueue();

        if (!_startMinimized && !Settings.StartMinimized)
        {
            _mainWindow.Show();
        }
        else
        {
            _mainWindow.ShowInTaskbar = false;
            _mainWindow.WindowState = WindowState.Minimized;
            _mainWindow.Hide();
            if (collected.Count == 0 && Settings.ShowTrayIcon && Settings.ShowNotifications)
                Notifier.ShowToast(DesignService.Current, "Работает в фоне. Дважды кликните по иконке в трее.");
        }

        // Single-instance activation signal: drain queues or show the window.
        // Keep listening for the app's whole lifetime: Explorer starts one process PER
        // selected file, and exiting after the first signal lost every file after the first.
        _ = Task.Run(() =>
        {
            using var ev = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
            while (!_exitRequested)
            {
                if (!ev.WaitOne(1000)) continue;
                if (_exitRequested) break;
                Dispatcher.BeginInvoke((Action)(() =>
                {
                    if (_mainWindow == null || _exitRequested) return;
                    bool drained = DrainInteractiveQueue();
                    if (DrainSendQueue()) drained = true;
                    if (!drained)
                        ShowMainWindow();
                }));
            }
        });
    }

    private static List<string> CollectSendArgs(string[] args, string flag)
    {
        var result = new List<string>();
        bool active = false;
        foreach (var a in args)
        {
            if (a.Equals(flag, StringComparison.OrdinalIgnoreCase)) { active = true; continue; }
            if (a.StartsWith("--")) { active = false; continue; }
            if (active) result.Add(a.Trim('"'));
        }
        return result;
    }

    private static void EnqueueFiles(string dir, List<string> files)
    {
        if (files.Count == 0) return;
        try
        {
            Directory.CreateDirectory(dir);
            foreach (var f in files)
                File.WriteAllText(Path.Combine(dir, $"{Guid.NewGuid():N}.path"), f);
        }
        catch { }
    }

    private static List<string> ReadQueue(string dir)
    {
        var paths = new List<string>();
        if (!Directory.Exists(dir)) return paths;
        foreach (var q in Directory.GetFiles(dir, "*.path"))
        {
            try
            {
                var path = File.ReadAllText(q);
                File.Delete(q);
                if (File.Exists(path)) paths.Add(path);
            }
            catch { }
        }
        return paths;
    }

    /// <summary>Shows SendWindow for queued interactive files. Returns true if any were shown.</summary>
    private bool _drainBusy;

    private bool DrainInteractiveQueue()
    {
        // Modal dialog pumps messages: a second signal must not open a stacked window.
        // Files enqueued meanwhile stay in the dir and are picked up by the loop below.
        if (_drainBusy) return true;
        try
        {
            if (!Directory.Exists(InteractiveQueueDir)) return false;
            var shown = false;
            _drainBusy = true;
            try
            {
                while (true)
                {
                    var paths = ReadQueue(InteractiveQueueDir);
                    if (paths.Count == 0) break;
                    // Explorer starts one process per selected file with a small delay;
                    // wait briefly so a multi-selection lands in ONE window.
                    Thread.Sleep(400);
                    var more = ReadQueue(InteractiveQueueDir);
                    if (more.Count > 0)
                        paths = paths.Union(more, StringComparer.OrdinalIgnoreCase).ToList();
                    if (paths.Count == 0) break;
                    shown = true;
                    var win = new UI.SendWindow(paths);
                    win.ShowDialog();
                }
            }
            finally
            {
                _drainBusy = false;
            }
            return shown;
        }
        catch { return false; }
    }

    /// <summary>Sends all silently-queued files. Returns true if queue was non-empty.</summary>
    private bool DrainSendQueue()
    {
        try
        {
            if (!Directory.Exists(SendQueueDir)) return false;
            var any = false;
            foreach (var q in Directory.GetFiles(SendQueueDir, "*.path"))
            {
                any = true;
                try
                {
                    var path = File.ReadAllText(q);
                    File.Delete(q);
                    if (File.Exists(path))
                    {
                        _controller?.SendFile(path);
                        Notifier.ShowToast("Отправлено на телефон", Path.GetFileName(path));
                    }
                }
                catch { }
            }
            return any;
        }
        catch { return false; }
    }

    public void ShowMainWindow()
    {
        if (_mainWindow == null) return;
        _mainWindow.ShowInTaskbar = true;
        _mainWindow.WindowState = WindowState.Normal;
        _mainWindow.Show();
        _mainWindow.Activate();
        _mainWindow.Topmost = true;
        _mainWindow.Topmost = false;
    }

    public void MinimizeToTray()
    {
        if (!Settings.ShowTrayIcon || !Settings.MinimizeToTray)
        {
            ExitClean();
            return;
        }
        _mainWindow?.Hide();
        _mainWindow!.ShowInTaskbar = false;
    }

    private void OnTrayCommand(string cmd)
    {
        switch (cmd)
        {
            case "open":
                ShowMainWindow();
                break;
            case "toggleSync":
                var newEnabled = !Controller.Settings.SyncEnabled;
                Controller.SetSyncEnabled(newEnabled);
                TrayManager.SetSyncEnabled(newEnabled);
                break;
            case "exit":
                ExitClean();
                break;
        }
    }

    private async void ExitClean()
    {
        _exitRequested = true;
        try
        {
            if (_controller != null)
                await _controller.DisposeAsync();
        }
        catch { }
        TrayManager.Dispose();
        try { _singleInstance?.ReleaseMutex(); } catch { }
        _singleInstance?.Dispose();
        Shutdown(0);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _exitRequested = true;
        try { _controller?.DisposeAsync().AsTask().Wait(1500); } catch { }
        TrayManager.Dispose();
        base.OnExit(e);
    }

    private static void LogError(Exception ex)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ZeroSynk");
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "error.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex}\n\n");
        }
        catch { }
    }

    /// <summary>One-time move of %APPDATA%\BufferSynk to %APPDATA%\ZeroSynk.</summary>
    private static void MigrateLegacyDataDir()
    {
        try
        {
            var appData = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var legacy = Path.Combine(appData, "BufferSynk");
            var current = Path.Combine(appData, "ZeroSynk");
            if (Directory.Exists(legacy) && !Directory.Exists(current))
                Directory.Move(legacy, current);
        }
        catch { }
    }
}
