using System.Net;
using System.Security.Cryptography;
using RemoteGameStreamer.Host.Driver;

namespace RemoteGameStreamer.Host.Network;

public sealed record SessionInfo(IPEndPoint Control, IPEndPoint Video, IPEndPoint Audio, uint Token, StreamParameters Parameters);

/// <summary>
/// Socket-free session logic: PIN pairing, single-client ownership, stale-packet
/// rejection, and the safety watchdog that releases all inputs when the phone goes quiet.
/// </summary>
public sealed class SessionManager
{
    public const long InputTimeoutMs = 250;
    public const long SessionTimeoutMs = 3000;
    public const int MaxPinFailures = 5;
    public const long LockoutMs = 30_000;

    private readonly object _lock = new();
    private readonly IPadSink _pad;
    private readonly uint _pin;
    private readonly Func<Protocol.Hello, StreamParameters> _negotiate;
    private readonly Dictionary<IPAddress, (int Failures, long LockedUntil)> _failures = new();

    private SessionInfo? _session;
    private uint _lastSeq;
    private bool _anyInput;
    private bool _neutral = true;
    private long _lastPacketMs;

    public event Action<SessionInfo>? SessionStarted;
    public event Action<SessionInfo>? SessionEnded;

    public SessionManager(IPadSink pad, uint pin, Func<Protocol.Hello, StreamParameters> negotiate)
    {
        _pad = pad;
        _pin = pin;
        _negotiate = negotiate;
    }

    public SessionManager(IPadSink pad, uint pin, StreamParameters parameters)
        : this(pad, pin, _ => parameters)
    {
    }

    public SessionInfo? Current { get { lock (_lock) return _session; } }

    /// <summary>Handles one datagram. Returns a reply to send to <paramref name="from"/>, or null.</summary>
    public byte[]? HandleDatagram(ReadOnlySpan<byte> d, IPEndPoint from, long nowMs)
    {
        if (!Protocol.HasHeader(d)) return null;

        switch (d[2])
        {
            case Protocol.TypeHello when d.Length == Protocol.HelloSize:
                return HandleHello(Protocol.ReadHello(d), from, nowMs);

            case Protocol.TypeInput when d.Length == Protocol.InputSize:
                HandleInput(d, from, nowMs);
                return null;

            case Protocol.TypeBye when d.Length == Protocol.ByeSize:
                HandleBye(Protocol.ReadToken(d), from);
                return null;

            default:
                return null;
        }
    }

    private byte[] HandleHello(Protocol.Hello hello, IPEndPoint from, long nowMs)
    {
        SessionInfo? ended = null, started = null;
        byte[] reply;
        var parameters = _negotiate(hello);

        lock (_lock)
        {
            if (_failures.TryGetValue(from.Address, out var f) && f.LockedUntil > nowMs)
                return Protocol.BuildWelcome(Protocol.WelcomeStatus.Locked, 0, parameters);

            if (hello.Pin != _pin)
            {
                int failures = (f.LockedUntil != 0 ? 0 : f.Failures) + 1;
                _failures[from.Address] = failures >= MaxPinFailures ? (0, nowMs + LockoutMs) : (failures, 0);
                AppLog.Warn(failures >= MaxPinFailures
                    ? $"Too many wrong PINs from {from.Address}. Locked out for {LockoutMs / 1000} seconds."
                    : $"Wrong PIN from {from.Address}.");
                return Protocol.BuildWelcome(Protocol.WelcomeStatus.BadPin, 0, parameters);
            }

            _failures.Remove(from.Address);

            if (_session is { } s && !s.Control.Address.Equals(from.Address))
                return Protocol.BuildWelcome(Protocol.WelcomeStatus.Busy, 0, parameters);

            var video = new IPEndPoint(from.Address, hello.VideoPort);
            var audio = new IPEndPoint(from.Address, hello.AudioPort);

            if (_session is { } same && same.Control.Equals(from) && same.Video.Equals(video) && same.Audio.Equals(audio))
            {
                // Our Welcome was lost and the client retried. Idempotent.
                return Protocol.BuildWelcome(Protocol.WelcomeStatus.Ok, same.Token, same.Parameters);
            }

            // Same phone reconnecting with new sockets replaces its old session.
            if (_session != null)
            {
                ended = _session;
                ReleaseInputs();
            }

            started = new SessionInfo(from, video, audio, (uint)RandomNumberGenerator.GetInt32(1, int.MaxValue), parameters);
            _session = started;
            _anyInput = false;
            _lastSeq = 0;
            _lastPacketMs = nowMs;
            reply = Protocol.BuildWelcome(Protocol.WelcomeStatus.Ok, started.Token, parameters);
        }

        if (ended != null) SessionEnded?.Invoke(ended);
        SessionStarted?.Invoke(started);
        return reply;
    }

    private void HandleInput(ReadOnlySpan<byte> d, IPEndPoint from, long nowMs)
    {
        lock (_lock)
        {
            if (_session is not { } s || !s.Control.Equals(from) || Protocol.ReadToken(d) != s.Token) return;

            _lastPacketMs = nowMs;

            uint seq = Protocol.ReadSeq(d);
            if (_anyInput && seq <= _lastSeq) return; // late or duplicated UDP datagram

            _anyInput = true;
            _lastSeq = seq;
            _neutral = false;
            _pad.ProcessInputPacket(Protocol.ReadControllerState(d));
        }
    }

    private void HandleBye(uint token, IPEndPoint from)
    {
        SessionInfo? ended = null;
        lock (_lock)
        {
            if (_session is { } s && s.Control.Equals(from) && s.Token == token)
            {
                ended = s;
                _session = null;
                ReleaseInputs();
            }
        }
        if (ended != null) SessionEnded?.Invoke(ended);
    }

    /// <summary>Call regularly (about every 50 ms).</summary>
    public void Tick(long nowMs)
    {
        SessionInfo? ended = null;
        lock (_lock)
        {
            if (_session == null) return;
            long silence = nowMs - _lastPacketMs;

            if (silence >= InputTimeoutMs && !_neutral) ReleaseInputs();

            if (silence >= SessionTimeoutMs)
            {
                ended = _session;
                _session = null;
            }
        }
        if (ended != null) SessionEnded?.Invoke(ended);
    }

    private void ReleaseInputs()
    {
        _neutral = true;
        _pad.Neutralize();
    }
}
