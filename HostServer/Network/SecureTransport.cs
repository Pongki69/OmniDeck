using System.Buffers.Binary;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;

namespace RemoteGameStreamer.Host.Network;

/// <summary>The PC's long-lived P-256 key. The phone remembers its public half after the first pairing.</summary>
public sealed class HostIdentity
{
    public const int PublicKeySize = 65; // 0x04 | X | Y

    public ECDiffieHellman Key { get; }
    public byte[] PublicKey { get; }

    private HostIdentity(ECDiffieHellman key)
    {
        Key = key;
        var q = key.ExportParameters(false).Q;
        PublicKey = new byte[PublicKeySize];
        PublicKey[0] = 4;
        q.X!.CopyTo(PublicKey, 1);
        q.Y!.CopyTo(PublicKey, 33);
    }

    public static HostIdentity CreateNew() => new(ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256));

    public static HostIdentity FromPrivateKey(byte[] d)
    {
        var key = ECDiffieHellman.Create();
        var derived = ECDiffieHellman.Create(new ECParameters { Curve = ECCurve.NamedCurves.nistP256, D = d });
        key.ImportParameters(derived.ExportParameters(true));
        return new HostIdentity(key);
    }

    /// <summary>The private key is stored encrypted to the current Windows user.</summary>
    public static HostIdentity LoadOrCreate(string path)
    {
        try
        {
            var key = ECDiffieHellman.Create();
            key.ImportPkcs8PrivateKey(ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser), out _);
            return new HostIdentity(key);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or CryptographicException)
        {
            var identity = CreateNew();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllBytes(path, ProtectedData.Protect(identity.Key.ExportPkcs8PrivateKey(), null, DataProtectionScope.CurrentUser));
            return identity;
        }
    }
}

/// <summary>
/// One key in one direction. Packets are 'O' 'D' 0x20 version | counter u64 | ciphertext | 16-byte tag.
/// Not safe for concurrent use: each stream has its own instance and its own thread.
/// </summary>
public sealed class StreamCipher
{
    public const int Overhead = Protocol.SealedHeaderSize + TagSize;
    private const int TagSize = 16;

    private readonly AesGcm _aes;
    private ulong _counter;

    public StreamCipher(byte[] key) => _aes = new AesGcm(key, TagSize);

    public int Seal(ReadOnlySpan<byte> inner, Span<byte> output)
    {
        ulong counter = ++_counter;
        Protocol.WriteHeader(output, Protocol.TypeSealed);
        BinaryPrimitives.WriteUInt64LittleEndian(output[4..], counter);

        Span<byte> nonce = stackalloc byte[12];
        nonce.Clear();
        BinaryPrimitives.WriteUInt64LittleEndian(nonce[4..], counter);

        int h = Protocol.SealedHeaderSize;
        _aes.Encrypt(nonce, inner, output.Slice(h, inner.Length), output.Slice(h + inner.Length, TagSize), output[..h]);
        return h + inner.Length + TagSize;
    }

    public byte[] Seal(ReadOnlySpan<byte> inner)
    {
        var output = new byte[inner.Length + Overhead];
        Seal(inner, output);
        return output;
    }

    /// <summary>Returns the decrypted datagram, or null if it is malformed or has been tampered with.</summary>
    public byte[]? Open(ReadOnlySpan<byte> sealedDatagram)
    {
        if (sealedDatagram.Length < Overhead) return null;

        int h = Protocol.SealedHeaderSize;
        Span<byte> nonce = stackalloc byte[12];
        nonce.Clear();
        sealedDatagram.Slice(4, 8).CopyTo(nonce[4..]);

        var inner = new byte[sealedDatagram.Length - Overhead];
        try
        {
            _aes.Decrypt(nonce, sealedDatagram.Slice(h, inner.Length), sealedDatagram.Slice(h + inner.Length, TagSize), inner, sealedDatagram[..h]);
        }
        catch (CryptographicException)
        {
            return null;
        }
        return inner;
    }
}

/// <summary>Per-connection keys, all derived from one ECDH secret. Mirrors Crypto.kt.</summary>
public sealed class SessionKeys
{
    public byte[] HelloKey { get; }
    public StreamCipher ClientToHost { get; }
    public StreamCipher Control { get; }
    public StreamCipher Video { get; }
    public StreamCipher Audio { get; }

    public SessionKeys(byte[] sharedSecret, ReadOnlySpan<byte> clientPublic, ReadOnlySpan<byte> hostPublic)
    {
        var salt = new byte[clientPublic.Length + hostPublic.Length];
        clientPublic.CopyTo(salt);
        hostPublic.CopyTo(salt.AsSpan(clientPublic.Length));

        byte[] Derive(string label) => HKDF.DeriveKey(HashAlgorithmName.SHA256, sharedSecret, 32, salt, Encoding.ASCII.GetBytes("OmniDeck " + label));

        HelloKey = Derive("hello");
        ClientToHost = new StreamCipher(Derive("c2s"));
        Control = new StreamCipher(Derive("control"));
        Video = new StreamCipher(Derive("video"));
        Audio = new StreamCipher(Derive("audio"));
    }
}

/// <summary>
/// Encrypted wrapper around <see cref="SessionManager"/>. Unencrypted datagrams never get past this class.
/// The phone sends its PIN only after encrypting it to the PC's public key.
/// </summary>
public sealed class SecureTransport
{
    private sealed record Active(IPEndPoint From, string ClientKey, SessionKeys Keys);

    private readonly HostIdentity _identity;
    private readonly SessionManager _sessions;
    private readonly Dictionary<string, IPEndPoint> _seenHellos = new();
    private Active? _active;

    public SecureTransport(HostIdentity identity, SessionManager sessions)
    {
        _identity = identity;
        _sessions = sessions;
    }

    /// <summary>The keys of the handshake being processed. Read during SessionStarted, on the same thread.</summary>
    public SessionKeys? HandshakeKeys { get; private set; }

    /// <summary>Called from the single receive thread only.</summary>
    public byte[]? HandleDatagram(ReadOnlySpan<byte> d, IPEndPoint from, long nowMs)
    {
        if (!Protocol.HasHeader(d)) return null;

        switch (d[2])
        {
            case Protocol.TypeKeyRequest when d.Length == Protocol.KeyRequestSize:
                return Protocol.BuildKeyReply(_identity.PublicKey);

            case Protocol.TypeSecureHello when d.Length == Protocol.SecureHelloSize:
                return HandleSecureHello(d, from, nowMs);

            case Protocol.TypeSealed:
                if (_active is not { } a || !a.From.Equals(from)) return null;
                var inner = a.Keys.ClientToHost.Open(d);
                if (inner == null) return null;
                var reply = _sessions.HandleDatagram(inner, from, nowMs);
                return reply == null ? null : a.Keys.Control.Seal(reply);

            default:
                return null;
        }
    }

    private byte[]? HandleSecureHello(ReadOnlySpan<byte> d, IPEndPoint from, long nowMs)
    {
        var clientPublic = d.Slice(Protocol.HeaderSize, HostIdentity.PublicKeySize);
        string clientKey = Convert.ToHexString(clientPublic);

        // A captured handshake replayed from another address must not start a session.
        if (_seenHellos.TryGetValue(clientKey, out var first) && !first.Equals(from)) return null;

        SessionKeys keys;
        byte[] hello = new byte[Protocol.HelloSize];
        try
        {
            keys = _active is { } current && current.ClientKey == clientKey ? current.Keys : DeriveKeys(clientPublic);

            int aadSize = Protocol.HeaderSize + HostIdentity.PublicKeySize;
            using var aes = new AesGcm(keys.HelloKey, 16);
            aes.Decrypt(new byte[12], d.Slice(aadSize, Protocol.HelloSize), d.Slice(aadSize + Protocol.HelloSize, 16), hello, d[..aadSize]);
        }
        catch (CryptographicException)
        {
            return null;
        }

        HandshakeKeys = keys;
        var welcome = _sessions.HandleDatagram(hello, from, nowMs);
        if (welcome == null) return null;

        if (welcome[4] == (byte)Protocol.WelcomeStatus.Ok)
        {
            _seenHellos[clientKey] = from; // only after the right PIN, so strangers can't grow this
            _active = new Active(from, clientKey, keys);
        }
        return keys.Control.Seal(welcome);
    }

    private SessionKeys DeriveKeys(ReadOnlySpan<byte> clientPublic)
    {
        if (clientPublic[0] != 4) throw new CryptographicException();

        using var peer = ECDiffieHellman.Create(new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = clientPublic.Slice(1, 32).ToArray(), Y = clientPublic.Slice(33, 32).ToArray() },
        });
        using var peerKey = peer.PublicKey;
        return new SessionKeys(_identity.Key.DeriveRawSecretAgreement(peerKey), clientPublic, _identity.PublicKey);
    }
}
