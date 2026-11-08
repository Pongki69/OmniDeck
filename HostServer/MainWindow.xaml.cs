using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Diagnostics;
using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host;

public sealed record LogRow(string Time, string Message, Brush Color);

public partial class MainWindow : Window
{
    private const int MaxRows = 500;

    private static readonly Brush InfoBrush = Frozen(0xE8, 0xF4, 0xFF);
    private static readonly Brush WarnBrush = Frozen(0xFF, 0xCF, 0x5C);
    private static readonly Brush ErrorBrush = Frozen(0xFF, 0x8F, 0xA6);

    private readonly HostEngine _engine;
    private readonly ObservableCollection<LogRow> _rows = new();
    private readonly DispatcherTimer _uptime = new() { Interval = TimeSpan.FromSeconds(1) };
    private DateTime _connectedAt;

    public MainWindow(HostEngine engine)
    {
        _engine = engine;
        InitializeComponent();
        LogList.ItemsSource = _rows;

        foreach (var entry in AppLog.Snapshot()) AddRow(entry);
        AppLog.Written += OnLog;
        _engine.StatusChanged += OnStatus;
        _engine.StatsUpdated += OnStats;
        _uptime.Tick += (_, _) => ShowUptime();

        StateChanged += (_, _) => Root.Margin = WindowState == WindowState.Maximized ? new Thickness(7) : new Thickness(0);
        Loaded += (_, _) => _engine.Start();
        ShowStatus(_engine.Status);
    }

    private static Brush Frozen(byte r, byte g, byte b)
    {
        var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
        brush.Freeze();
        return brush;
    }

    private void OnLog(LogEntry entry) => Dispatcher.BeginInvoke(() => AddRow(entry));

    private void AddRow(LogEntry e)
    {
        var color = e.Level switch { LogLevel.Warning => WarnBrush, LogLevel.Error => ErrorBrush, _ => InfoBrush };
        _rows.Add(new LogRow(e.Time.ToString("HH:mm:ss"), e.Message, color));
        if (_rows.Count > MaxRows) _rows.RemoveAt(0);
        LogList.ScrollIntoView(_rows[^1]);
    }

    private void OnStatus(HostStatus status) => Dispatcher.BeginInvoke(() => ShowStatus(status));

    private void ShowStatus(HostStatus s)
    {
        var (label, r, g, b) = s.State switch
        {
            HostState.Starting => ("Starting", (byte)0xFF, (byte)0xCF, (byte)0x5C),
            HostState.Waiting => ("Waiting for phone", (byte)0x27, (byte)0xD3, (byte)0xF5),
            HostState.Streaming => ("Streaming", (byte)0x5C, (byte)0xE6, (byte)0x9A),
            HostState.Failed => ("Problem", (byte)0xFF, (byte)0x8F, (byte)0xA6),
            _ => ("Stopped", (byte)0x8F, (byte)0xB0, (byte)0xD9),
        };
        StatusText.Text = label;
        StatusPill.Background = Frozen(r, g, b);

        bool serving = s.State is HostState.Waiting or HostState.Streaming;
        var addresses = s.Addresses ?? Array.Empty<PcAddress>();
        AddressText.Text = serving && addresses.Count > 0 ? addresses[0].Ip : "—";
        OtherAddressesText.Text = serving
            ? string.Join("\n", addresses.Skip(1).Select(a => a.IsVpn ? $"VPN: {a.Ip}" : $"Also: {a.Ip}"))
            : "";
        PinText.Text = serving ? s.Pin : "------";

        ProblemBox.Visibility = s.Problem != null ? Visibility.Visible : Visibility.Collapsed;
        ProblemText.Text = s.Problem ?? "";

        ServerButton.IsEnabled = s.State != HostState.Starting;
        ServerButtonText.Text = serving ? "Stop server" : "Start server";

        EncoderText.Text = s.EncoderName ?? "—";

        if (s.State == HostState.Streaming && s.Session is { } session)
        {
            StreamParameters p = session.Parameters;
            ResolutionText.Text = $"{p.Width}×{p.Height}";
            CodecText.Text = p.Codec == VideoCodec.Hevc ? "HEVC" : "H.264";
            FpsText.Text = $"— / {p.Fps}";
            BitrateText.Text = $"— / {p.BitrateMbps}";
            PhoneText.Text = session.Control.Address.ToString();
            ControllerText.Text = s.ControllerEnabled ? "Connected" : "Off (--no-pad)";
            _connectedAt = DateTime.Now;
            ShowUptime();
            _uptime.Start();
        }
        else
        {
            _uptime.Stop();
            ResolutionText.Text = FpsText.Text = BitrateText.Text = CodecText.Text = UptimeText.Text = "—";
            PhoneText.Text = "Not connected";
            ControllerText.Text = !s.ControllerEnabled ? "Off (--no-pad)" : serving ? "Waiting for phone" : "Off";
        }
    }

    private void OnStats(StreamStats stats) => Dispatcher.BeginInvoke(() =>
    {
        if (_engine.Status.Session is not { } session) return;
        FpsText.Text = $"{stats.Fps:0} / {session.Parameters.Fps}";
        BitrateText.Text = $"{stats.Mbps:0.0} / {session.Parameters.BitrateMbps}";
    });

    private void ShowUptime() => UptimeText.Text = (DateTime.Now - _connectedAt).ToString(@"h\:mm\:ss");

    private void OnServerToggle(object sender, RoutedEventArgs e)
    {
        if (_engine.Status.State is HostState.Waiting or HostState.Streaming)
        {
            ServerButton.IsEnabled = false;
            Task.Run(_engine.Stop);
        }
        else
        {
            _engine.Start();
        }
    }

    private void OnCopyLogs(object sender, RoutedEventArgs e) =>
        Clipboard.SetText(string.Join(Environment.NewLine, AppLog.Snapshot().Select(l => $"{l.Time:HH:mm:ss} {l.Level} {l.Message}")));

    private void OnOpenLogs(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{AppLog.Folder}\"") { UseShellExecute = true });

    private void OnMinimize(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void OnMaximize(object sender, RoutedEventArgs e) =>
        WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    private void OnClose(object sender, RoutedEventArgs e) => Close();

    protected override void OnClosing(CancelEventArgs e)
    {
        AppLog.Written -= OnLog;
        _engine.StatusChanged -= OnStatus;
        _engine.StatsUpdated -= OnStats;
        _engine.Dispose();
        base.OnClosing(e);
    }
}
