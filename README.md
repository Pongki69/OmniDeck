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

The virtual controller exists only while the phone is connected. Mouse and keyboard input from
the phone are off each time the host starts. Tick **Allow mouse and keyboard from phone** in the
host window (or start it with `--mouse`) before the phone connects.

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
| `--mouse` | off | Start with mouse and keyboard from the phone allowed. Run the host as administrator for games that run elevated |

## Phone controls

Settings > **Edit control layout** moves, resizes and hides controls. The joystick stays in one
place by default; turn off "Lock joystick in place" to have it appear where you touch.

Drag on any free area to move the camera. Tap **Sens** during a stream to change how sensitive the
swipe is, up to 400%. It applies immediately and you can swipe to try it while the slider is open.
The same setting is in Settings > **Camera sensitivity**.

With mouse and keyboard allowed, a **Pad** button switches the free area to a touchpad: drag moves
the mouse, tap clicks, two-finger tap right-clicks. To type into a login form, use the touchpad to
click the field, then tap **Kbd**, enter the text and press **Send**. **Tab**, **Enter** and
**Delete** send those keys. Text is hidden unless you tick **Show**, and it is never saved.

## Security

Pairing needs the PIN, one phone can connect at a time, and five wrong PINs lock that address
out for 30 seconds. All traffic after the key exchange, including the PIN, controls, typed text,
video and sound, is encrypted with AES-256-GCM using keys from a fresh P-256 exchange per
connection.

The PC keeps a permanent identity key in `%LOCALAPPDATA%\OmniDeck`, encrypted to your Windows
account. The phone remembers it after the first successful pairing and refuses to connect if it
changes, which blocks anyone impersonating the PC later. Do the first pairing on a network you
trust, because nothing can verify the PC before then. If you reinstall Windows or lose that file,
use Settings > **Forget saved PCs** on the phone. Never forward port 9050 to the internet.

## Updates

Both apps check GitHub Releases when they open. The PC app shows an **Update to x.y.z** button
beside the window controls, and the phone app offers the update in a dialog. Both also have a
manual **Check for updates** button. Update both together, because their versions need to match.

The PC app only installs a download signed with the release key. Android only installs an APK
signed with the same key as the installed app.

## Publishing a release

The release workflow needs three repository secrets, under Settings > Secrets and variables >
Actions: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD` and `UPDATE_SIGNING_KEY`.

To publish, push a version tag. The workflow tests both apps, builds them and creates the release:

```powershell
git tag v1.0.1
git push origin v1.0.1
```

Keep a backup of the signing keys outside this repository. Without the Android key, future APKs
can't update installed apps, and without the update key, the PC app won't accept new versions.

## Development

Toolchains live in `.tools/`, which is not checked in.

```powershell
.tools\dotnet\dotnet test HostServer.Tests -c Release
cd MobileClient; ..\.tools\gradle-8.9\bin\gradle testDebugUnitTest assembleDebug   # needs JAVA_HOME=.tools\jdk
```

Host: `HostServer/` (C#, .NET 8). Client: `MobileClient/` (Kotlin). The wire format is defined in
`HostServer/Network/Protocol.cs` and `MobileClient/.../net/Protocol.kt`, and encryption in
`HostServer/Network/SecureTransport.cs` and `MobileClient/.../net/Crypto.kt`. Both test suites assert
the same golden bytes, so a layout or key-derivation mismatch fails a test.

Capture is FFmpeg's `ddagrab` (DXGI Desktop Duplication) feeding NVENC or AMF, with RTP over
loopback so each frame's end is known immediately.
