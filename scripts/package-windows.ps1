# Builds a Windows folder with propel.exe + bundled Java (no separate JRE install on target PCs).
# Requires: JDK 21 on this machine, with jpackage on PATH.
#
# Output: dist\propel\propel.exe  (NOT under target\ — avoids nested app\dist\propel loops)
# Zip dist\propel and send to colleagues.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

$AppName = "propel"
$AppVersion = "1.0.0"
$DistRoot = Join-Path $Root "dist"
$AppDir = Join-Path $DistRoot $AppName
$JarName = "Auto_project-1.0-SNAPSHOT.jar"
$StageDir = Join-Path $Root "target\jpackage-input"
$JpackageTemp = Join-Path $env:TEMP "propel-jpackage"
# Legacy bad output from older scripts — remove so deletes do not fight nested trees
$LegacyTargetDist = Join-Path $Root "target\dist"

function Resolve-JdkBin {
    $javaHome = $env:JAVA_HOME
    if ($javaHome) {
        $bin = Join-Path $javaHome "bin"
        if ((Test-Path (Join-Path $bin "java.exe")) -and (Test-Path (Join-Path $bin "jpackage.exe"))) {
            return $bin
        }
    }
    $jpackageCmd = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($jpackageCmd) {
        return (Split-Path $jpackageCmd.Source -Parent)
    }
    throw @"
jpackage not found. Install JDK 21 (not JRE), set JAVA_HOME to it, then retry.
Example: `$env:JAVA_HOME = 'D:\Java_jdk'
"@
}

function Remove-TreeForce([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $full = (Get-Item -LiteralPath $Path).FullName
    Write-Host "Removing: $full"
    $long = if ($full.StartsWith("\\?\")) { $full } else { "\\?\$full" }
    cmd /c "rd /s /q `"$long`"" | Out-Null
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue
    }
}

Get-Process -Name propel -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

$JdkBin = Resolve-JdkBin
$JavaExe = Join-Path $JdkBin "java.exe"
$JpackageExe = Join-Path $JdkBin "jpackage.exe"
$env:PATH = "$JdkBin;$env:PATH"

Write-Host "Checking JDK / jpackage..."
Write-Host "  JAVA_HOME=$env:JAVA_HOME"
Write-Host "  using: $JdkBin"
& $JavaExe -version
if ($LASTEXITCODE -ne 0) {
    throw "java failed (exit $LASTEXITCODE). Fix JAVA_HOME — do not use Oracle Java 23 shim on PATH."
}
& $JpackageExe --version
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed (exit $LASTEXITCODE)."
}

Write-Host "Cleaning old package output..."
Remove-TreeForce $LegacyTargetDist
Remove-TreeForce $DistRoot
Remove-TreeForce $StageDir
Remove-TreeForce $JpackageTemp

Write-Host "Building fat jar..."
& mvn -q package -DskipTests
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jar = Join-Path $Root "target\$JarName"
if (-not (Test-Path $jar)) {
    throw "Missing $jar — run 'mvn package' and check for errors."
}

New-Item -ItemType Directory -Force -Path $StageDir | Out-Null
Copy-Item $jar (Join-Path $StageDir $JarName) -Force
New-Item -ItemType Directory -Force -Path $DistRoot | Out-Null
New-Item -ItemType Directory -Force -Path $JpackageTemp | Out-Null

Write-Host "Running jpackage (may take 1–3 minutes)..."
Write-Host "  input: $StageDir  (jar only)"
Write-Host "  dest:  $DistRoot"
Write-Host "  temp:  $JpackageTemp"
& $JpackageExe `
    --input $StageDir `
    --main-jar $JarName `
    --main-class com.autoproject.Main `
    --name $AppName `
    --app-version $AppVersion `
    --description "CSV merge and proposal Excel export" `
    --vendor "AutoProject" `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "-Xmx4g" `
    --arguments "--gui" `
    --temp $JpackageTemp `
    --dest $DistRoot `
    --type app-image

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$exe = Join-Path $AppDir "$AppName.exe"
if (-not (Test-Path $exe)) {
    throw "Expected $exe was not created."
}

Write-Host ""
Write-Host "Done."
Write-Host "  Run locally:  $exe"
Write-Host "  Distribute:   zip this entire folder:"
Write-Host "                $AppDir"
Write-Host ""
Write-Host "Recipients do NOT need Java installed. Windows 10/11 x64 only."
