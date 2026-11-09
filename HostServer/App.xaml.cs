using System.Runtime.InteropServices;
using System.Windows;

namespace RemoteGameStreamer.Host;

public partial class App : Application
{
    private Mutex? _singleInstance;

    [DllImport("winmm.dll")] private static extern uint timeBeginPeriod(uint ms);

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        if (e.Args is ["--finish-update", var targetDir, var pidText] && int.TryParse(pidText, out int pid))
        {
            AppUpdater.FinishUpdate(targetDir, pid);
            Shutdown(0);
            return;
        }

        AppLog.Initialize();
        DispatcherUnhandledException += (_, args) =>
        {
            AppLog.Error($"Unexpected error: {args.Exception}");
            args.Handled = true;
        };

        var options = HostOptions.Parse(e.Args);
        if (options == null)
        {
            MessageBox.Show(HostOptions.Usage, "OmniDeck options", MessageBoxButton.OK, MessageBoxImage.Warning);
            Shutdown(2);
            return;
        }

        // Two copies would fight over the port and the virtual controller.
        _singleInstance = new Mutex(true, "OmniDeckHost", out bool isFirst);
        if (!isFirst)
        {
            MessageBox.Show("OmniDeck is already open.", "OmniDeck", MessageBoxButton.OK, MessageBoxImage.Information);
            Shutdown(0);
            return;
        }

        timeBeginPeriod(1); // 1 ms sleep granularity for packet pacing

        MainWindow = new MainWindow(new HostEngine(options));
        MainWindow.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _singleInstance?.Dispose();
        base.OnExit(e);
    }
}
