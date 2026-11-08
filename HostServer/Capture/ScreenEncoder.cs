using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Text.RegularExpressions;
using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host.Capture;

public enum GpuEncoder { Nvenc, Amf }

public sealed record CaptureSettings(
    string FfmpegPath, GpuEncoder Encoder, VideoCodec Codec,
    int OutputIndex, int Width, int Height, int Fps, int BitrateMbps);

/// <summary>
/// DXGI Desktop Duplication (FFmpeg ddagrab) into an NVIDIA NVENC or AMD AMF encoder.
/// The game process is never touched: frames come from the Windows desktop compositor.
/// </summary>
public static class EncoderSelector
{
    private static readonly Regex OutputSize = new(@"Opened dxgi output \d+ with dimensions (\d+)x(\d+)", RegexOptions.Compiled);

    public static string EncoderName(GpuEncoder e, VideoCodec c) =>
        (c == VideoCodec.H264 ? "h264_" : "hevc_") + (e == GpuEncoder.Nvenc ? "nvenc" : "amf");

    /// <summary>Encoder options tuned for no reordering and no lookahead on each vendor.</summary>
    public static string[] EncoderArgs(GpuEncoder e, VideoCodec c, int fps, int bitrateMbps)
    {
        string rate = bitrateMbps.ToString(CultureInfo.InvariantCulture) + "M";
        // A VBV of about two frames keeps frame sizes even, which matters most on Wi-Fi.
        string vbv = (bitrateMbps * 1000 * 2 / fps).ToString(CultureInfo.InvariantCulture) + "k";

        var common = new[]
        {
            "-c:v", EncoderName(e, c), "-rc", "cbr", "-b:v", rate, "-maxrate", rate, "-bufsize", vbv,
            "-g", fps.ToString(CultureInfo.InvariantCulture), "-bf", "0",
            // One IDR per second is how a phone recovers from unrepairable packet loss.
            // AMF H.264 ignores -g for this, so force it explicitly on both vendors.
            "-force_key_frames", "expr:gte(t,n_forced*1)",
        };

        string[] vendor = e == GpuEncoder.Nvenc
            ? new[] { "-preset", "p1", "-tune", "ull", "-zerolatency", "1", "-delay", "0", "-rc-lookahead", "0", "-no-scenecut", "1", "-forced-idr", "1" }
            : new[] { "-usage", "ultralowlatency", "-quality", "speed", "-latency", "1", "-preanalysis", "0", "-vbaq", "0", "-async_depth", "1", "-forced_idr", "1" };

        return common.Concat(vendor).ToArray();
    }

    public static string CaptureGraph(int outputIndex, int fps, int width, int height) =>
        $"ddagrab=output_idx={outputIndex}:framerate={fps}:output_fmt=bgra:draw_mouse=1," +
        $"hwdownload,format=bgra,scale={width}:{height}:flags=fast_bilinear,format=nv12";

    public static (int Width, int Height) FitToMaxHeight(int width, int height, int maxHeight)
    {
        if (height > maxHeight)
        {
            width = (int)Math.Round(width * (double)maxHeight / height);
            height = maxHeight;
        }
        return (width & ~1, height & ~1);
    }

    public sealed record Probe(GpuEncoder Encoder, int DesktopWidth, int DesktopHeight);

    /// <summary>
    /// Runs the real capture and encode for a few frames with each candidate and returns
    /// the first that works. A failed NVENC probe on an AMD PC costs about 100 ms.
    /// </summary>
    public static Probe? FindWorkingEncoder(string ffmpeg, VideoCodec codec, int outputIndex, int fps, IEnumerable<GpuEncoder> candidates, Action<string> log)
    {
        foreach (var encoder in candidates)
        {
            var args = new List<string>
            {
                "-hide_banner", "-loglevel", "verbose", "-nostdin", "-f", "lavfi",
                "-i", CaptureGraph(outputIndex, fps, 1280, 720), "-frames:v", "3", "-an",
            };
            args.AddRange(EncoderArgs(encoder, codec, fps, 10));
            args.AddRange(new[] { "-f", "null", "-" });

            var (exit, output) = RunToCompletion(ffmpeg, args, TimeSpan.FromSeconds(20));
            if (exit == 0)
            {
                var m = OutputSize.Match(output);
                if (m.Success) return new Probe(encoder, int.Parse(m.Groups[1].Value), int.Parse(m.Groups[2].Value));
                log($"{EncoderName(encoder, codec)} worked, but FFmpeg did not report the desktop size.");
            }
            else
            {
                string reason = output.Split('\n').FirstOrDefault(l => l.Contains("Cannot ") || l.Contains("Error ") || l.Contains("failed"))?.Trim() ?? $"exit code {exit}";
                log($"{EncoderName(encoder, codec)} unavailable: {reason}");
            }
        }
        return null;
    }

    private static (int Exit, string Output) RunToCompletion(string exe, IEnumerable<string> args, TimeSpan timeout)
    {
        var psi = new ProcessStartInfo(exe) { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in args) psi.ArgumentList.Add(a);

        using var p = Process.Start(psi)!;
        var stderr = p.StandardError.ReadToEndAsync();
        var stdout = p.StandardOutput.ReadToEndAsync();
        if (!p.WaitForExit((int)timeout.TotalMilliseconds))
        {
            try { p.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
            return (-1, "timed out");
        }
        Task.WaitAll(stderr, stdout);
        return (p.ExitCode, stderr.Result);
    }
}

public delegate void EncodedFrame(ReadOnlySpan<byte> annexB, bool keyframe);

public sealed class ScreenEncoder
{
    private readonly CaptureSettings _settings;

    public ScreenEncoder(CaptureSettings settings) => _settings = settings;

    public static string[] BuildArguments(CaptureSettings s, int rtpPort)
    {
        var args = new List<string>
        {
            "-hide_banner", "-loglevel", "warning", "-nostats", "-nostdin",
            "-f", "lavfi", "-i", EncoderSelector.CaptureGraph(s.OutputIndex, s.Fps, s.Width, s.Height),
            "-an", "-fps_mode", "passthrough",
        };
        args.AddRange(EncoderSelector.EncoderArgs(s.Encoder, s.Codec, s.Fps, s.BitrateMbps));
        args.AddRange(new[]
        {
            // The RTP muxer has no global header, so both encoders repeat SPS/PPS on every IDR.
            "-flags", "+low_delay", "-flush_packets", "1", "-max_delay", "0",
            "-payload_type", "96", "-f", "rtp", $"udp://127.0.0.1:{rtpPort}?pkt_size=1400",
        });
        return args.ToArray();
    }

    /// <summary>Runs until cancelled, restarting FFmpeg if capture is lost (for example on a resolution change).</summary>
    public void Run(EncodedFrame onFrame, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            RunOnce(onFrame, token, out string lastError);
            if (token.IsCancellationRequested) return;

            AppLog.Warn($"Capture stopped ({lastError}). Restarting...");
            if (token.WaitHandle.WaitOne(1000)) return;
        }
    }

    private void RunOnce(EncodedFrame onFrame, CancellationToken token, out string lastError)
    {
        using var rtp = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp) { ReceiveBufferSize = 8 * 1024 * 1024, ReceiveTimeout = 200 };
        rtp.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        int port = ((IPEndPoint)rtp.LocalEndPoint!).Port;

        var psi = new ProcessStartInfo(_settings.FfmpegPath) { RedirectStandardError = true, RedirectStandardOutput = true, UseShellExecute = false, CreateNoWindow = true };
        foreach (var a in BuildArguments(_settings, port)) psi.ArgumentList.Add(a);

        using var ffmpeg = Process.Start(psi)!;
        string error = "FFmpeg exited";
        ffmpeg.ErrorDataReceived += (_, e) => { if (!string.IsNullOrWhiteSpace(e.Data) && !e.Data.Contains("dts")) error = e.Data; };
        ffmpeg.OutputDataReceived += (_, _) => { };
        ffmpeg.BeginErrorReadLine();
        ffmpeg.BeginOutputReadLine();

        var depacketizer = new RtpDepacketizer(_settings.Codec);
        var buffer = new byte[4096];

        try
        {
            while (!token.IsCancellationRequested)
            {
                int n;
                try { n = rtp.Receive(buffer); }
                catch (SocketException e) when (e.SocketErrorCode == SocketError.TimedOut)
                {
                    if (ffmpeg.HasExited) break;
                    continue;
                }

                if (depacketizer.Push(buffer.AsSpan(0, n), out var frame, out bool keyframe))
                    onFrame(frame, keyframe);
            }
        }
        finally
        {
            try { if (!ffmpeg.HasExited) ffmpeg.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { }
            ffmpeg.WaitForExit(2000);
            lastError = error;
        }
    }
}
