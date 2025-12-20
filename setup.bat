@echo off
chcp 65001 >nul
echo ====================================
echo Markdown Editor 빌드 (Inno Setup)
echo ====================================
echo.

REM 필수 파일 확인
echo [1/5] 필수 파일 확인 중...
set MISSING_FILES=0

if not exist "icon.ico" (
    echo ❌ icon.ico 파일이 없습니다!
    set MISSING_FILES=1
)

if not exist "LICENSE" (
    echo ❌ LICENSE 파일이 없습니다!
    set MISSING_FILES=1
)

if not exist "installer-header.bmp" (
    echo ⚠️  installer-header.bmp 파일이 없습니다. ^(선택사항^)
    echo    권장 크기: 55x58 픽셀
)

if not exist "installer-sidebar.bmp" (
    echo ⚠️  installer-sidebar.bmp 파일이 없습니다. ^(선택사항^)
    echo    권장 크기: 164x314 픽셀
)

if %MISSING_FILES%==1 (
    echo.
    echo ❌ 필수 파일이 없어 빌드를 중단합니다.
    pause
    exit /b 1
)

echo ✅ 필수 파일 확인 완료
echo.

REM 의존성 설치
echo [2/5] NPM 의존성 설치 중...
if not exist "node_modules" (
    call npm install
    if errorlevel 1 (
        echo ❌ npm install 실패
        pause
        exit /b 1
    )
) else (
    echo ✅ node_modules 이미 존재 ^(건너뜀^)
)
echo ✅ 의존성 확인 완료
echo.

REM Electron 앱 빌드
echo [3/5] Electron 앱 빌드 중...
echo    빌드 출력: dist\win-unpacked\
call npm run build
if errorlevel 1 (
    echo ❌ Electron 빌드 실패
    pause
    exit /b 1
)

REM 빌드 결과 확인
if not exist "dist\win-unpacked\Markdown Editor.exe" (
    echo ❌ Electron 앱이 제대로 빌드되지 않았습니다!
    echo    예상 위치: dist\win-unpacked\Markdown Editor.exe
    pause
    exit /b 1
)

echo ✅ Electron 빌드 완료
echo.

REM Inno Setup 설치 확인
echo [4/5] Inno Setup 확인 중...
set INNO_PATH=

REM 일반적인 Inno Setup 설치 경로 확인
if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" (
    set INNO_PATH=C:\Program Files (x86)\Inno Setup 6\ISCC.exe
) else if exist "C:\Program Files\Inno Setup 6\ISCC.exe" (
    set INNO_PATH=C:\Program Files\Inno Setup 6\ISCC.exe
) else if exist "%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe" (
    set INNO_PATH=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe
) else if exist "%ProgramFiles%\Inno Setup 6\ISCC.exe" (
    set INNO_PATH=%ProgramFiles%\Inno Setup 6\ISCC.exe
)

if "%INNO_PATH%"=="" (
    echo ❌ Inno Setup이 설치되어 있지 않습니다!
    echo.
    echo Inno Setup 다운로드: https://jrsoftware.org/isdl.php
    echo 또는 Chocolatey로 설치: choco install innosetup
    echo.
    pause
    exit /b 1
)

echo ✅ Inno Setup 발견: %INNO_PATH%
echo.

REM Inno Setup으로 설치 프로그램 생성
echo [5/5] Inno Setup 설치 프로그램 생성 중...
echo    스크립트: installer.iss
"%INNO_PATH%" "installer.iss"
if errorlevel 1 (
    echo ❌ Inno Setup 빌드 실패
    pause
    exit /b 1
)
echo ✅ Inno Setup 빌드 완료
echo.

echo ====================================
echo ✅ 빌드 완료!
echo ====================================
echo.
echo 📦 설치 파일 위치:
if exist "dist\MarkdownEditor-Setup-1.0.0.exe" (
    echo    ✅ dist\MarkdownEditor-Setup-1.0.0.exe
) else (
    echo    ⚠️  설치 파일을 찾을 수 없습니다.
    dir /b dist\*.exe 2>nul
)
echo.
echo 💡 설치 파일을 실행하여 테스트하세요!
echo ====================================
echo.

REM 설치 파일 탐색기에서 열기
if exist "dist\MarkdownEditor-Setup-1.0.0.exe" (
    choice /C YN /M "dist 폴더를 여시겠습니까"
    if errorlevel 2 goto end
    if errorlevel 1 start "" "%CD%\dist"
)

:end
pause