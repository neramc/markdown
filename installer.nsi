; Markdown Editor Installer Script
; NSIS Modern User Interface

!include "MUI2.nsh"

; 애플리케이션 정보
Name "Markdown Editor"
OutFile "MarkdownEditor-Setup.exe"
InstallDir "$PROGRAMFILES\Markdown Editor"
InstallDirRegKey HKLM "Software\MarkdownEditor" "Install_Dir"
RequestExecutionLevel admin

; 인터페이스 설정
!define MUI_ABORTWARNING
!define MUI_ICON "icon.ico"
!define MUI_UNICON "icon.ico"

; 배너 이미지 설정 (1920x1080을 164x314로 리사이즈 필요)
; installer-header.bmp: 150x57 픽셀 (상단 배너)
; installer-sidebar.bmp: 164x314 픽셀 (사이드바)
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_BITMAP "installer-header.bmp"
!define MUI_HEADERIMAGE_RIGHT
!define MUI_WELCOMEFINISHPAGE_BITMAP "installer-sidebar.bmp"

; 페이지
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "LICENSE.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

; 언어
!insertmacro MUI_LANGUAGE "Korean"
!insertmacro MUI_LANGUAGE "English"

; 설치 섹션
Section "Markdown Editor" SecMain
  SetOutPath "$INSTDIR"
  
  ; 파일 복사
  File /r "dist\win-unpacked\*.*"
  File "icon.ico"
  
  ; 레지스트리 키 작성
  WriteRegStr HKLM "Software\MarkdownEditor" "Install_Dir" "$INSTDIR"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "DisplayName" "Markdown Editor"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "DisplayIcon" "$INSTDIR\icon.ico"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "Publisher" "Markdown Editor Team"
  WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "DisplayVersion" "1.0.0"
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "NoModify" 1
  WriteRegDWORD HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor" "NoRepair" 1
  
  ; .md 파일 연결
  WriteRegStr HKCR ".md" "" "MarkdownEditor.File"
  WriteRegStr HKCR "MarkdownEditor.File" "" "Markdown File"
  WriteRegStr HKCR "MarkdownEditor.File\DefaultIcon" "" "$INSTDIR\icon.ico"
  WriteRegStr HKCR "MarkdownEditor.File\shell\open\command" "" '"$INSTDIR\Markdown Editor.exe" "%1"'
  
  ; 바탕화면 바로가기
  CreateShortCut "$DESKTOP\Markdown Editor.lnk" "$INSTDIR\Markdown Editor.exe" "" "$INSTDIR\icon.ico"
  
  ; 시작 메뉴 바로가기
  CreateDirectory "$SMPROGRAMS\Markdown Editor"
  CreateShortCut "$SMPROGRAMS\Markdown Editor\Markdown Editor.lnk" "$INSTDIR\Markdown Editor.exe" "" "$INSTDIR\icon.ico"
  CreateShortCut "$SMPROGRAMS\Markdown Editor\Uninstall.lnk" "$INSTDIR\uninstall.exe"
  
  ; 언인스톨러 생성
  WriteUninstaller "$INSTDIR\uninstall.exe"
SectionEnd

; 언인스톨 섹션
Section "Uninstall"
  ; 레지스트리 키 삭제
  DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\MarkdownEditor"
  DeleteRegKey HKLM "Software\MarkdownEditor"
  DeleteRegKey HKCR ".md"
  DeleteRegKey HKCR "MarkdownEditor.File"
  
  ; 파일 삭제
  Delete "$INSTDIR\*.*"
  RMDir /r "$INSTDIR"
  
  ; 바로가기 삭제
  Delete "$DESKTOP\Markdown Editor.lnk"
  Delete "$SMPROGRAMS\Markdown Editor\*.*"
  RMDir "$SMPROGRAMS\Markdown Editor"
SectionEnd