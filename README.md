# OmniDeck

Streams a Windows PC's screen and audio to an Android phone over local Wi-Fi, and sends the
phone's touch controls back as a virtual Xbox 360 controller (ViGEmBus). The game process is
never hooked, inspected or modified.

## First-time setup

1. Install the ViGEmBus driver: run `.tools/installers/ViGEmBus_1.22.0_x64_x86_arm64.exe`.
2. Allow the input port through the Windows firewall, once, from an administrator PowerShell:

   ```powershell
   New-NetFirewallRule -DisplayName "Remote Game Streamer" -Direction Inbound -Protocol UDP -LocalPort 9050 -Action Allow -Profile Private
   ```

3. Install `dist/OmniDeck.apk` on the phone.

## Running

1. Open `dist/OmniDeck/OmniDeck.exe`. Keep `ffmpeg.exe` in the same folder.
2. Put the phone on the same network, open the app, and enter the PC address and PIN shown in the
   window. The PIN changes on each launch unless you pass `--pin`.

Logs are shown in the window and saved in `%LOCALAPPDATA%\OmniDeck\logs`.

For 120 FPS, set the PC monitor to 120 Hz or higher in Windows display settings and choose 120 FPS
in the app. The stream can't exceed the monitor's refresh rate.

To change the app icons, save the artwork as `Branding/icon.png`, run
`python Branding/apply_icon.py`, and rebuild.

The virtual controller exists only while the phone is connected. Mouse input from the phone is
off unless you start the host with `--mouse`.

Resolution, frame rate, bitrate, codec and sound are chosen in the app's Settings screen. The
resolution, frame rate, bitrate and codec options below only apply if a client leaves them unset.

| Option | Default | Meaning |
| --- | --- | --- |
| `--encoder auto\|nvenc\|amf` | `auto` | `auto` tests NVENC, then AMF, and uses the first that works |
| `--codec h264\|hevc` | `h264` | Video codec |
| `--bitrate <Mbps>` | `30` | Lower this if the picture breaks up on weak Wi-Fi |
| `--fps <n>` | `60` | Capture rate |
| `--max-height <px>` | `1080` | Larger desktops are scaled down to this |
| `--output <n>` | `0` | Monitor index |
| `--no-pad`, `--no-audio` | off | Stream without the controller or sound |
| `--mouse` | off | Allow touchpad mode. Run the host as administrator for games that run elevated |

## Phone controls

Settings > **Edit control layout** moves, resizes and hides controls. The joystick stays in one
place by default; turn off "Lock joystick in place" to have it appear where you touch.

Drag on any free area to move the camera. With `--mouse`, a **Pad**
button switches the free area to a touchpad: drag moves the mouse, tap clicks, two-finger tap
right-clicks.

## Security

Pairing needs the PIN, one phone can connect at a time, and five wrong PINs lock that address
out for 30 seconds. Traffic is not encrypted, so anyone on the same network can capture the
video. Use this only on a network you trust and never forward port 9050 to the internet.

## Development

Toolchains live in `.tools/`, which is not checked in.

```powershell
.tools\dotnet\dotnet test HostServer.Tests -c Release
cd MobileClient; ..\.tools\gradle-8.9\bin\gradle testDebugUnitTest assembleDebug   # needs JAVA_HOME=.tools\jdk
```

Host: `HostServer/` (C#, .NET 8). Client: `MobileClient/` (Kotlin). The wire format is defined in
`HostServer/Network/Protocol.cs` and `MobileClient/.../net/Protocol.kt`. Both test suites assert the
same golden bytes, so a layout mismatch fails a test.

Capture is FFmpeg's `ddagrab` (DXGI Desktop Duplication) feeding NVENC or AMF, with RTP over
loopback so each frame's end is known immediately.
