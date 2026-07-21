# Builds a self-contained Windows application folder (includes bundled JRE).
# Testers unzip the output zip and double-click propel.exe — no Java/Maven install.
#
# Requirements on YOUR machine (build only):
#   - JDK 21+ on PATH (same major version as pom.xml compiler level)
#   - Maven on PATH
#
# Optional for .msi / setup-style installer instead of a zip folder:
#   - WiX Toolset 3.14+ (https://wixtoolset.org/) so jpackage can use --type msi

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$artifactId = "Auto_project"
$version = "1.0-SNAPSHOT"
# shade plugin replaces the main artifact; final file is *.jar, not *-shaded.jar
$fatJar = "$artifactId-$version.jar"
$appName = "propel"
$distDir = Join-Path $root "dist"
$stageDir = Join-Path $root "target\jpackage-input"

Write-Host "==> Maven clean package (fat JAR, forces fresh compile)..." -ForegroundColor Cyan
mvn -B -DskipTests clean package
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jarPath = Join-Path $root "target\$fatJar"
if (-not (Test-Path $jarPath)) {
    Write-Error "Expected fat JAR not found: target\$fatJar"
}

$jdkBin = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
    $jdkBin = Join-Path $env:JAVA_HOME "bin"
} else {
    $jpackageCmd = Get-Command jpackage -ErrorAction SilentlyContinue
    if ($jpackageCmd) {
        $jdkBin = Split-Path $jpackageCmd.Source -Parent
    } else {
        Write-Error "jpackage not found. Install JDK 21, set JAVA_HOME, then retry."
    }
}
$jpackageExe = Join-Path $jdkBin "jpackage.exe"
$env:PATH = "$jdkBin;$env:PATH"

New-Item -ItemType Directory -Force -Path $stageDir | Out-Null
Copy-Item $jarPath (Join-Path $stageDir $fatJar) -Force

try {
    if (Test-Path $distDir) {
        Remove-Item -Recurse -Force $distDir
    }
} catch {
    Write-Error "Cannot delete dist folder (is propel.exe still running?). Close the app and retry. $_"
    exit 1
}
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

$jpackageCommon = @(
    "--name", $appName,
    "--input", $stageDir,
    "--main-jar", $fatJar,
    "--main-class", "com.autoproject.Main",
    "--app-version", "1.0.0",
    "--description", "CSV merge / Excel export tool",
    "--vendor", "Auto_project",
    "--copyright", "Auto_project",
    "--dest", $distDir,
    "--java-options", "-Dfile.encoding=UTF-8",
    "--java-options", "-Xmx4g",
    "--arguments", "--gui"
)

Write-Host "==> jpackage: portable app (folder + exe, no WiX required)..." -ForegroundColor Cyan
$appImageDir = Join-Path $distDir $appName
if (Test-Path $appImageDir) {
    Remove-Item -Recurse -Force $appImageDir
}
& $jpackageExe @jpackageCommon --type app-image
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$zipPath = Join-Path $distDir "$appName-Windows-portable.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Write-Host "==> Zipping portable build..." -ForegroundColor Cyan
Compress-Archive -Path $appImageDir -DestinationPath $zipPath

$exePath = Join-Path $appImageDir "$appName.exe"
$jarInImage = Join-Path $appImageDir "app\$fatJar"
Write-Host ""
Write-Host "Build stamp (verify you opened THIS copy):" -ForegroundColor Cyan
if (Test-Path $jarInImage) { Write-Host "  JAR: $(Get-Item $jarInImage | Select-Object -ExpandProperty LastWriteTime)  $jarInImage" }
if (Test-Path $exePath) { Write-Host "  EXE: $(Get-Item $exePath | Select-Object -ExpandProperty LastWriteTime)  $exePath" }
Write-Host ""
Write-Host "Done. Give testers this file:" -ForegroundColor Green
Write-Host "  $zipPath"
Write-Host "They unzip once, then run $appName.exe inside the folder."
Write-Host ""
Write-Host "Optional MSI (needs WiX on PATH):" -ForegroundColor Yellow
Write-Host "  jpackage $($jpackageCommon -join ' ') --type msi"
