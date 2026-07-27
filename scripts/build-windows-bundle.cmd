@echo off
setlocal

rem Double-click this file on a Windows build computer to create the installer
rem with propel.exe and the one-click propel-web.exe launcher.
set "ROOT=%~dp0.."
pushd "%ROOT%" >nul

powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\package-windows.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
  echo Build failed with exit code %EXITCODE%.
) else (
  echo Build succeeded.
  echo Installer: "%ROOT%\release\windows\propel-1.0.0.exe"
  echo The installer adds both propel and propel-web launchers.
)
echo.
pause

popd >nul
exit /b %EXITCODE%
