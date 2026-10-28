#define AppName "M6GH AS-Built Config"
#define AppVersion "1.0.0"
#ifndef TargetArch
  #define TargetArch "x64"
#endif
#if TargetArch == "x64"
  #define AppGuid "{{8DCC8A9F-0C42-4B38-8F0B-7092E6F12064}"
  #define ArchSuffix "x64"
#else
  #define AppGuid "{{E7AA9B5C-AB7D-4C29-9B15-2B2B753C89D1}"
  #define ArchSuffix "x86"
#endif

[Setup]
AppId={#AppGuid}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=D3DIMON3
AppVerName={#AppName} {#AppVersion} ({#ArchSuffix})
DefaultDirName={autopf}\M6GH AS-Built Config {#ArchSuffix}
DefaultGroupName={#AppName} {#ArchSuffix}
UninstallDisplayIcon={app}\M6GH-AS-Built-Config.exe
SetupIconFile=..\M6GH-AS-Built-Config.ico
OutputDir=dist-installer
OutputBaseFilename=M6GH-AS-Built-Config-Setup-{#ArchSuffix}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
DisableProgramGroupPage=yes
#if TargetArch == "x64"
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
CloseApplications=yes
RestartApplications=no

[Files]
Source: "dist\M6GH-AS-Built-Config.exe"; DestDir: "{app}"; Flags: ignoreversion

[Tasks]
Name: "desktopicon"; Description: "Создать ярлык на рабочем столе"; GroupDescription: "Дополнительные задачи:"; Flags: checkedonce

[Icons]
Name: "{group}\M6GH AS-Built Config"; Filename: "{app}\M6GH-AS-Built-Config.exe"; IconFilename: "{app}\M6GH-AS-Built-Config.exe"
Name: "{autodesktop}\M6GH AS-Built Config"; Filename: "{app}\M6GH-AS-Built-Config.exe"; IconFilename: "{app}\M6GH-AS-Built-Config.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\M6GH-AS-Built-Config.exe"; Description: "Запустить M6GH AS-Built Config"; Flags: nowait postinstall skipifsilent
