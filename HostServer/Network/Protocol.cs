using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteGameStreamer.Host.Network;

/// <summary>
/// Wire format shared with the client (Protocol.kt). All integers little-endian.
/// Every datagram starts with: 'O' 'D' type version.
/// </summary>
public static class Protocol
{
    public const byte Magic0 = (byte)'O';
    public const byte Magic1 = (byte)'D';
    public const byte Version = 1;

    public const byte TypeHello = 0x01;
    public const byte TypeWelcome = 0x02;
    public const byte TypeInput = 0x03;
    public const byte TypeBye = 0x04;
    public const byte TypeKeyboard = 0x05;
    public const byte TypeKeyboardAck = 0x06;
    public const byte TypeKeyRequest = 0x07;
    public const byte TypeKeyReply = 0x08;
    public const byte TypeSecureHello = 0x09;
    public const byte TypeVideo = 0x10;
    public const byte TypeAudio = 0x11;
    public const byte TypeSealed = 0x20;

    public const int HeaderSize = 4;
    public const int HelloSize = 20;   // hdr | pin u32 | videoPort u16 | audioPort u16 (0 = no sound) | maxHeight u16 | fps u8 | bitrateMbps u8 | codec u8 | reserved[3]
    public const int WelcomeSize = 20; // hdr | status u8 | codec u8 | fps u8 | mouseEnabled u8 | token u32 | w u16 | h u16 | sampleRate u32
    public const int InputSize = 12 + ControllerStatePacket.Size; // hdr | token u32 | seq u32 | body
    public const int ByeSize = 8;      // hdr | token u32
    public const int KeyboardHeaderSize = 14; // hdr | token u32 | id u32 | key u8 | reserved u8 | UTF-16LE text
    public const int MaxKeyboardChars = 256;
    public const int KeyboardAckSize = 9;     // hdr | id u32 | accepted u8
    public const int KeyReplySize = 69;       // hdr | host public key
    public const int KeyRequestSize = 69;     // hdr | padding, as large as the reply so this can't amplify spoofed traffic
    public const int SecureHelloSize = 105;   // hdr | client public key | encrypted Hello | tag
    public const int SealedHeaderSize = 12;   // hdr | counter u64

    public const byte KeyNone = 0;
    public const byte KeyBackspace = 1;
    public const byte KeyTab = 2;
    public const byte KeyEnter = 3;

    public const byte CodecDefault = 0;
    public const byte CodecH264 = 1;
    public const byte CodecHevc = 2;

    public enum WelcomeStatus : byte { Ok = 0, BadPin = 1, Busy = 2, Locked = 3 }

    public static bool HasHeader(ReadOnlySpan<byte> d) =>
        d.Length >= HeaderSize && d[0] == Magic0 && d[1] == Magic1 && d[3] == Version;

    public static void WriteHeader(Span<byte> d, byte type)
    {
        d[0] = Magic0;
        d[1] = Magic1;
        d[2] = type;
        d[3] = Version;
    }

    /// <summary>For MaxHeight, Fps, BitrateMbps and Codec, 0 means "use the host's default".</summary>
    public readonly record struct Hello(uint Pin, ushort VideoPort, ushort AudioPort, ushort MaxHeight, byte Fps, byte BitrateMbps, byte Codec);

    public static Hello ReadHello(ReadOnlySpan<byte> d) => new(
        BinaryPrimitives.ReadUInt32LittleEndian(d[4..]),
        BinaryPrimitives.ReadUInt16LittleEndian(d[8..]),
        BinaryPrimitives.ReadUInt16LittleEndian(d[10..]),
        BinaryPrimitives.ReadUInt16LittleEndian(d[12..]),
        d[14],
        d[15],
        d[16]);

    public static byte[] BuildWelcome(WelcomeStatus status, uint token, StreamParameters p)
    {
        var d = new byte[WelcomeSize];
        WriteHeader(d, TypeWelcome);
        d[4] = (byte)status;
        d[5] = (byte)p.Codec;
        d[6] = (byte)p.Fps;
        d[7] = p.MouseEnabled ? (byte)1 : (byte)0;
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(8), token);
        BinaryPrimitives.WriteUInt16LittleEndian(d.AsSpan(12), (ushort)p.Width);
        BinaryPrimitives.WriteUInt16LittleEndian(d.AsSpan(14), (ushort)p.Height);
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(16), (uint)p.AudioSampleRate);
        return d;
    }

    public static uint ReadToken(ReadOnlySpan<byte> d) => BinaryPrimitives.ReadUInt32LittleEndian(d[4..]);
    public static uint ReadSeq(ReadOnlySpan<byte> d) => BinaryPrimitives.ReadUInt32LittleEndian(d[8..]);

    public static byte[] BuildKeyReply(ReadOnlySpan<byte> hostPublic)
    {
        var d = new byte[KeyReplySize];
        WriteHeader(d, TypeKeyReply);
        hostPublic.CopyTo(d.AsSpan(HeaderSize));
        return d;
    }

    public static bool IsValidKeyboard(ReadOnlySpan<byte> d)
    {
        int textBytes = d.Length - KeyboardHeaderSize;
        return textBytes >= 0 && textBytes <= MaxKeyboardChars * 2 && textBytes % 2 == 0;
    }

    public static byte ReadKeyboardKey(ReadOnlySpan<byte> d) => d[12];
    public static string ReadKeyboardText(ReadOnlySpan<byte> d) => Encoding.Unicode.GetString(d[KeyboardHeaderSize..]);

    public static byte[] BuildKeyboardAck(uint id, bool accepted)
    {
        var d = new byte[KeyboardAckSize];
        WriteHeader(d, TypeKeyboardAck);
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(4), id);
        d[8] = accepted ? (byte)1 : (byte)0;
        return d;
    }

    public static ControllerStatePacket ReadControllerState(ReadOnlySpan<byte> inputDatagram) =>
        MemoryMarshal.Read<ControllerStatePacket>(inputDatagram[12..]);
}

public enum VideoCodec : byte { H264 = 0, Hevc = 1 }

public sealed record StreamParameters(VideoCodec Codec, int Width, int Height, int Fps, int AudioSampleRate, bool MouseEnabled = false, int BitrateMbps = 30, int RequestedFps = 60);
