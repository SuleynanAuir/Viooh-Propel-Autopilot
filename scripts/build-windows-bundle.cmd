@echo off
setlocal

rem Double-click this file on a Windows build computer to create the installer
rem with propel.exe as the one-click web launcher and propel-desktop.exe as
rem the optional legacy desktop launcher.
set "ROOT=%~dp0.."
pushd "%ROOT%" >nul

powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\package-windows.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
  echo Build failed with exit code %EXITCODE%.
) else (
  echo Build succeeded.
  echo Installer: "%ROOT%\release\windows\propel-1.1.0.exe"
  echo The installer makes propel.exe open the web UI and also adds propel-desktop.exe.
)
echo.
pause

popd >nul
exit /b %EXITCODE%
