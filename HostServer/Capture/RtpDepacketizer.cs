using System.Buffers.Binary;
using RemoteGameStreamer.Host.Network;

namespace RemoteGameStreamer.Host.Capture;

/// <summary>
/// Rebuilds Annex-B access units from FFmpeg's RTP output. RTP is used on loopback
/// because its marker bit ends a frame the instant it is produced, whereas a raw
/// Annex-B pipe only reveals a frame's end when the next one starts (+1 frame latency).
/// </summary>
public sealed class RtpDepacketizer
{
    private static readonly byte[] StartCode = { 0, 0, 0, 1 };

    private readonly VideoCodec _codec;
    private byte[] _buffer = new byte[2 * 1024 * 1024];
    private int _length;
    private bool _keyframe;
    private bool _broken;
    private int _expectedSeq = -1;

    public RtpDepacketizer(VideoCodec codec) => _codec = codec;

    public long DroppedFrames { get; private set; }

    /// <summary>Feeds one RTP datagram. Returns true when <paramref name="frame"/> holds a complete access unit.</summary>
    public bool Push(ReadOnlySpan<byte> rtp, out ReadOnlySpan<byte> frame, out bool keyframe)
    {
        frame = default;
        keyframe = false;

        if (rtp.Length < 12 || rtp[0] >> 6 != 2) return false;
        if (rtp[1] is >= 200 and <= 204) return false; // RTCP sharing the socket

        int headerLen = 12 + 4 * (rtp[0] & 0x0F);
        if ((rtp[0] & 0x10) != 0)
        {
            if (rtp.Length < headerLen + 4) return false;
            headerLen += 4 + 4 * BinaryPrimitives.ReadUInt16BigEndian(rtp[(headerLen + 2)..]);
        }
        int end = rtp.Length;
        if ((rtp[0] & 0x20) != 0) end -= rtp[^1];
        if (end <= headerLen) return false;

        int seq = BinaryPrimitives.ReadUInt16BigEndian(rtp[2..]);
        if (_expectedSeq >= 0 && seq != _expectedSeq) _broken = true;
        _expectedSeq = (seq + 1) & 0xFFFF;

        var payload = rtp[headerLen..end];
        if (_codec == VideoCodec.H264) AppendH264(payload);
        else AppendHevc(payload);

        if ((rtp[1] & 0x80) == 0) return false;

        bool complete = !_broken && _length > 0;
        if (_broken) DroppedFrames++;
        if (complete)
        {
            frame = _buffer.AsSpan(0, _length);
            keyframe = _keyframe;
        }
        _length = 0;
        _keyframe = false;
        _broken = false;
        return complete;
    }

    private void AppendH264(ReadOnlySpan<byte> p)
    {
        if (p.IsEmpty) return;
        int type = p[0] & 0x1F;

        if (type is >= 1 and <= 23)
        {
            AppendNal(p, type);
        }
        else if (type == 24) // STAP-A
        {
            var r = p[1..];
            while (r.Length > 2)
            {
                int size = BinaryPrimitives.ReadUInt16BigEndian(r);
                if (size == 0 || size > r.Length - 2) { _broken = true; return; }
                AppendNal(r.Slice(2, size), r[2] & 0x1F);
                r = r[(2 + size)..];
            }
        }
        else if (type == 28 && p.Length > 2) // FU-A
        {
            byte fu = p[1];
            int nalType = fu & 0x1F;
            if ((fu & 0x80) != 0)
            {
                Reserve(p.Length + 5);
                Write(StartCode);
                Write(stackalloc byte[] { (byte)((p[0] & 0xE0) | nalType) });
                Mark(nalType);
            }
            Write(p[2..]);
        }
    }

    private void AppendHevc(ReadOnlySpan<byte> p)
    {
        if (p.Length < 2) return;
        int type = (p[0] >> 1) & 0x3F;

        if (type <= 47)
        {
            AppendNal(p, type);
        }
        else if (type == 48) // aggregation packet
        {
            var r = p[2..];
            while (r.Length > 2)
            {
                int size = BinaryPrimitives.ReadUInt16BigEndian(r);
                if (size < 2 || size > r.Length - 2) { _broken = true; return; }
                AppendNal(r.Slice(2, size), (r[2] >> 1) & 0x3F);
                r = r[(2 + size)..];
            }
        }
        else if (type == 49 && p.Length > 3) // fragmentation unit
        {
            byte fu = p[2];
            int nalType = fu & 0x3F;
            if ((fu & 0x80) != 0)
            {
                Reserve(p.Length + 6);
                Write(StartCode);
                Write(stackalloc byte[] { (byte)((p[0] & 0x81) | (nalType << 1)), p[1] });
                Mark(nalType);
            }
            Write(p[3..]);
        }
    }

    private void AppendNal(ReadOnlySpan<byte> nal, int type)
    {
        Write(StartCode);
        Write(nal);
        Mark(type);
    }

    private void Mark(int nalType)
    {
        if (_codec == VideoCodec.H264 ? nalType is 5 or 7 : nalType is >= 16 and <= 21) _keyframe = true;
    }

    private void Write(ReadOnlySpan<byte> data)
    {
        Reserve(data.Length);
        data.CopyTo(_buffer.AsSpan(_length));
        _length += data.Length;
    }

    private void Reserve(int extra)
    {
        if (_length + extra > _buffer.Length) Array.Resize(ref _buffer, Math.Max(_buffer.Length * 2, _length + extra));
    }
}
