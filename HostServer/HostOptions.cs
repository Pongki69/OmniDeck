using System.Globalization;
using RemoteGameStreamer.Host.Capture;
using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host;

/// <summary>Optional command-line settings. The app needs none of these to run.</summary>
public sealed class HostOptions
{
    public const string Usage =
        "--encoder auto|nvenc|amf  --codec h264|hevc  --bitrate <Mbps>  --fps <n>  --max-height <px>\n" +
        "--output <monitor index>  --port <udp>  --pin <digits>  --ffmpeg <path>  --no-pad  --no-audio  --mouse";

    public int Port = 9050, Fps = 60, Bitrate = 30, MaxHeight = 1080, Output;
    public uint? Pin;
    public VideoCodec Codec = VideoCodec.H264;
    public GpuEncoder[] Candidates = { GpuEncoder.Nvenc, GpuEncoder.Amf };
    public bool NoPad, NoAudio, Mouse;
    public string? FfmpegPath;

    public static HostOptions? Parse(string[] a)
    {
        var o = new HostOptions();
        try
        {
            for (int i = 0; i < a.Length; i++)
            {
                switch (a[i])
                {
                    case "--port": o.Port = Int(a[++i]); break;
                    case "--fps": o.Fps = Int(a[++i]); break;
                    case "--bitrate": o.Bitrate = Int(a[++i]); break;
                    case "--max-height": o.MaxHeight = Int(a[++i]); break;
                    case "--output": o.Output = Int(a[++i]); break;
                    case "--pin": o.Pin = uint.Parse(a[++i], CultureInfo.InvariantCulture); break;
                    case "--ffmpeg": o.FfmpegPath = a[++i]; break;
                    case "--no-pad": o.NoPad = true; break;
                    case "--no-audio": o.NoAudio = true; break;
                    case "--mouse": o.Mouse = true; break;
                    case "--codec":
                        o.Codec = a[++i].ToLowerInvariant() switch
                        {
                            "h264" => VideoCodec.H264,
                            "hevc" => VideoCodec.Hevc,
                            _ => throw new FormatException(),
                        };
                        break;
                    case "--encoder":
                        o.Candidates = a[++i].ToLowerInvariant() switch
                        {
                            "auto" => new[] { GpuEncoder.Nvenc, GpuEncoder.Amf },
                            "nvenc" => new[] { GpuEncoder.Nvenc },
                            "amf" => new[] { GpuEncoder.Amf },
                            _ => throw new FormatException(),
                        };
                        break;
                    default: throw new FormatException();
                }
            }
            return o;
        }
        catch (Exception e) when (e is FormatException or IndexOutOfRangeException or OverflowException)
        {
            return null;
        }

        static int Int(string s) => int.Parse(s, CultureInfo.InvariantCulture);
    }
}
