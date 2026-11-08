using System.Buffers.Binary;

namespace RemoteGameStreamer.Host.Network;

/// <summary>
/// Splits one encoded frame into UDP shards with XOR parity: one parity shard per
/// <see cref="GroupSize"/> data shards, so any single loss in a group is recoverable.
/// Wire format mirrored by FrameReassembler.kt.
///
/// Header (18 bytes): hdr(4) | frameId u32 | frameLen u32 | index u16 | dataShards u16 | flags u8 | reserved u8
/// Parity shards use index = dataShards + groupNumber.
/// </summary>
public sealed class VideoPacketizer
{
    public const int HeaderSize = 18;
    public const int DefaultPayloadSize = 1200;
    public const int GroupSize = 8;
    public const byte FlagKeyframe = 1;
    public const byte FlagParity = 2;

    private readonly int _payloadSize;
    private readonly byte[] _datagram;
    private readonly byte[] _parity;

    public VideoPacketizer(int payloadSize = DefaultPayloadSize)
    {
        _payloadSize = payloadSize;
        _datagram = new byte[HeaderSize + payloadSize];
        _parity = new byte[payloadSize];
    }

    public static int ShardCount(int frameLen, int payloadSize = DefaultPayloadSize)
    {
        int data = (frameLen + payloadSize - 1) / payloadSize;
        return data + (data + GroupSize - 1) / GroupSize;
    }

    /// <summary>Emits every shard. <paramref name="send"/> must consume the span before returning.</summary>
    public void Packetize(ReadOnlySpan<byte> frame, uint frameId, bool keyframe, SendShard send)
    {
        if (frame.IsEmpty) return;

        int dataShards = (frame.Length + _payloadSize - 1) / _payloadSize;
        if (dataShards > ushort.MaxValue / 2) throw new ArgumentException("Frame too large", nameof(frame));

        byte flags = keyframe ? FlagKeyframe : (byte)0;

        for (int index = 0; index < dataShards; index++)
        {
            if (index % GroupSize == 0) _parity.AsSpan().Clear();

            int offset = index * _payloadSize;
            var chunk = frame.Slice(offset, Math.Min(_payloadSize, frame.Length - offset));

            for (int i = 0; i < chunk.Length; i++) _parity[i] ^= chunk[i];

            WriteHeader(frameId, frame.Length, index, dataShards, flags);
            chunk.CopyTo(_datagram.AsSpan(HeaderSize));
            send(_datagram.AsSpan(0, HeaderSize + chunk.Length));

            bool groupEnd = index % GroupSize == GroupSize - 1 || index == dataShards - 1;
            if (groupEnd)
            {
                int group = index / GroupSize;
                WriteHeader(frameId, frame.Length, dataShards + group, dataShards, (byte)(flags | FlagParity));
                _parity.CopyTo(_datagram.AsSpan(HeaderSize));
                send(_datagram.AsSpan(0, HeaderSize + _payloadSize));
            }
        }
    }

    private void WriteHeader(uint frameId, int frameLen, int index, int dataShards, byte flags)
    {
        var d = _datagram.AsSpan();
        Protocol.WriteHeader(d, Protocol.TypeVideo);
        BinaryPrimitives.WriteUInt32LittleEndian(d[4..], frameId);
        BinaryPrimitives.WriteUInt32LittleEndian(d[8..], (uint)frameLen);
        BinaryPrimitives.WriteUInt16LittleEndian(d[12..], (ushort)index);
        BinaryPrimitives.WriteUInt16LittleEndian(d[14..], (ushort)dataShards);
        d[16] = flags;
        d[17] = 0;
    }
}

public delegate void SendShard(ReadOnlySpan<byte> datagram);
