using System.Runtime.InteropServices;
using System.Threading.Channels;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host.Driver;

public interface IPadSink
{
    void ProcessInputPacket(ControllerStatePacket packet);

    /// <summary>Releases every button, centers sticks, and lets go of held mouse buttons.</summary>
    void Neutralize();

    /// <summary>Queues text, then an optional special key, for the focused window. False if keyboard input is off.</summary>
    bool TypeText(string text, byte key);
}

/// <summary>Works out which mouse buttons changed, so a held button is one press rather than 200/s.</summary>
public static class MouseEdges
{
    public const uint LeftDown = 0x0002, LeftUp = 0x0004, RightDown = 0x0008, RightUp = 0x0010;

    public static uint Flags(byte previous, byte current)
    {
        uint flags = 0;
        if (((previous ^ current) & 1) != 0) flags |= (current & 1) != 0 ? LeftDown : LeftUp;
        if (((previous ^ current) & 2) != 0) flags |= (current & 2) != 0 ? RightDown : RightUp;
        return flags;
    }
}

/// <summary>Virtual Xbox 360 pad through ViGEmBus, plus SendInput for touchpad mode and typed text.</summary>
public sealed class VirtualPadManager : IPadSink, IDisposable
{
    private const uint InputMouse = 0;
    private const uint InputKeyboard = 1;
    private const uint MouseMove = 0x0001;
    private const uint KeyUp = 0x0002;
    private const uint KeyUnicode = 0x0004;
    private const int KeyIntervalMs = 20; // 50 keys/s at most, well inside a normal keyboard's report rate

    private readonly record struct TypingJob(string Text, byte Key, int Generation);

    private ViGEmClient? _client;
    private IXbox360Controller? _controller;
    private ControllerStatePacket _last;
    private bool _hasLast;
    private byte _mouseButtons;
    private readonly object _sync = new();
    private bool _mouseEnabled;
    private object? _owner;
    private readonly Channel<TypingJob> _typing = Channel.CreateUnbounded<TypingJob>(new UnboundedChannelOptions { SingleReader = true });
    private int _typingGeneration;

    public VirtualPadManager(bool mouseEnabled)
    {
        _mouseEnabled = mouseEnabled;
        _ = Task.Run(TypeLoopAsync);
    }

    /// <summary>Switches mouse and keyboard from the phone on or off. Turning off applies immediately.</summary>
    public void SetRemoteInput(bool allowed)
    {
        lock (_sync)
        {
            if (!allowed)
            {
                ApplyMouse(0, 0, 0);
                CancelTypingLocked();
            }
            _mouseEnabled = allowed;
        }
    }

    public bool TypeText(string text, byte key)
    {
        lock (_sync)
        {
            if (!_mouseEnabled) return false;
            _typing.Writer.TryWrite(new TypingJob(text, key, _typingGeneration));
            return true;
        }
    }

    private void CancelTypingLocked() => Interlocked.Increment(ref _typingGeneration);

    private async Task TypeLoopAsync()
    {
        await foreach (var job in _typing.Reader.ReadAllAsync())
        {
            foreach (char c in job.Text)
            {
                if (Volatile.Read(ref _typingGeneration) != job.Generation) break;
                if (char.IsControl(c)) continue;
                SendKey(0, c, KeyUnicode);
                await Task.Delay(KeyIntervalMs);
            }

            if (job.Key != Protocol.KeyNone && Volatile.Read(ref _typingGeneration) == job.Generation)
            {
                var (vk, scan) = job.Key switch
                {
                    Protocol.KeyBackspace => ((ushort)0x08, (ushort)0x0E),
                    Protocol.KeyTab => ((ushort)0x09, (ushort)0x0F),
                    Protocol.KeyEnter => ((ushort)0x0D, (ushort)0x1C),
                    _ => ((ushort)0, (ushort)0),
                };
                if (vk != 0)
                {
                    SendKey(vk, scan, 0);
                    await Task.Delay(KeyIntervalMs);
                }
            }
        }
    }

    private static void SendKey(ushort vk, ushort scan, uint flags)
    {
        var inputs = new[]
        {
            new INPUT { type = InputKeyboard, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags } } },
            new INPUT { type = InputKeyboard, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags | KeyUp } } },
        };
        SendInput(2, inputs, Marshal.SizeOf<INPUT>());
    }

    /// <summary>Opens the driver. The controller itself only exists during a session.</summary>
    /// <exception cref="Nefarius.ViGEm.Client.Exceptions.VigemBusNotFoundException">Driver not installed.</exception>
    public void Initialize() => _client = new ViGEmClient();

    /// <summary>Plugs in the virtual controller for this session.</summary>
    public void AttachSession(object owner)
    {
        lock (_sync)
        {
            _owner = owner;
            if (_controller != null)
            {
                ReleaseLocked();
                return;
            }

            var controller = _client!.CreateXbox360Controller();
            controller.AutoSubmitReport = false; // otherwise every Set* call is a separate driver report
            controller.Connect();
            _controller = controller;
            _hasLast = false;
        }
    }

    /// <summary>Unplugs the controller, unless a newer session has already taken it over.</summary>
    public void DetachSession(object owner)
    {
        lock (_sync)
        {
            if (!ReferenceEquals(_owner, owner)) return;
            _owner = null;
            CancelTypingLocked(); // nothing left in the queue may be typed after the phone has gone
            DisconnectLocked();
        }
    }

    public void ProcessInputPacket(ControllerStatePacket p)
    {
        lock (_sync)
        {
            ApplyController(p);
            if (_mouseEnabled) ApplyMouse(p.MouseDeltaX, p.MouseDeltaY, p.MouseButtons);
        }
    }

    public void Neutralize()
    {
        lock (_sync) ReleaseLocked();
    }

    private void ReleaseLocked()
    {
        ApplyController(default);
        if (_mouseEnabled) ApplyMouse(0, 0, 0);
    }

    private void DisconnectLocked()
    {
        ReleaseLocked();
        _controller?.Disconnect();
        _controller = null;
        _hasLast = false;
    }

    private void ApplyController(ControllerStatePacket p)
    {
        var c = _controller;
        if (c == null) return;

        // Skip identical reports (sent 200 times a second while idle).
        if (_hasLast && SameController(p, _last)) return;
        _last = p;
        _hasLast = true;

        c.SetSliderValue(Xbox360Slider.LeftTrigger, p.LeftTrigger);
        c.SetSliderValue(Xbox360Slider.RightTrigger, p.RightTrigger);

        c.SetAxisValue(Xbox360Axis.LeftThumbX, p.ThumbLX);
        c.SetAxisValue(Xbox360Axis.LeftThumbY, p.ThumbLY);
        c.SetAxisValue(Xbox360Axis.RightThumbX, p.ThumbRX);
        c.SetAxisValue(Xbox360Axis.RightThumbY, p.ThumbRY);

        c.SetButtonState(Xbox360Button.A, (p.Buttons & (1 << 0)) != 0);
        c.SetButtonState(Xbox360Button.B, (p.Buttons & (1 << 1)) != 0);
        c.SetButtonState(Xbox360Button.X, (p.Buttons & (1 << 2)) != 0);
        c.SetButtonState(Xbox360Button.Y, (p.Buttons & (1 << 3)) != 0);
        c.SetButtonState(Xbox360Button.LeftShoulder, (p.Buttons & (1 << 4)) != 0);
        c.SetButtonState(Xbox360Button.RightShoulder, (p.Buttons & (1 << 5)) != 0);
        c.SetButtonState(Xbox360Button.Start, (p.Buttons & (1 << 6)) != 0);
        c.SetButtonState(Xbox360Button.Back, (p.Buttons & (1 << 7)) != 0);
        c.SetButtonState(Xbox360Button.LeftThumb, (p.Buttons & (1 << 8)) != 0);
        c.SetButtonState(Xbox360Button.RightThumb, (p.Buttons & (1 << 9)) != 0);
        c.SetButtonState(Xbox360Button.Up, (p.Buttons & (1 << 10)) != 0);
        c.SetButtonState(Xbox360Button.Down, (p.Buttons & (1 << 11)) != 0);
        c.SetButtonState(Xbox360Button.Left, (p.Buttons & (1 << 12)) != 0);
        c.SetButtonState(Xbox360Button.Right, (p.Buttons & (1 << 13)) != 0);
        c.SetButtonState(Xbox360Button.Guide, (p.Buttons & (1 << 14)) != 0);

        c.SubmitReport();
    }

    private static bool SameController(in ControllerStatePacket a, in ControllerStatePacket b) =>
        a.Buttons == b.Buttons && a.LeftTrigger == b.LeftTrigger && a.RightTrigger == b.RightTrigger &&
        a.ThumbLX == b.ThumbLX && a.ThumbLY == b.ThumbLY && a.ThumbRX == b.ThumbRX && a.ThumbRY == b.ThumbRY;

    private void ApplyMouse(short dx, short dy, byte buttons)
    {
        uint flags = MouseEdges.Flags(_mouseButtons, buttons);
        _mouseButtons = buttons;
        if (dx != 0 || dy != 0) flags |= MouseMove;
        if (flags == 0) return;

        var input = new INPUT { type = InputMouse, u = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, dwFlags = flags } } };
        SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());
    }

    public void Dispose()
    {
        lock (_sync)
        {
            CancelTypingLocked();
            _typing.Writer.TryComplete();
            try { DisconnectLocked(); } catch { /* shutting down */ }
        }
        _client?.Dispose();
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public nint dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint type;
        public InputUnion u;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint count, INPUT[] inputs, int size);
}

/// <summary>Used with --no-pad to run the stream without the driver.</summary>
public sealed class NullPadSink : IPadSink
{
    public void ProcessInputPacket(ControllerStatePacket packet) { }
    public void Neutralize() { }
    public bool TypeText(string text, byte key) => false;
}
