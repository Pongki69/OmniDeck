using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host.Capture;

/// <summary>Host-side values used when the phone leaves a setting at 0, plus what this PC can do.</summary>
public sealed record StreamDefaults(
    VideoCodec Codec, int MaxHeight, int Fps, int BitrateMbps,
    int DesktopWidth, int DesktopHeight, bool HevcAvailable, int AudioSampleRate, bool MouseEnabled, int DisplayHz = 0);

/// <summary>Turns the phone's requested settings into what will actually be streamed.</summary>
public static class StreamNegotiator
{
    public static StreamParameters Negotiate(Protocol.Hello hello, StreamDefaults d)
    {
        int requestedFps = hello.Fps == 0 ? d.Fps : Math.Clamp((int)hello.Fps, 15, 120);
        // Desktop capture only gets new frames at the monitor's refresh rate.
        int fps = d.DisplayHz > 0 ? Math.Min(requestedFps, d.DisplayHz) : requestedFps;
        int bitrate = hello.BitrateMbps == 0 ? d.BitrateMbps : Math.Clamp((int)hello.BitrateMbps, 2, 150);
        int maxHeight = hello.MaxHeight == 0 ? d.MaxHeight : Math.Clamp((int)hello.MaxHeight, 360, 2160);

        VideoCodec codec = hello.Codec switch
        {
            Protocol.CodecH264 => VideoCodec.H264,
            Protocol.CodecHevc when d.HevcAvailable => VideoCodec.Hevc,
            Protocol.CodecHevc => VideoCodec.H264,
            _ => d.Codec,
        };

        var (width, height) = EncoderSelector.FitToMaxHeight(d.DesktopWidth, d.DesktopHeight, maxHeight);
        return new StreamParameters(codec, width, height, fps, d.AudioSampleRate, d.MouseEnabled, bitrate, requestedFps);
    }
}
