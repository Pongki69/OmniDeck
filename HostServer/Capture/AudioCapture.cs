using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace RemoteGameStreamer.Host.Capture;

/// <summary>Converts WASAPI buffers to interleaved stereo s16le. No resampling: the phone plays at the device's rate.</summary>
public static class AudioConverter
{
    public static int ToStereo16(ReadOnlySpan<byte> src, int channels, int bits, bool isFloat, Span<byte> dst)
    {
        int bytesPerSample = bits / 8;
        int frames = src.Length / (bytesPerSample * channels);
        Span<float> frame = stackalloc float[Math.Min(channels, 8)];

        for (int f = 0; f < frames; f++)
        {
            var s = src.Slice(f * bytesPerSample * channels, bytesPerSample * channels);
            for (int c = 0; c < frame.Length; c++) frame[c] = ReadSample(s[(c * bytesPerSample)..], bits, isFloat);

            float l, r;
            if (channels == 1) { l = r = frame[0]; }
            else if (channels >= 6)
            {
                // FL FR C LFE BL BR (and SL SR for 7.1)
                float rearL = frame[4], rearR = frame[5];
                if (channels >= 8) { rearL = (rearL + frame[6]) * 0.5f; rearR = (rearR + frame[7]) * 0.5f; }
                l = 0.5f * frame[0] + 0.35f * frame[2] + 0.35f * rearL;
                r = 0.5f * frame[1] + 0.35f * frame[2] + 0.35f * rearR;
            }
            else { l = frame[0]; r = frame[1]; }

            WriteS16(dst[(f * 4)..], l);
            WriteS16(dst[(f * 4 + 2)..], r);
        }
        return frames * 4;
    }

    private static float ReadSample(ReadOnlySpan<byte> s, int bits, bool isFloat)
    {
        if (isFloat) return BitConverter.ToSingle(s);
        return bits switch
        {
            16 => BitConverter.ToInt16(s) / 32768f,
            24 => (s[0] << 8 | s[1] << 16 | s[2] << 24) / 2147483648f,
            32 => BitConverter.ToInt32(s) / 2147483648f,
            _ => 0f,
        };
    }

    private static void WriteS16(Span<byte> d, float v)
    {
        int sample = (int)MathF.Round(Math.Clamp(v, -1f, 1f) * 32767f);
        d[0] = (byte)sample;
        d[1] = (byte)(sample >> 8);
    }
}

/// <summary>Loopback capture of whatever the default output device is playing.</summary>
public sealed class AudioCapture : IDisposable
{
    private readonly WasapiLoopbackCapture _capture = new();
    private readonly bool _isFloat;
    private byte[] _converted = new byte[64 * 1024];
    public Action<ReadOnlyMemory<byte>>? OnPcm;

    public AudioCapture()
    {
        var f = _capture.WaveFormat;
        var standard = f is WaveFormatExtensible e ? e.ToStandardWaveFormat() : f;
        _isFloat = standard.Encoding == WaveFormatEncoding.IeeeFloat;
        _capture.DataAvailable += OnData;
    }

    public int SampleRate => _capture.WaveFormat.SampleRate;

    public void Start() => _capture.StartRecording();
    public void Stop()
    {
        if (_capture.CaptureState == CaptureState.Capturing) _capture.StopRecording();
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        var f = _capture.WaveFormat;
        int needed = e.BytesRecorded / Math.Max(1, f.BlockAlign) * 4;
        if (needed > _converted.Length) _converted = new byte[needed];
        int n = AudioConverter.ToStereo16(e.Buffer.AsSpan(0, e.BytesRecorded), f.Channels, f.BitsPerSample, _isFloat, _converted);
        OnPcm?.Invoke(_converted.AsMemory(0, n));
    }

    public void Dispose()
    {
        Stop();
        _capture.Dispose();
    }
}
