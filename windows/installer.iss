; Inno-Setup-Skript für Honeys Werkstatt (Installation ohne Adminrechte, pro Benutzer:in)
#define AppVersion GetEnv("HW_VERSION")
#if AppVersion == ""
  #define AppVersion "1.0.0"
#endif

[Setup]
AppId={{EDD2D7CA-10E7-4F92-8F62-B5F948950037}
AppName=Honeys Werkstatt
AppVersion={#AppVersion}
AppPublisher=Honey Balecta
DefaultDirName={localappdata}\Programs\Honeys Werkstatt
DefaultGroupName=Honeys Werkstatt
DisableProgramGroupPage=yes
DisableDirPage=auto
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=HoneysWerkstatt-Setup-{#AppVersion}
SetupIconFile=werkstatt.ico
UninstallDisplayIcon={app}\HoneysWerkstatt.exe
UninstallDisplayName=Honeys Werkstatt
Compression=lzma2/normal
SolidCompression=no
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

[Languages]
Name: "de"; MessagesFile: "compiler:Languages\German.isl"

[Tasks]
Name: "desktopicon"; Description: "Symbol auf dem Desktop anlegen"; GroupDescription: "Zusätzlich:"; Flags: unchecked

[Files]
Source: "..\dist\HoneysWerkstatt\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
; alte Programmdateien entfernen, damit nichts Veraltetes liegen bleibt
Type: filesandordirs; Name: "{app}\_internal"

[Icons]
Name: "{autoprograms}\Honeys Werkstatt"; Filename: "{app}\HoneysWerkstatt.exe"; AppUserModelID: "honeybalecta.HoneysWerkstatt"
Name: "{autodesktop}\Honeys Werkstatt"; Filename: "{app}\HoneysWerkstatt.exe"; Tasks: desktopicon; AppUserModelID: "honeybalecta.HoneysWerkstatt"

[Registry]
; Autostart legt die Werkstatt selbst an (abschaltbar in den Einstellungen); beim Entfernen verschwindet der Eintrag
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "HoneysWerkstatt"; Flags: uninsdeletevalue dontcreatekey

[Run]
; Nach dem Selbst-Update startet die Werkstatt wieder (Parameter /werkstattstart=--zeigen oder --hintergrund)
Filename: "{app}\HoneysWerkstatt.exe"; Parameters: "{param:werkstattstart|}"; Description: "Honeys Werkstatt jetzt starten"; Flags: nowait postinstall

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/IM HoneysWerkstatt.exe /F"; Flags: runhidden; RunOnceId: "WerkstattBeenden"
