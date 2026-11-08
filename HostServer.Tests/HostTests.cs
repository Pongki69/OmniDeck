using System.Buffers.Binary;
using System.Net;
using System.Runtime.InteropServices;
using RemoteGameStreamer.Host;
using RemoteGameStreamer.Host.Capture;
using RemoteGameStreamer.Host.Driver;
using RemoteGameStreamer.Host.Network;
using Xunit;

namespace HostServer.Tests;

internal sealed class RecordingPad : IPadSink
{
    public List<ControllerStatePacket> Packets { get; } = new();
    public int Neutralized { get; private set; }
    public void ProcessInputPacket(ControllerStatePacket packet) => Packets.Add(packet);
    public void Neutralize() => Neutralized++;
}

public class ProtocolTests
{
    // Same bytes are asserted by ProtocolTest.kt on the client.
    private const string GoldenInput = "4F44030178563412020000000102FF803412CDAB00801027F0D800FE03";

    private static byte[] Hex(string s) => Convert.FromHexString(s);

    [Fact]
    public void Struct_is_17_bytes() => Assert.Equal(17, Marshal.SizeOf<ControllerStatePacket>());

    [Fact]
    public void Golden_hello_decodes()
    {
        var d = Hex("4F44010140E2010028A029A038043C1E02000000");
        Assert.Equal(Protocol.HelloSize, d.Length);
        Assert.Equal(new Protocol.Hello(123456, 41000, 41001, 1080, 60, 30, Protocol.CodecHevc), Protocol.ReadHello(d));
    }

    [Theory]
    [InlineData(false, 0)]
    [InlineData(true, 1)]
    public void Welcome_reports_whether_mouse_input_is_allowed(bool enabled, byte expected)
    {
        var p = new StreamParameters(VideoCodec.H264, 1920, 1080, 60, 48000, enabled);
        Assert.Equal(expected, Protocol.BuildWelcome(Protocol.WelcomeStatus.Ok, 1, p)[7]);
    }

    [Fact]
    public void Golden_input_decodes()
    {
        var d = Hex(GoldenInput);
        Assert.Equal(Protocol.InputSize, d.Length);
        Assert.Equal(0x12345678u, Protocol.ReadToken(d));
        Assert.Equal(2u, Protocol.ReadSeq(d));

        var p = Protocol.ReadControllerState(d);
        Assert.Equal(0x0201, p.Buttons);
        Assert.Equal(255, p.LeftTrigger);
        Assert.Equal(128, p.RightTrigger);
        Assert.Equal(0x1234, p.ThumbLX);
        Assert.Equal(unchecked((short)0xABCD), p.ThumbLY);
        Assert.Equal(short.MinValue, p.ThumbRX);
        Assert.Equal(10000, p.ThumbRY);
        Assert.Equal(-10000, p.MouseDeltaX);
        Assert.Equal(-512, p.MouseDeltaY);
        Assert.Equal(3, p.MouseButtons);
    }
}

public class SessionTests
{
    private const uint Pin = 123456;
    private static readonly StreamParameters Params = new(VideoCodec.H264, 1920, 1080, 60, 48000);
    private static readonly IPEndPoint Phone = new(IPAddress.Parse("192.168.1.50"), 40000);

    private static byte[] Hello(uint pin, ushort video = 41000, ushort audio = 41001)
    {
        var d = new byte[Protocol.HelloSize];
        Protocol.WriteHeader(d, Protocol.TypeHello);
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(4), pin);
        BinaryPrimitives.WriteUInt16LittleEndian(d.AsSpan(8), video);
        BinaryPrimitives.WriteUInt16LittleEndian(d.AsSpan(10), audio);
        return d;
    }

    private static byte[] Input(uint token, uint seq, ushort buttons = 1)
    {
        var d = new byte[Protocol.InputSize];
        Protocol.WriteHeader(d, Protocol.TypeInput);
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(4), token);
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(8), seq);
        BinaryPrimitives.WriteUInt16LittleEndian(d.AsSpan(12), buttons);
        return d;
    }

    private static uint Connect(SessionManager s)
    {
        var w = s.HandleDatagram(Hello(Pin), Phone, 0)!;
        Assert.Equal((byte)Protocol.WelcomeStatus.Ok, w[4]);
        return BinaryPrimitives.ReadUInt32LittleEndian(w.AsSpan(8));
    }

    [Fact]
    public void Wrong_pin_is_rejected_and_starts_no_session()
    {
        var s = new SessionManager(new RecordingPad(), Pin, Params);
        var w = s.HandleDatagram(Hello(1), Phone, 0)!;
        Assert.Equal((byte)Protocol.WelcomeStatus.BadPin, w[4]);
        Assert.Null(s.Current);
    }

    [Fact]
    public void Repeated_wrong_pins_lock_out_even_the_right_pin()
    {
        var s = new SessionManager(new RecordingPad(), Pin, Params);
        for (int i = 0; i < SessionManager.MaxPinFailures; i++) s.HandleDatagram(Hello(1), Phone, 0);

        Assert.Equal((byte)Protocol.WelcomeStatus.Locked, s.HandleDatagram(Hello(Pin), Phone, 100)![4]);
        Assert.Equal((byte)Protocol.WelcomeStatus.Ok, s.HandleDatagram(Hello(Pin), Phone, SessionManager.LockoutMs + 1)![4]);
    }

    [Fact]
    public void Input_with_wrong_token_or_source_is_ignored()
    {
        var pad = new RecordingPad();
        var s = new SessionManager(pad, Pin, Params);
        uint token = Connect(s);

        s.HandleDatagram(Input(token + 1, 1), Phone, 1);
        s.HandleDatagram(Input(token, 1), new IPEndPoint(IPAddress.Parse("192.168.1.99"), 40000), 1);
        Assert.Empty(pad.Packets);

        s.HandleDatagram(Input(token, 1), Phone, 1);
        Assert.Single(pad.Packets);
    }

    [Fact]
    public void Out_of_order_packets_are_dropped()
    {
        var pad = new RecordingPad();
        var s = new SessionManager(pad, Pin, Params);
        uint token = Connect(s);

        s.HandleDatagram(Input(token, 5, 1), Phone, 1);
        s.HandleDatagram(Input(token, 4, 2), Phone, 2);
        s.HandleDatagram(Input(token, 5, 2), Phone, 2);
        s.HandleDatagram(Input(token, 6, 4), Phone, 3);

        Assert.Equal(new ushort[] { 1, 4 }, pad.Packets.Select(p => p.Buttons));
    }

    [Fact]
    public void Silence_releases_inputs_once_then_ends_the_session()
    {
        var pad = new RecordingPad();
        var s = new SessionManager(pad, Pin, Params);
        SessionInfo? ended = null;
        s.SessionEnded += x => ended = x;
        uint token = Connect(s);
        s.HandleDatagram(Input(token, 1), Phone, 10);

        s.Tick(10 + SessionManager.InputTimeoutMs - 1);
        Assert.Equal(0, pad.Neutralized);

        s.Tick(10 + SessionManager.InputTimeoutMs);
        s.Tick(10 + SessionManager.InputTimeoutMs + 50);
        Assert.Equal(1, pad.Neutralized);
        Assert.Null(ended);

        s.Tick(10 + SessionManager.SessionTimeoutMs);
        Assert.NotNull(ended);
        Assert.Null(s.Current);
    }

    [Fact]
    public void Second_phone_is_busy_and_retried_hello_keeps_the_token()
    {
        var s = new SessionManager(new RecordingPad(), Pin, Params);
        int started = 0;
        s.SessionStarted += _ => started++;
        uint token = Connect(s);

        var again = s.HandleDatagram(Hello(Pin), Phone, 5)!;
        Assert.Equal(token, BinaryPrimitives.ReadUInt32LittleEndian(again.AsSpan(8)));
        Assert.Equal(1, started);

        var other = s.HandleDatagram(Hello(Pin), new IPEndPoint(IPAddress.Parse("192.168.1.77"), 1), 6)!;
        Assert.Equal((byte)Protocol.WelcomeStatus.Busy, other[4]);
    }
}

public class StreamNegotiatorTests
{
    private static readonly StreamDefaults D = new(VideoCodec.H264, 1080, 60, 30, 2560, 1440, HevcAvailable: true, 48000, MouseEnabled: false);

    private static Protocol.Hello Req(ushort maxHeight = 0, byte fps = 0, byte bitrate = 0, byte codec = 0) => new(1, 2, 3, maxHeight, fps, bitrate, codec);

    [Fact]
    public void Zero_means_host_defaults()
    {
        var p = StreamNegotiator.Negotiate(Req(), D);
        Assert.Equal((1920, 1080, 60, 30, VideoCodec.H264), (p.Width, p.Height, p.Fps, p.BitrateMbps, p.Codec));
    }

    [Fact]
    public void Phone_settings_are_applied()
    {
        var p = StreamNegotiator.Negotiate(Req(720, 120, 15, Protocol.CodecHevc), D);
        Assert.Equal((1280, 720, 120, 15, VideoCodec.Hevc), (p.Width, p.Height, p.Fps, p.BitrateMbps, p.Codec));
    }

    [Fact]
    public void Frame_rate_is_limited_by_the_monitor_but_remembers_the_request()
    {
        var p = StreamNegotiator.Negotiate(Req(fps: 120), D with { DisplayHz = 60 });
        Assert.Equal((60, 120), (p.Fps, p.RequestedFps));
        Assert.Equal(120, StreamNegotiator.Negotiate(Req(fps: 120), D with { DisplayHz = 180 }).Fps);
    }

    [Fact]
    public void Address_list_prefers_home_network_and_drops_unusable()
    {
        var ranked = HostEngine.RankAddresses(new[] { "100.98.169.120", "169.254.1.1", "192.168.0.200" }.Select(IPAddress.Parse)).ToArray();
        Assert.Equal(new[] { new PcAddress("192.168.0.200", false), new PcAddress("100.98.169.120", true) }, ranked);
    }

    [Fact]
    public void Never_upscales_past_the_desktop()
    {
        var p = StreamNegotiator.Negotiate(Req(2160), D);
        Assert.Equal((2560, 1440), (p.Width, p.Height));
    }

    [Fact]
    public void Out_of_range_requests_are_clamped()
    {
        var p = StreamNegotiator.Negotiate(Req(100, 255, 255), D);
        Assert.Equal((640, 360, 120, 150), (p.Width, p.Height, p.Fps, p.BitrateMbps));
    }

    [Fact]
    public void Hevc_falls_back_when_the_gpu_cannot_encode_it()
    {
        var p = StreamNegotiator.Negotiate(Req(codec: Protocol.CodecHevc), D with { HevcAvailable = false });
        Assert.Equal(VideoCodec.H264, p.Codec);
    }
}

public class MouseEdgeTests
{
    [Theory]
    [InlineData(0, 1, MouseEdges.LeftDown)]
    [InlineData(1, 1, 0u)]
    [InlineData(1, 0, MouseEdges.LeftUp)]
    [InlineData(0, 2, MouseEdges.RightDown)]
    [InlineData(2, 0, MouseEdges.RightUp)]
    [InlineData(1, 2, MouseEdges.LeftUp | MouseEdges.RightDown)]
    public void Only_changes_produce_events(byte previous, byte current, uint expected) =>
        Assert.Equal(expected, MouseEdges.Flags(previous, current));
}

public class PacketizerTests
{
    private static List<byte[]> Shards(byte[] frame, int payload)
    {
        var list = new List<byte[]>();
        new VideoPacketizer(payload).Packetize(frame, 7, true, d => list.Add(d.ToArray()));
        return list;
    }

    private static int Index(byte[] s) => BinaryPrimitives.ReadUInt16LittleEndian(s.AsSpan(12));

    [Fact]
    public void Golden_shards_match_the_client_tests()
    {
        var frame = Enumerable.Range(1, 10).Select(i => (byte)i).ToArray();
        var hex = Shards(frame, 4).Select(Convert.ToHexString).ToArray();
        Assert.Equal(new[]
        {
            "4F441001070000000A00000000000300010001020304",
            "4F441001070000000A00000001000300010005060708",
            "4F441001070000000A000000020003000100090A",
            "4F441001070000000A0000000300030003000D0E040C",
        }, hex);
    }

    [Fact]
    public void Shard_count_matches_prediction()
    {
        var frame = new byte[1000];
        Assert.Equal(VideoPacketizer.ShardCount(frame.Length, 100), Shards(frame, 100).Count);
    }

    [Fact]
    public void Any_single_loss_in_a_group_is_recoverable_by_xor()
    {
        var frame = new byte[730];
        new Random(1).NextBytes(frame);
        const int payload = 100;

        var shards = Shards(frame, payload);
        int dataShards = 8;
        var group = shards.Where(s => Index(s) < dataShards).ToList();
        var parity = shards.Single(s => Index(s) == dataShards);

        for (int lost = 0; lost < dataShards; lost++)
        {
            var rebuilt = new byte[payload];
            parity.AsSpan(VideoPacketizer.HeaderSize).CopyTo(rebuilt);
            foreach (var s in group.Where(x => Index(x) != lost))
            {
                var body = s.AsSpan(VideoPacketizer.HeaderSize);
                for (int i = 0; i < body.Length; i++) rebuilt[i] ^= body[i];
            }
            int len = Math.Min(payload, frame.Length - lost * payload);
            Assert.True(rebuilt.AsSpan(0, len).SequenceEqual(frame.AsSpan(lost * payload, len)), $"shard {lost}");
        }
    }
}

public class RtpDepacketizerTests
{
    private static byte[] Rtp(ushort seq, bool marker, params byte[] payload)
    {
        var d = new byte[12 + payload.Length];
        d[0] = 0x80;
        d[1] = (byte)(96 | (marker ? 0x80 : 0));
        BinaryPrimitives.WriteUInt16BigEndian(d.AsSpan(2), seq);
        payload.CopyTo(d, 12);
        return d;
    }

    [Fact]
    public void Single_nal_and_fu_a_rebuild_annexb()
    {
        var r = new RtpDepacketizer(VideoCodec.H264);
        Assert.False(r.Push(Rtp(1, false, 0x67, 1, 2), out _, out _));                 // SPS
        Assert.False(r.Push(Rtp(2, false, 0x7C, 0x85, 0xAA), out _, out _));           // FU-A start, IDR, NRI=3
        Assert.True(r.Push(Rtp(3, true, 0x7C, 0x45, 0xBB), out var f, out bool key));  // FU-A end, marker

        Assert.True(key);
        Assert.Equal(new byte[] { 0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x65, 0xAA, 0xBB }, f.ToArray());
    }

    [Fact]
    public void Stap_a_is_expanded()
    {
        var r = new RtpDepacketizer(VideoCodec.H264);
        Assert.True(r.Push(Rtp(1, true, 0x78, 0, 2, 0x67, 9, 0, 2, 0x68, 8), out var f, out _));
        Assert.Equal(new byte[] { 0, 0, 0, 1, 0x67, 9, 0, 0, 0, 1, 0x68, 8 }, f.ToArray());
    }

    [Fact]
    public void Hevc_fragment_restores_the_two_byte_header()
    {
        var r = new RtpDepacketizer(VideoCodec.Hevc);
        // FU indicator type 49, layer/tid byte 0x01, FU header start + type 19 (IDR).
        Assert.False(r.Push(Rtp(1, false, 49 << 1, 0x01, 0x80 | 19, 0xAA), out _, out _));
        Assert.True(r.Push(Rtp(2, true, 49 << 1, 0x01, 0x40 | 19, 0xBB), out var f, out bool key));
        Assert.True(key);
        Assert.Equal(new byte[] { 0, 0, 0, 1, 19 << 1, 0x01, 0xAA, 0xBB }, f.ToArray());
    }

    [Fact]
    public void Sequence_gap_drops_the_frame_and_later_frames_recover()
    {
        var r = new RtpDepacketizer(VideoCodec.H264);
        Assert.False(r.Push(Rtp(1, false, 0x41, 1), out _, out _));
        Assert.False(r.Push(Rtp(3, true, 0x41, 2), out _, out _));
        Assert.Equal(1, r.DroppedFrames);
        Assert.True(r.Push(Rtp(4, true, 0x41, 3), out _, out _));
    }

    [Fact]
    public void Rtcp_is_ignored()
    {
        var r = new RtpDepacketizer(VideoCodec.H264);
        var sr = new byte[28]; sr[0] = 0x80; sr[1] = 200;
        Assert.False(r.Push(sr, out _, out _));
    }
}

public class EncoderArgsTests
{
    [Theory]
    [InlineData(GpuEncoder.Nvenc, VideoCodec.H264, "h264_nvenc")]
    [InlineData(GpuEncoder.Nvenc, VideoCodec.Hevc, "hevc_nvenc")]
    [InlineData(GpuEncoder.Amf, VideoCodec.H264, "h264_amf")]
    [InlineData(GpuEncoder.Amf, VideoCodec.Hevc, "hevc_amf")]
    public void Vendor_encoder_is_selected_without_b_frames(GpuEncoder e, VideoCodec c, string expected)
    {
        var args = EncoderSelector.EncoderArgs(e, c, 60, 30);
        Assert.Equal(expected, args[Array.IndexOf(args, "-c:v") + 1]);
        Assert.Equal("0", args[Array.IndexOf(args, "-bf") + 1]);
        Assert.Equal("cbr", args[Array.IndexOf(args, "-rc") + 1]);
    }

    [Theory]
    [InlineData(2560, 1440, 1080, 1920, 1080)]
    [InlineData(3840, 2160, 1080, 1920, 1080)]
    [InlineData(1920, 1080, 1080, 1920, 1080)]
    [InlineData(3440, 1440, 1080, 2580, 1080)]
    [InlineData(1366, 767, 1080, 1366, 766)]
    public void Output_size_is_even_and_capped(int w, int h, int max, int ew, int eh) =>
        Assert.Equal((ew, eh), EncoderSelector.FitToMaxHeight(w, h, max));
}

public class AudioConverterTests
{
    [Fact]
    public void Float_stereo_converts_and_clips()
    {
        var src = new byte[8];
        BitConverter.TryWriteBytes(src.AsSpan(0), 0.5f);
        BitConverter.TryWriteBytes(src.AsSpan(4), -2f);
        var dst = new byte[4];

        Assert.Equal(4, AudioConverter.ToStereo16(src, 2, 32, true, dst));
        Assert.Equal(16384, BitConverter.ToInt16(dst, 0));
        Assert.Equal(-32767, BitConverter.ToInt16(dst, 2));
    }

    [Fact]
    public void Surround_center_reaches_both_ears()
    {
        var src = new byte[24];
        BitConverter.TryWriteBytes(src.AsSpan(8), 1f); // center channel only
        var dst = new byte[4];

        AudioConverter.ToStereo16(src, 6, 32, true, dst);
        Assert.Equal(BitConverter.ToInt16(dst, 0), BitConverter.ToInt16(dst, 2));
        Assert.True(BitConverter.ToInt16(dst, 0) > 10000);
    }
}
