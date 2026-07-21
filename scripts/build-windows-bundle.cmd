@echo off
setlocal

rem Run the PowerShell build script with a permissive policy for this process only.
set "ROOT=%~dp0.."
pushd "%ROOT%" >nul

powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\build-windows-bundle.ps1"
set "EXITCODE=%ERRORLEVEL%"

echo.
if not "%EXITCODE%"=="0" (
  echo Build failed with exit code %EXITCODE%.
) else (
  echo Build succeeded.
)
echo Output folder: "%ROOT%\dist"
echo.
pause

popd >nul
exit /b %EXITCODE%

