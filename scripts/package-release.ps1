[CmdletBinding()]
param(
    [string]$Version = '0.2.1',
    [string]$JdkHome = '',
    [string]$OutputDirectory = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-JdkHome {
    param([string]$RequestedHome)

    $candidates = @()
    if ($RequestedHome) { $candidates += $RequestedHome }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $environmentFile = Join-Path $PSScriptRoot '..\.env'
    if (Test-Path -LiteralPath $environmentFile -PathType Leaf) {
        $configuredJava = Get-Content -LiteralPath $environmentFile |
            Where-Object { $_ -match '^\s*JAVA_HOME\s*=' } |
            Select-Object -First 1
        if ($configuredJava -and $configuredJava -match '^\s*JAVA_HOME\s*=\s*(.*)$') {
            $candidates += $Matches[1].Trim().Trim('"').Trim("'")
        }
    }

    $jlinkCommand = Get-Command jlink.exe -ErrorAction SilentlyContinue
    if ($jlinkCommand) {
        $candidates += Split-Path (Split-Path $jlinkCommand.Source -Parent) -Parent
    }

    foreach ($candidate in $candidates) {
        if (-not $candidate) { continue }
        $jdkPath = [IO.Path]::GetFullPath($candidate)
        if ((Test-Path -LiteralPath (Join-Path $jdkPath 'bin/java.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $jdkPath 'bin/jlink.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $jdkPath 'jmods') -PathType Container)) {
            $release = Get-Content -LiteralPath (Join-Path $jdkPath 'release') -Raw
            if ($release -match 'JAVA_VERSION="17\.') { return $jdkPath }
        }
    }

    throw 'A Java 17 JDK with bin/java.exe, bin/jlink.exe, and jmods is required. Pass -JdkHome explicitly.'
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jdk = Resolve-JdkHome -RequestedHome $JdkHome
$env:JAVA_HOME = $jdk
$env:PATH = "$(Join-Path $jdk 'bin');$env:PATH"

$jar = Join-Path $root "backend/build/libs/codex-squire-$Version.jar"
$dist = Join-Path $root 'frontend/dist'
$tauriExe = Join-Path $root 'src-tauri/target/release/codex-squire-desktop.exe'
$outputRoot = if ($OutputDirectory) {
    if ([IO.Path]::IsPathRooted($OutputDirectory)) {
        [IO.Path]::GetFullPath($OutputDirectory)
    } else {
        [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
    }
} else {
    Join-Path $root 'artifacts'
}
$packageName = "Codex-Squire-$Version-windows-x64"
$packageRoot = Join-Path $outputRoot $packageName
$zipPath = Join-Path $outputRoot "$packageName.zip"

Write-Host "Building backend JAR..."
Push-Location $root
try {
    & (Join-Path $root 'backend/gradlew.bat') -p (Join-Path $root 'backend') clean bootJar
    if ($LASTEXITCODE -ne 0) { throw "Backend build failed with exit code $LASTEXITCODE." }

    Write-Host "Building frontend and Tauri release executable..."
    & pnpm --dir frontend run tauri:build -- --no-bundle
    if ($LASTEXITCODE -ne 0) { throw "Tauri release build failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Backend JAR was not produced: $jar" }
if (-not (Test-Path -LiteralPath $dist -PathType Container)) { throw "Frontend dist was not produced: $dist" }
if (-not (Test-Path -LiteralPath $tauriExe -PathType Leaf)) { throw "Tauri executable was not produced: $tauriExe" }

if (Test-Path -LiteralPath $packageRoot) { Remove-Item -LiteralPath $packageRoot -Recurse -Force }
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
$null = New-Item -ItemType Directory -Force -Path (Join-Path $packageRoot 'resources')

Write-Host "Creating bundled Java runtime..."
$runtime = Join-Path $packageRoot 'runtime'
$jlink = Join-Path $jdk 'bin/jlink.exe'
& $jlink `
    --module-path (Join-Path $jdk 'jmods') `
    --add-modules 'java.base,java.sql,java.desktop,java.naming,jdk.crypto.ec' `
    --strip-debug `
    --no-man-pages `
    --no-header-files `
    --compress=2 `
    --output $runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE." }

Copy-Item -LiteralPath $tauriExe -Destination (Join-Path $packageRoot 'codex-squire-desktop.exe')
Copy-Item -LiteralPath $jar -Destination (Join-Path $packageRoot 'resources/backend.jar')
Copy-Item -LiteralPath $dist -Destination (Join-Path $packageRoot 'resources/frontend') -Recurse

$readme = @"
Codex Squire $Version - Windows x64 portable release

Double-click codex-squire-desktop.exe to start the application.
The package includes its own Java 17 runtime, backend JAR, and frontend assets.
No JAVA_HOME, SQUIRE_BACKEND_JAR, or SQUIRE_FRONTEND_DIST environment variable is required.

The first screen lets you choose local mode or trusted-LAN service mode.
Application data is stored in the normal Windows application data directory.
"@
Set-Content -LiteralPath (Join-Path $packageRoot 'README.txt') -Value $readme -Encoding UTF8

Write-Host "Verifying bundled Java runtime..."
$runtimeJava = Join-Path $runtime 'bin/java.exe'
$versionOutput = & cmd.exe /c "`"$runtimeJava`" -version 2>&1"
if ($LASTEXITCODE -ne 0) { throw "Bundled Java runtime verification failed." }
Write-Host ($versionOutput -join [Environment]::NewLine)

$moduleList = & $runtimeJava --list-modules
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect bundled Java runtime modules." }
if (-not ($moduleList -match '^java.naming@')) {
    throw 'Bundled Java runtime is missing the required java.naming module.'
}
if (-not ($moduleList -match '^jdk.crypto.ec@')) {
    throw 'Bundled Java runtime is missing the required jdk.crypto.ec module.'
}
Write-Host "Verified required Java modules: java.naming, jdk.crypto.ec"

Write-Host "Creating release ZIP..."
$null = New-Item -ItemType Directory -Force -Path $outputRoot
Compress-Archive -LiteralPath $packageRoot -DestinationPath $zipPath -CompressionLevel Optimal

Write-Host "Release package created: $zipPath"
Write-Host "Package directory: $packageRoot"
