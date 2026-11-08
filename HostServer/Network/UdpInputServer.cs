using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace RemoteGameStreamer.Host.Network;

/// <summary>UDP receiver on the input/control port (9050).</summary>
public sealed class UdpInputServer : IDisposable
{
    private const int SIO_UDP_CONNRESET = unchecked((int)0x9800000C);

    private readonly SessionManager _sessions;
    private readonly Socket _socket;
    private readonly Stopwatch _clock = Stopwatch.StartNew();
    private readonly CancellationTokenSource _cts = new();
    private Thread? _receiveThread;
    private Thread? _watchdogThread;

    public UdpInputServer(int port, SessionManager sessions)
    {
        _sessions = sessions;
        _socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        // Without this, an ICMP "port unreachable" from a vanished phone makes ReceiveFrom throw.
        _socket.IOControl(SIO_UDP_CONNRESET, new byte[] { 0 }, null);
        _socket.ReceiveBufferSize = 256 * 1024;
        _socket.Bind(new IPEndPoint(IPAddress.Any, port));
    }

    public int Port => ((IPEndPoint)_socket.LocalEndPoint!).Port;

    public void StartListening()
    {
        _receiveThread = new Thread(ReceiveLoop) { IsBackground = true, Name = "UdpInput", Priority = ThreadPriority.AboveNormal };
        _watchdogThread = new Thread(WatchdogLoop) { IsBackground = true, Name = "InputWatchdog" };
        _receiveThread.Start();
        _watchdogThread.Start();
    }

    private void ReceiveLoop()
    {
        var buffer = new byte[2048];
        EndPoint remote = new IPEndPoint(IPAddress.Any, 0);

        while (!_cts.IsCancellationRequested)
        {
            try
            {
                int n = _socket.ReceiveFrom(buffer, ref remote);
                var from = (IPEndPoint)remote;
                byte[]? reply = _sessions.HandleDatagram(buffer.AsSpan(0, n), from, _clock.ElapsedMilliseconds);
                if (reply != null) _socket.SendTo(reply, from);
            }
            catch (SocketException) when (_cts.IsCancellationRequested) { return; }
            catch (ObjectDisposedException) { return; }
            catch (Exception ex)
            {
                AppLog.Warn($"UDP input error: {ex.Message}");
            }
        }
    }

    private void WatchdogLoop()
    {
        while (!_cts.Token.WaitHandle.WaitOne(50))
        {
            try { _sessions.Tick(_clock.ElapsedMilliseconds); }
            catch (Exception ex) { AppLog.Warn($"Input watchdog error: {ex.Message}"); }
        }
    }

    public void Dispose()
    {
        _cts.Cancel();
        _socket.Dispose();
    }
}
