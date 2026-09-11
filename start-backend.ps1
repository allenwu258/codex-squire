[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\SDK\Java\17\graalvm-jdk-17.0.9',
    [ValidateRange(1024, 65535)][int]$Port = 8787,
    [string]$DataDir = (Join-Path ([Environment]::GetFolderPath('UserProfile')) '.codex-squire'),
    [string]$AllowedOrigins = 'http://127.0.0.1:5173,http://localhost:5173,http://127.0.0.1:5174,http://localhost:5174',
    [switch]$SkipBuild,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$java = Join-Path $JavaHome 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "JDK not found: $java" }
if (-not ((& $java -version 2>&1 | Out-String) -match 'version "17\.')) { throw 'Java 17 is required.' }
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
$env:SQUIRE_DATA_DIR = [IO.Path]::GetFullPath($DataDir)
$env:SQUIRE_ALLOWED_ORIGINS = $AllowedOrigins
$backend = Join-Path $PSScriptRoot 'backend'
Push-Location $backend
try {
    if (-not $SkipBuild) {
        $gradleArgs = @('--console=plain', 'bootJar')
        if ($Offline) { $gradleArgs += '--offline' }
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $jar = Join-Path $backend 'build\libs\codex-squire-0.1.0.jar'
    if (-not (Test-Path -LiteralPath $jar)) { throw 'Backend JAR missing. Run without -SkipBuild.' }
    while (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        if ($Port -eq 65535) { throw 'No available port.' }
        $Port++
    }
    $env:SQUIRE_PORT = "$Port"
    Write-Host "Backend: http://127.0.0.1:$Port"
    Write-Host "Data directory: $env:SQUIRE_DATA_DIR"
    Write-Host "Admin token file: $(Join-Path $env:SQUIRE_DATA_DIR 'admin.token')"
    Write-Host "Web command: .\start-web.ps1 -BackendUrl http://127.0.0.1:$Port"
    & $java '-Dfile.encoding=UTF-8' -jar $jar
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
} finally { Pop-Location }
