# One-time cleanup after a bad jpackage run (nested target\dist\propel\app\dist\...).
# Close propel.exe first, then run from project root:
#   .\scripts\clean-package-artifacts.ps1

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

function Remove-TreeForce([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $item = Get-Item -LiteralPath $Path
    $full = $item.FullName
    Write-Host "Removing: $full"
    # \\?\ prefix allows deleting very deep paths on Windows
    $long = if ($full.StartsWith("\\?\")) { $full } else { "\\?\$full" }
    cmd /c "rd /s /q `"$long`"" | Out-Null
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction Continue
    }
    if (Test-Path -LiteralPath $Path) {
        Write-Warning "Could not fully remove $Path — close propel.exe / Excel, reboot, or delete in Explorer."
    }
}

Get-Process -Name propel -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

Remove-TreeForce (Join-Path $Root "target\dist")
Remove-TreeForce (Join-Path $Root "dist")
Remove-TreeForce (Join-Path $Root "target\jpackage-input")
Remove-TreeForce (Join-Path $env:TEMP "propel-jpackage")

Write-Host "Cleanup finished."
