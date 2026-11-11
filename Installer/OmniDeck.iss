#ifndef AppVersion
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{8CFE07C1-69EE-463A-A29C-1C270433B1E4}
AppName=OmniDeck
AppVersion={#AppVersion}
AppPublisher=Pongki69
AppPublisherURL=https://github.com/Pongki69/OmniDeck
; Install per-user so OmniDeck's signed in-app updater can replace its own files without admin rights.
DefaultDirName={localappdata}\Programs\OmniDeck
DefaultGroupName=OmniDeck
UninstallDisplayIcon={app}\OmniDeck.exe
OutputDir=..\
OutputBaseFilename=OmniDeck-Setup
SetupIconFile=..\HostServer\Assets\omnideck.ico
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
MinVersion=10.0
PrivilegesRequired=lowest
CloseApplications=yes
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
VersionInfoVersion={#AppVersion}
VersionInfoProductName=OmniDeck
VersionInfoCompany=Pongki69
Uninstallable=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "vigembus"; Description: "Install ViGEmBus (retired third-party kernel driver; required for virtual Xbox controller input)"; GroupDescription: "Controller support:"; Flags: checkedonce; Check: not IsViGEmInstalled
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "..\dist\OmniDeck\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "THIRD_PARTY_NOTICES.txt"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\.tools\installers\ViGEmBus_1.22.0_x64_x86_arm64.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: not IsViGEmInstalled

[Icons]
Name: "{group}\OmniDeck"; Filename: "{app}\OmniDeck.exe"
Name: "{autodesktop}\OmniDeck"; Filename: "{app}\OmniDeck.exe"; Tasks: desktopicon

[Run]
Filename: "{tmp}\ViGEmBus_1.22.0_x64_x86_arm64.exe"; Description: "Install ViGEmBus virtual controller driver (retired third-party kernel driver)"; Tasks: vigembus; Flags: postinstall waituntilterminated skipifsilent runasoriginaluser
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -Command ""Get-NetFirewallRule -DisplayName 'OmniDeck Game Stream (UDP 9050)' -ErrorAction SilentlyContinue | Remove-NetFirewallRule; New-NetFirewallRule -DisplayName 'OmniDeck Game Stream (UDP 9050)' -Direction Inbound -Protocol UDP -LocalPort 9050 -Action Allow -Profile Private -RemoteAddress LocalSubnet"""; Verb: "runas"; Flags: shellexec waituntilterminated
Filename: "{app}\OmniDeck.exe"; Description: "Launch OmniDeck"; Flags: postinstall nowait skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\WindowsPowerShell\v1.0\powershell.exe"; Parameters: "-NoProfile -Command ""Get-NetFirewallRule -DisplayName 'OmniDeck Game Stream (UDP 9050)' -ErrorAction SilentlyContinue | Remove-NetFirewallRule"""; Verb: "runas"; Flags: shellexec waituntilterminated; RunOnceId: "RemoveFirewallRule"

[Code]
function IsViGEmInstalled(): Boolean;
begin
  Result := RegKeyExists(HKLM, 'SYSTEM\CurrentControlSet\Services\ViGEmBus');
end;
