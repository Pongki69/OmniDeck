using System.Runtime.InteropServices;

namespace RemoteGameStreamer.Host.Network;

/// <summary>
/// Controller state body. Exactly 17 bytes, little-endian. Must match
/// ControllerStatePacket.kt on the client.
/// </summary>
[StructLayout(LayoutKind.Sequential, Pack = 1)]
public struct ControllerStatePacket
{
    public const int Size = 17;

    public ushort Buttons;      // A=1, B=2, X=4, Y=8, LB=16, RB=32, Start=64, Select=128, L3=256, R3=512, Up=1024, Down=2048, Left=4096, Right=8192, Guide=16384
    public byte LeftTrigger;    // 0 to 255
    public byte RightTrigger;   // 0 to 255
    public short ThumbLX;       // -32768 to 32767
    public short ThumbLY;
    public short ThumbRX;
    public short ThumbRY;
    public short MouseDeltaX;   // Relative mouse movement (touchpad mode)
    public short MouseDeltaY;
    public byte MouseButtons;   // Bit 0: left, bit 1: right
}
