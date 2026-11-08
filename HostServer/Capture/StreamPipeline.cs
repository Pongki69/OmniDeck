using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host.Capture;

/// <summary>Runs capture and audio only while a phone is connected.</summary>
public sealed class StreamPipeline : IDisposable
{
    private readonly object _lock = new();
    private readonly string _ffmpeg;
    private readonly GpuEncoder _encoder;
    private readonly int _output;
    private CancellationTokenSource? _cts;
    private Task? _videoTask;
    private volatile MediaSender? _sender;
    private SessionInfo? _active;

    public StreamPipeline(string ffmpeg, GpuEncoder encoder, int output, AudioCapture? audio)
    {
        _ffmpeg = ffmpeg;
        _encoder = encoder;
        _output = output;
        if (audio != null) audio.OnPcm = pcm => _sender?.SendAudio(pcm.Span);
    }

    public void Start(SessionInfo session)
    {
        lock (_lock)
        {
            StopLocked();
            _active = session;
            var p = session.Parameters;
            var sender = new MediaSender(session, p.Fps);
            var cts = new CancellationTokenSource();
            var encoder = new ScreenEncoder(new CaptureSettings(_ffmpeg, _encoder, p.Codec, _output, p.Width, p.Height, p.Fps, p.BitrateMbps));

            _sender = sender;
            _cts = cts;
            _videoTask = Task.Factory.StartNew(() =>
            {
                try
                {
                    encoder.Run((frame, key) => sender.SendFrame(frame, key), cts.Token);
                }
                catch (Exception ex)
                {
                    AppLog.Error($"Video pipeline error: {ex.Message}");
                }
            }, TaskCreationOptions.LongRunning);
        }
    }

    /// <summary>Stops only if <paramref name="session"/> is still current, so a late stop can't kill a newer stream.</summary>
    public void Stop(SessionInfo session)
    {
        lock (_lock)
        {
            if (ReferenceEquals(_active, session)) StopLocked();
        }
    }

    private void StopLocked()
    {
        _active = null;
        var sender = _sender;
        _sender = null;

        _cts?.Cancel();
        _videoTask?.Wait(3000);
        _cts?.Dispose();
        _cts = null;
        _videoTask = null;

        sender?.Dispose();
    }

    public (long Frames, long Bytes) ReadCounters()
    {
        var sender = _sender;
        return sender == null ? (0, 0) : (sender.Frames, sender.Bytes);
    }

    public void Dispose()
    {
        lock (_lock) StopLocked();
    }
}
