using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using Nefarius.ViGEm.Client.Exceptions;
using RemoteGameStreamer.Host.Capture;
using RemoteGameStreamer.Host.Driver;
using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host;

public enum HostState { Starting, Waiting, Streaming, Stopped, Failed }

public sealed record PcAddress(string Ip, bool IsVpn);

public sealed record HostStatus(
    HostState State,
    string? Problem = null,
    string? Pin = null,
    IReadOnlyList<PcAddress>? Addresses = null,
    string? EncoderName = null,
    SessionInfo? Session = null,
    bool ControllerEnabled = true);

public readonly record struct StreamStats(double Fps, double Mbps);

/// <summary>Owns everything the host runs. Knows nothing about the window that displays it.</summary>
public sealed class HostEngine : IDisposable
{
    private readonly HostOptions _options;
    private readonly object _gate = new();

    private VirtualPadManager? _pad;
    private AudioCapture? _audio;
    private SessionManager? _sessions;
    private StreamPipeline? _pipeline;
    private UdpInputServer? _server;
    private Timer? _statsTimer;
    private long _lastFrames, _lastBytes;
    private volatile bool _remoteInput;

    public HostEngine(HostOptions options)
    {
        _options = options;
        _remoteInput = options.Mouse;
    }

    /// <summary>Whether the phone may use the PC's mouse and keyboard. Turning on takes effect at the next connection.</summary>
    public bool RemoteInputAllowed
    {
        get => _remoteInput;
        set
        {
            if (_remoteInput == value) return;
            _remoteInput = value;
            _pad?.SetRemoteInput(value);
            AppLog.Info(value
                ? "Mouse and keyboard from the phone are on. If the phone is already connected, reconnect it to get the Pad and Kbd buttons."
                : "Mouse and keyboard from the phone are off.");
            if (value) WarnIfNotAdministrator();
        }
    }

    private static void WarnIfNotAdministrator()
    {
        if (!new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator))
            AppLog.Warn("Games running as administrator will ignore the phone's mouse and keyboard unless OmniDeck also runs as administrator.");
    }

    public HostStatus Status { get; private set; } = new(HostState.Stopped);
    public event Action<HostStatus>? StatusChanged;
    public event Action<StreamStats>? StatsUpdated;

    public void Start()
    {
        lock (_gate)
        {
            if (Status.State is HostState.Starting or HostState.Waiting or HostState.Streaming) return;
            Publish(new HostStatus(HostState.Starting, ControllerEnabled: !_options.NoPad));
        }
        Task.Run(StartCore);
    }

    private void StartCore()
    {
        try
        {
            AppLog.Info("Starting OmniDeck host.");

            string? ffmpeg = FindFfmpeg(_options.FfmpegPath);
            if (ffmpeg == null)
            {
                Fail("FFmpeg is missing. Keep ffmpeg.exe in the same folder as OmniDeck.exe.");
                return;
            }

            AppLog.Info("Testing GPU encoders...");
            var probe = EncoderSelector.FindWorkingEncoder(ffmpeg, _options.Codec, _options.Output, _options.Fps, _options.Candidates, AppLog.Info);
            if (probe == null)
            {
                Fail("No working NVIDIA or AMD video encoder was found. Update your GPU driver and make sure the desktop isn't locked or in a Remote Desktop session.");
                return;
            }

            bool hevcAvailable = _options.Codec == VideoCodec.Hevc ||
                EncoderSelector.FindWorkingEncoder(ffmpeg, VideoCodec.Hevc, _options.Output, _options.Fps, new[] { probe.Encoder }, _ => { }) != null;
            string encoderName = probe.Encoder == GpuEncoder.Nvenc ? "NVIDIA NVENC" : "AMD AMF";
            AppLog.Info($"Using {encoderName}. Desktop {probe.DesktopWidth}x{probe.DesktopHeight} at {DisplayInfo.PrimaryRefreshHz()} Hz. HEVC {(hevcAvailable ? "available" : "unavailable")}.");

            VirtualPadManager? pad = null;
            if (!_options.NoPad)
            {
                pad = new VirtualPadManager(_remoteInput);
                try
                {
                    pad.Initialize();
                }
                catch (VigemBusNotFoundException)
                {
                    pad.Dispose();
                    Fail("The ViGEmBus driver isn't installed. Install it, then press Start server.");
                    return;
                }
            }
            _pad = pad;

            if (!_options.NoAudio)
            {
                try
                {
                    _audio = new AudioCapture();
                }
                catch (Exception ex)
                {
                    AppLog.Warn($"Sound capture unavailable, streaming video only: {ex.Message}");
                }
            }
            int sampleRate = _audio?.SampleRate ?? 48000;

            uint pin = _options.Pin ?? (uint)RandomNumberGenerator.GetInt32(0, 1_000_000);
            var defaults = new StreamDefaults(_options.Codec, _options.MaxHeight, _options.Fps, _options.Bitrate,
                probe.DesktopWidth, probe.DesktopHeight, hevcAvailable, sampleRate, MouseEnabled: false);

            var sessions = new SessionManager((IPadSink?)pad ?? new NullPadSink(), pin,
                hello => StreamNegotiator.Negotiate(hello, defaults with { DisplayHz = DisplayInfo.PrimaryRefreshHz(), MouseEnabled = _remoteInput && pad != null }));
            var pipeline = new StreamPipeline(ffmpeg, probe.Encoder, _options.Output, _audio);

            sessions.SessionStarted += OnSessionStarted;
            sessions.SessionEnded += OnSessionEnded;
            _sessions = sessions;
            _pipeline = pipeline;

            try
            {
                string identityPath = Path.Combine(Path.GetDirectoryName(AppLog.Folder)!, "host-identity.bin");
                _server = new UdpInputServer(_options.Port, sessions, HostIdentity.LoadOrCreate(identityPath));
            }
            catch (SocketException ex) when (ex.SocketErrorCode == SocketError.AddressAlreadyInUse)
            {
                Fail($"Port {_options.Port} is already in use. Close any other copy of the host, then press Start server.");
                return;
            }
            _server.StartListening();
            _audio?.Start();

            _lastFrames = _lastBytes = 0;
            _statsTimer = new Timer(_ => ReportStats(), null, 1000, 1000);

            if (_remoteInput) WarnIfNotAdministrator();

            var addresses = LocalAddresses().ToArray();
            AppLog.Info($"Ready on UDP port {_options.Port}. PC address: {string.Join(", ", addresses.Select(a => a.Ip))}");

            lock (_gate)
            {
                Publish(new HostStatus(HostState.Waiting, Pin: pin.ToString("D6"), Addresses: addresses,
                    EncoderName: encoderName, ControllerEnabled: !_options.NoPad));
            }
        }
        catch (Exception ex)
        {
            Fail($"Couldn't start: {ex.Message}");
        }
    }

    private void OnSessionStarted(SessionInfo s)
    {
        var p = s.Parameters;
        AppLog.Info($"Phone connected: {s.Control.Address} ({p.Width}x{p.Height}, {p.Fps} FPS, {p.BitrateMbps} Mbps, {p.Codec}, sound {(s.Audio.Port != 0 ? "on" : "off")})");

        int hz = DisplayInfo.PrimaryRefreshHz();
        if (hz > 0 && p.Fps == hz && p.RequestedFps > hz)
            AppLog.Warn($"The phone asked for {p.RequestedFps} FPS but your monitor is set to {hz} Hz, so the stream is limited to {hz}. Raise it in Windows display settings.");

        try
        {
            _pad?.AttachSession(s);
            if (_pad != null) AppLog.Info("Virtual Xbox controller connected.");
        }
        catch (Exception ex)
        {
            AppLog.Error($"Could not create the virtual controller: {ex.Message}");
        }

        _lastFrames = _lastBytes = 0;
        if (_server?.Transport.HandshakeKeys is { } keys) _pipeline?.Start(s, keys);
        lock (_gate) Publish(Status with { State = HostState.Streaming, Session = s });
    }

    private void OnSessionEnded(SessionInfo s)
    {
        AppLog.Info($"Phone disconnected: {s.Control.Address}");
        try
        {
            _pad?.DetachSession(s);
        }
        catch (Exception ex)
        {
            AppLog.Error($"Could not remove the virtual controller: {ex.Message}");
        }

        var pipeline = _pipeline;
        ThreadPool.QueueUserWorkItem(_ => pipeline?.Stop(s));

        lock (_gate)
        {
            if (ReferenceEquals(Status.Session, s) && Status.State == HostState.Streaming)
                Publish(Status with { State = HostState.Waiting, Session = null });
        }
    }

    private void ReportStats()
    {
        var pipeline = _pipeline;
        if (pipeline == null || Status.State != HostState.Streaming) return;

        var (frames, bytes) = pipeline.ReadCounters();
        long frameDelta = frames - _lastFrames, byteDelta = bytes - _lastBytes;
        _lastFrames = frames;
        _lastBytes = bytes;

        if (frameDelta >= 0 && byteDelta >= 0) StatsUpdated?.Invoke(new StreamStats(frameDelta, byteDelta * 8 / 1e6));
    }

    public void Stop()
    {
        lock (_gate)
        {
            if (Status.State is not (HostState.Waiting or HostState.Streaming)) return;
        }
        ReleaseAll();
        lock (_gate) Publish(new HostStatus(HostState.Stopped, ControllerEnabled: !_options.NoPad));
        AppLog.Info("Server stopped.");
    }

    private void Fail(string problem)
    {
        AppLog.Error(problem);
        ReleaseAll();
        lock (_gate) Publish(new HostStatus(HostState.Failed, Problem: problem, ControllerEnabled: !_options.NoPad));
    }

    private void ReleaseAll()
    {
        _statsTimer?.Dispose();
        _statsTimer = null;
        _server?.Dispose();
        _server = null;
        _pipeline?.Dispose();
        _pipeline = null;
        _audio?.Dispose();
        _audio = null;
        _pad?.Dispose();
        _pad = null;
        _sessions = null;
    }

    private void Publish(HostStatus status)
    {
        Status = status;
        StatusChanged?.Invoke(status);
    }

    public void Dispose() => ReleaseAll();

    private static IEnumerable<PcAddress> LocalAddresses() =>
        RankAddresses(NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .SelectMany(n => n.GetIPProperties().UnicastAddresses)
            .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork)
            .Select(a => a.Address));

    /// <summary>Home-network addresses first, then VPN ranges. Unusable self-assigned ones are dropped.</summary>
    public static IEnumerable<PcAddress> RankAddresses(IEnumerable<IPAddress> addresses) => addresses
        .Select(a => a.GetAddressBytes())
        .Where(b => !(b[0] == 169 && b[1] == 254))
        .Select(b => new PcAddress(new IPAddress(b).ToString(), b[0] == 100 && b[1] is >= 64 and <= 127))
        .OrderBy(a => a.IsVpn);

    private static string? FindFfmpeg(string? explicitPath)
    {
        if (explicitPath != null) return File.Exists(explicitPath) ? explicitPath : null;

        string local = Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe");
        if (File.Exists(local)) return local;

        // Development layout: <repo>/.tools/ffmpeg/bin/ffmpeg.exe
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir != null; dir = dir.Parent)
        {
            string candidate = Path.Combine(dir.FullName, ".tools", "ffmpeg", "bin", "ffmpeg.exe");
            if (File.Exists(candidate)) return candidate;
        }

        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries))
        {
            string candidate = Path.Combine(dir.Trim(), "ffmpeg.exe");
            if (File.Exists(candidate)) return candidate;
        }
        return null;
    }
}

public static class DisplayInfo
{
    private const int CurrentSettings = -1;

    /// <summary>The primary monitor's current refresh rate, or 0 if Windows won't say.</summary>
    public static int PrimaryRefreshHz()
    {
        var mode = new DEVMODE { dmSize = (ushort)Marshal.SizeOf<DEVMODE>() };
        return EnumDisplaySettings(null, CurrentSettings, ref mode) && mode.dmDisplayFrequency > 1 ? (int)mode.dmDisplayFrequency : 0;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)]
    private struct DEVMODE
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmDeviceName;
        public ushort dmSpecVersion, dmDriverVersion, dmSize, dmDriverExtra;
        public uint dmFields;
        public int dmPositionX, dmPositionY;
        public uint dmDisplayOrientation, dmDisplayFixedOutput;
        public short dmColor, dmDuplex, dmYResolution, dmTTOption, dmCollate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string dmFormName;
        public ushort dmLogPixels;
        public uint dmBitsPerPel, dmPelsWidth, dmPelsHeight, dmDisplayFlags, dmDisplayFrequency;
        public uint dmICMMethod, dmICMIntent, dmMediaType, dmDitherType, dmReserved1, dmReserved2, dmPanningWidth, dmPanningHeight;
    }

    [DllImport("user32.dll", CharSet = CharSet.Ansi)]
    private static extern bool EnumDisplaySettings(string? device, int mode, ref DEVMODE devMode);
}
