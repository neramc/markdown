; Inno Setup Script for Markdown Editor
; Requires Inno Setup 6.0 or later

#define MyAppName "Markdown Editor"
#define MyAppVersion "1.5.4"
#define MyAppPublisher "Madmovies"
#define MyAppURL "https://github.com/neramc/markdown"
#define MyAppExeName "Markdown Editor.exe"
#define MyAppIconName "icon.ico"

[Setup]
; 기본 정보
AppId={{8F9A7B2C-5D4E-4A1B-9C3E-7F6D8E5A4B2C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}

; 설치 경로
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
AllowNoIcons=yes

; 라이센스 및 정보 파일
LicenseFile=LICENSE
InfoBeforeFile=

; 출력 설정
OutputDir=dist
OutputBaseFilename=MarkdownEditor-Setup-{#MyAppVersion}
SetupIconFile={#MyAppIconName}
UninstallDisplayIcon={app}\{#MyAppIconName}

; 압축 설정
Compression=lzma2/max
SolidCompression=yes

; Windows 버전 요구사항
MinVersion=10.0
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64

; UI 설정
WizardStyle=modern
DisableWelcomePage=no
DisableDirPage=no
DisableProgramGroupPage=yes

; 배너 이미지 설정
WizardImageFile=installer-sidebar.bmp
WizardSmallImageFile=installer-header.bmp

; 권한 - 자동으로 모든 사용자용 설치
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline

[Languages]
Name: "korean"; MessagesFile: "compiler:Languages\Korean.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked
Name: "quicklaunchicon"; Description: "{cm:CreateQuickLaunchIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked; OnlyBelowVersion: 6.1; Check: not IsAdminInstallMode
Name: "associate_md"; Description: ".md 파일을 {#MyAppName}(으)로 열기"; GroupDescription: "파일 연결:"; Flags: unchecked

[Files]
; 애플리케이션 파일들
Source: "dist\win-unpacked\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#MyAppIconName}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
; 시작 메뉴 바로가기
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppIconName}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"

; 바탕화면 바로가기
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppIconName}"; Tasks: desktopicon

; 빠른 실행 바로가기
Name: "{userappdata}\Microsoft\Internet Explorer\Quick Launch\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: quicklaunchicon

[Registry]
; .md 파일 연결
Root: HKCR; Subkey: ".md"; ValueType: string; ValueName: ""; ValueData: "MarkdownEditor.File"; Flags: uninsdeletevalue; Tasks: associate_md
Root: HKCR; Subkey: ".markdown"; ValueType: string; ValueName: ""; ValueData: "MarkdownEditor.File"; Flags: uninsdeletevalue; Tasks: associate_md

Root: HKCR; Subkey: "MarkdownEditor.File"; ValueType: string; ValueName: ""; ValueData: "Markdown File"; Flags: uninsdeletekey; Tasks: associate_md
Root: HKCR; Subkey: "MarkdownEditor.File\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\{#MyAppIconName},0"; Tasks: associate_md
Root: HKCR; Subkey: "MarkdownEditor.File\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#MyAppExeName}"" ""%1"""; Tasks: associate_md

; 애플리케이션 정보
Root: HKLM; Subkey: "Software\{#MyAppName}"; ValueType: string; ValueName: "InstallPath"; ValueData: "{app}"; Flags: uninsdeletekey
Root: HKLM; Subkey: "Software\{#MyAppName}"; ValueType: string; ValueName: "Version"; ValueData: "{#MyAppVersion}"; Flags: uninsdeletekey

[Run]
; 설치 후 실행
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
// 기존 버전 제거
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  UninstallPath: String;
begin
  Result := '';
  
  // 기존 설치 버전 확인
  if RegQueryStringValue(HKLM, 'Software\Microsoft\Windows\CurrentVersion\Uninstall\{8F9A7B2C-5D4E-4A1B-9C3E-7F6D8E5A4B2C}_is1', 'UninstallString', UninstallPath) then
  begin
    if MsgBox('기존 버전이 설치되어 있습니다. 제거하시겠습니까?', mbConfirmation, MB_YESNO) = IDYES then
    begin
      Exec(RemoveQuotes(UninstallPath), '/SILENT', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
    end;
  end;
end;

// 설치 완료 메시지
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    // 파일 연결을 위한 시스템 새로고침
    // Windows에 파일 연결 변경 사항 알림
    RegWriteStringValue(HKEY_CURRENT_USER, 'Environment', 'MarkdownEditorInstalled', '1');
  end;
end;