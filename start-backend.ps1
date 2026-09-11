#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$EnvFile = '.env',
    [switch]$SkipBuild,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/Environment.ps1')
$configuration = Read-SquireEnvironment -Path $EnvFile -ProjectRoot $PSScriptRoot
Assert-SquirePortAvailable $configuration.BackendPort 'SQUIRE_PORT'
$java = Initialize-SquireJava $configuration
$env:SQUIRE_PORT = "$($configuration.BackendPort)"
$env:SQUIRE_DATA_DIR = $configuration.DataDirectory
$env:SQUIRE_ALLOWED_ORIGINS = $configuration.AllowedOrigins
$env:SQUIRE_UPSTREAM_IDLE_SECONDS = "$($configuration.IdleSeconds)"
$env:SQUIRE_UPSTREAM_TOTAL_SECONDS = "$($configuration.TotalSeconds)"
$backend = Join-Path $PSScriptRoot 'backend'
Push-Location $backend
try {
    if (-not $SkipBuild) {
        $gradleArgs = @('--console=plain', 'bootJar')
        if ($Offline) { $gradleArgs += '--offline' }
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $backend 'build/libs') -Filter 'codex-squire-*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object Name -NotLike '*-plain.jar')
    if ($jars.Count -ne 1) { throw 'Expected one executable JAR in backend/build/libs. Run Gradle clean bootJar, then retry.' }
    Write-Host "Configuration: $($configuration.File)"
    Write-Host "Backend: $($configuration.BackendUrl)"
    Write-Host "Data directory: $($configuration.DataDirectory)"
    Write-Host "Admin token file: $(Join-Path $configuration.DataDirectory 'admin.token')"
    Write-Host 'Start start-web.ps1 with the same environment file in another terminal.'
    & $java '-Dfile.encoding=UTF-8' -jar $jars[0].FullName
    if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
} finally { Pop-Location }
