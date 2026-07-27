# Build a self-contained Windows release.
#
# Default output:
#   release\windows\propel-1.0.0.exe
#
# The EXE is a Windows installer. It installs both propel.exe (desktop UI) and
# propel-web.exe (one-click local web UI), plus a private Java runtime.

[CmdletBinding()]
param(
    [ValidateSet("Installer", "Portable", "All")]
    [string]$PackageType = "Installer",

    [string]$AppVersion = "1.0.0",

    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

$AppName = "propel"
$WebLauncherName = "propel-web"
$JarName = "Auto_project-1.0-SNAPSHOT.jar"
$ReleaseRoot = Join-Path $Root "release\windows"
$StageDir = Join-Path $Root "target\jpackage-input"
$TempRoot = Join-Path $env:TEMP "propel-jpackage-$PID"
$UpgradeUuid = "54735d54-fac1-41d4-8a41-3ed1742fd84b"
$SupplyMatrixPath = Join-Path $Root "feishu\supply_matrix.xlsx"

function Resolve-JdkBin {
    if ($env:JAVA_HOME) {
        $bin = Join-Path $env:JAVA_HOME "bin"
        if ((Test-Path (Join-Path $bin "java.exe")) -and
            (Test-Path (Join-Path $bin "jpackage.exe"))) {
            return $bin
        }
    }

    $jpackageCommand = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($jpackageCommand) {
        return Split-Path $jpackageCommand.Source -Parent
    }

    throw @"
jpackage.exe was not found.
Install JDK 21 (not a JRE), set JAVA_HOME to that JDK, and run this script again.
"@
}

function Resolve-Maven {
    foreach ($commandName in @("mvn.cmd", "mvn.exe", "mvn")) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    throw "Maven was not found. Install Maven 3.9 or newer and add its bin directory to PATH."
}

function Enable-WixToolset {
    $candle = Get-Command candle.exe -ErrorAction SilentlyContinue
    $light = Get-Command light.exe -ErrorAction SilentlyContinue
    if ($candle -and $light) {
        return
    }

    $candidateBins = [System.Collections.Generic.List[string]]::new()
    foreach ($wixRoot in @($env:WIX, $env:WIX_ROOT)) {
        if ($wixRoot) {
            $candidateBins.Add((Join-Path $wixRoot "bin"))
            $candidateBins.Add($wixRoot)
        }
    }

    $programFilesX86 = ${env:ProgramFiles(x86)}
    if ($programFilesX86) {
        $wixFolders = Get-ChildItem `
            -Path (Join-Path $programFilesX86 "WiX Toolset v3.*") `
            -Directory `
            -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending
        foreach ($folder in $wixFolders) {
            $candidateBins.Add((Join-Path $folder.FullName "bin"))
        }
    }

    foreach ($candidate in $candidateBins) {
        if ((Test-Path (Join-Path $candidate "candle.exe")) -and
            (Test-Path (Join-Path $candidate "light.exe"))) {
            $env:PATH = "$candidate;$env:PATH"
            return
        }
    }

    throw @"
WiX Toolset 3 was not found. It is required to create the single installer EXE.
Install it and retry:
  choco install wixtoolset --no-progress -y

If you only need the folder-based build, run:
  .\scripts\package-windows.ps1 -PackageType Portable
"@
}

function Remove-BuildDirectory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    $resolvedRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd("\")
    $resolvedPath = [System.IO.Path]::GetFullPath($Path).TrimEnd("\")
    if (-not $resolvedPath.StartsWith("$resolvedRoot\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove a directory outside the project: $resolvedPath"
    }

    Remove-Item -LiteralPath $resolvedPath -Recurse -Force
}

if ($env:OS -ne "Windows_NT") {
    throw @"
Windows EXE packages must be built on Windows.
Use the repository's 'Build Windows EXE' GitHub Actions workflow, or run this script on Windows 10/11.
"@
}

if ($AppVersion -notmatch "^[0-9]+(\.[0-9]+){1,3}$") {
    throw "AppVersion must contain 2 to 4 numeric components, for example 1.0.0."
}

$JdkBin = Resolve-JdkBin
$JavaExe = Join-Path $JdkBin "java.exe"
$JpackageExe = Join-Path $JdkBin "jpackage.exe"
$MavenExe = Resolve-Maven
$env:PATH = "$JdkBin;$env:PATH"

if ($PackageType -in @("Installer", "All")) {
    Enable-WixToolset
}

Write-Host "==> Toolchain" -ForegroundColor Cyan
Write-Host "Java:    $JavaExe"
Write-Host "Maven:   $MavenExe"
Write-Host "jpackage: $JpackageExe"
& $JavaExe -version
if ($LASTEXITCODE -ne 0) {
    throw "java.exe failed with exit code $LASTEXITCODE."
}
& $JpackageExe --version
if ($LASTEXITCODE -ne 0) {
    throw "jpackage.exe failed with exit code $LASTEXITCODE."
}

$runningApp = Get-Process -Name @($AppName, $WebLauncherName) -ErrorAction SilentlyContinue
if ($runningApp) {
    throw "propel.exe or propel-web.exe is running. Close it before packaging, then run this script again."
}

Write-Host "==> Cleaning packaging output" -ForegroundColor Cyan
Remove-BuildDirectory $ReleaseRoot
Remove-BuildDirectory $StageDir
if (Test-Path -LiteralPath $TempRoot) {
    Remove-Item -LiteralPath $TempRoot -Recurse -Force
}

$mavenArguments = @("-B", "package")
if ($SkipTests) {
    $mavenArguments += "-DskipTests"
}

Write-Host "==> Building application JAR" -ForegroundColor Cyan
& $MavenExe @mavenArguments
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$JarPath = Join-Path $Root "target\$JarName"
if (-not (Test-Path -LiteralPath $JarPath)) {
    throw "Expected application JAR was not created: $JarPath"
}
if (-not (Test-Path -LiteralPath $SupplyMatrixPath -PathType Leaf)) {
    throw "Expected PICS supply matrix was not found: $SupplyMatrixPath"
}

New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
New-Item -ItemType Directory -Force -Path $ReleaseRoot | Out-Null
Copy-Item -LiteralPath $JarPath -Destination (Join-Path $StageDir $JarName) -Force
$StageFeishuDir = Join-Path $StageDir "feishu"
New-Item -ItemType Directory -Force -Path $StageFeishuDir | Out-Null
Copy-Item -LiteralPath $SupplyMatrixPath `
    -Destination (Join-Path $StageFeishuDir "supply_matrix.xlsx") `
    -Force

New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
$WebLauncherConfig = Join-Path $TempRoot "propel-web.properties"
@(
    "main-jar=$JarName"
    "main-class=com.autoproject.web.WebLauncherMain"
    "description=Open the Propel local web application"
    "win-shortcut=true"
    "win-menu=true"
) | Set-Content -LiteralPath $WebLauncherConfig -Encoding ascii

$jpackageCommon = @(
    "--input", $StageDir,
    "--main-jar", $JarName,
    "--main-class", "com.autoproject.Main",
    "--name", $AppName,
    "--app-version", $AppVersion,
    "--description", "VIOOH CSV merge and proposal Excel export",
    "--vendor", "VIOOH",
    "--copyright", "VIOOH",
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx4g",
    "--java-options", '-Dpropel.supplyMatrixPath=$APPDIR/feishu/supply_matrix.xlsx',
    "--add-launcher", "$WebLauncherName=$WebLauncherConfig",
    "--arguments", "--gui"
)

if ($PackageType -in @("Installer", "All")) {
    $installerTemp = Join-Path $TempRoot "installer"
    New-Item -ItemType Directory -Force -Path $installerTemp | Out-Null
    Write-Host "==> Creating single Windows installer EXE" -ForegroundColor Cyan
    & $JpackageExe @jpackageCommon `
        --type exe `
        --dest $ReleaseRoot `
        --temp $installerTemp `
        --win-per-user-install `
        --win-dir-chooser `
        --win-menu `
        --win-menu-group $AppName `
        --win-shortcut `
        --win-upgrade-uuid $UpgradeUuid
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $installer = Join-Path $ReleaseRoot "$AppName-$AppVersion.exe"
    if (-not (Test-Path -LiteralPath $installer)) {
        $installer = Get-ChildItem -Path $ReleaseRoot -Filter "*.exe" |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $installer -or -not (Test-Path -LiteralPath $installer)) {
        throw "jpackage completed but no installer EXE was found in $ReleaseRoot."
    }

    Write-Host "Installer ready: $installer" -ForegroundColor Green
}

if ($PackageType -in @("Portable", "All")) {
    $portableDest = Join-Path $ReleaseRoot "portable"
    $portableTemp = Join-Path $TempRoot "portable"
    New-Item -ItemType Directory -Force -Path $portableDest | Out-Null
    New-Item -ItemType Directory -Force -Path $portableTemp | Out-Null

    Write-Host "==> Creating portable folder build" -ForegroundColor Cyan
    & $JpackageExe @jpackageCommon `
        --type app-image `
        --dest $portableDest `
        --temp $portableTemp
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }

    $appImage = Join-Path $portableDest $AppName
    $portableExe = Join-Path $appImage "$AppName.exe"
    $portableWebExe = Join-Path $appImage "$WebLauncherName.exe"
    $portableSupplyMatrix = Join-Path $appImage "app\feishu\supply_matrix.xlsx"
    if (-not (Test-Path -LiteralPath $portableExe)) {
        throw "Portable launcher was not created: $portableExe"
    }
    if (-not (Test-Path -LiteralPath $portableWebExe)) {
        throw "Portable web launcher was not created: $portableWebExe"
    }
    if (-not (Test-Path -LiteralPath $portableSupplyMatrix)) {
        throw "Portable PICS supply matrix was not packaged: $portableSupplyMatrix"
    }

    $zipPath = Join-Path $ReleaseRoot "$AppName-Windows-portable.zip"
    Compress-Archive -Path $appImage -DestinationPath $zipPath -CompressionLevel Optimal
    Write-Host "Portable ZIP ready: $zipPath" -ForegroundColor Green
    Write-Host "One-click web launcher: $portableWebExe" -ForegroundColor Green
}

if (Test-Path -LiteralPath $TempRoot) {
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Build finished. Release files are in:" -ForegroundColor Green
Write-Host "  $ReleaseRoot"
