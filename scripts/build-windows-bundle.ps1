# Compatibility entry point: build both the single installer EXE and the
# folder-based portable ZIP.

[CmdletBinding()]
param(
    [string]$AppVersion = "1.0.0",
    [switch]$SkipTests
)

$arguments = @(
    "-PackageType", "All",
    "-AppVersion", $AppVersion
)
if ($SkipTests) {
    $arguments += "-SkipTests"
}

& (Join-Path $PSScriptRoot "package-windows.ps1") @arguments
exit $LASTEXITCODE
