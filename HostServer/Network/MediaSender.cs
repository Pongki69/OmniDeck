using System.Buffers.Binary;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace RemoteGameStreamer.Host.Network;

/// <summary>Sends video shards (paced) and audio packets to the connected phone.</summary>
public sealed class MediaSender : IDisposable
{
    private const int AudioChunkBytes = 960; // 5 ms of 48 kHz stereo s16

    private readonly Socket _video = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
    private readonly Socket _audio = new(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
    private readonly VideoPacketizer _packetizer = new();
    private readonly byte[] _audioDatagram = new byte[8 + AudioChunkBytes];
    private readonly byte[] _sealedAudio = new byte[8 + AudioChunkBytes + StreamCipher.Overhead];
    private readonly byte[] _sealedVideo = new byte[VideoPacketizer.DefaultPayloadSize + 64 + StreamCipher.Overhead];
    private readonly StreamCipher _videoCipher;
    private readonly StreamCipher _audioCipher;
    private readonly double _paceWindowTicks;
    private uint _frameId;
    private uint _audioSeq;
    private readonly bool _audioEnabled;
    private long _frames, _bytes;

    public long Frames => Interlocked.Read(ref _frames);
    public long Bytes => Interlocked.Read(ref _bytes);

    public MediaSender(SessionInfo session, int fps, SessionKeys keys)
    {
        _videoCipher = keys.Video;
        _audioCipher = keys.Audio;
        _video.SendBufferSize = 4 * 1024 * 1024;
        _video.Connect(session.Video);
        _audioEnabled = session.Audio.Port != 0; // the phone sends port 0 when sound is off
        if (_audioEnabled) _audio.Connect(session.Audio);
        // Spread each frame across half a frame interval so a large keyframe does
        // not arrive as one burst and overflow the Wi-Fi queue.
        _paceWindowTicks = Stopwatch.Frequency * 0.5 / fps;
    }

    public void SendFrame(ReadOnlySpan<byte> frame, bool keyframe)
    {
        Interlocked.Increment(ref _frames);
        Interlocked.Add(ref _bytes, frame.Length);

        int total = VideoPacketizer.ShardCount(frame.Length);
        long start = Stopwatch.GetTimestamp();
        int sent = 0;
        var socket = _video;
        double window = _paceWindowTicks;
        var cipher = _videoCipher;
        var output = _sealedVideo;

        _packetizer.Packetize(frame, ++_frameId, keyframe, datagram =>
        {
            int length = cipher.Seal(datagram, output);
            try { socket.Send(output.AsSpan(0, length)); }
            catch (SocketException) { /* phone briefly unreachable: UDP, carry on */ }

            sent++;
            if (total <= VideoPacketizer.GroupSize || sent % VideoPacketizer.GroupSize != 0) return;

            long target = start + (long)(window * sent / total);
            while (true)
            {
                long remaining = target - Stopwatch.GetTimestamp();
                if (remaining <= 0) break;
                if (remaining > Stopwatch.Frequency / 500) Thread.Sleep(1);
                else Thread.Yield();
            }
        });
    }

    /// <summary>Sends interleaved stereo s16le PCM, split to fit the MTU.</summary>
    public void SendAudio(ReadOnlySpan<byte> pcm)
    {
        if (!_audioEnabled) return;

        Protocol.WriteHeader(_audioDatagram, Protocol.TypeAudio);
        while (!pcm.IsEmpty)
        {
            int n = Math.Min(AudioChunkBytes, pcm.Length);
            BinaryPrimitives.WriteUInt32LittleEndian(_audioDatagram.AsSpan(4), ++_audioSeq);
            pcm[..n].CopyTo(_audioDatagram.AsSpan(8));
            int length = _audioCipher.Seal(_audioDatagram.AsSpan(0, 8 + n), _sealedAudio);
            try { _audio.Send(_sealedAudio.AsSpan(0, length)); }
            catch (SocketException) { }
            pcm = pcm[n..];
        }
    }

    public void Dispose()
    {
        _video.Dispose();
        _audio.Dispose();
    }
}
