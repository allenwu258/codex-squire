#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$EnvFile = '.env',
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts/Environment.ps1')
$configuration = Read-SquireEnvironment -Path $EnvFile -ProjectRoot $PSScriptRoot
Assert-SquirePortAvailable $configuration.WebPort 'SQUIRE_WEB_PORT'
Initialize-SquireNode $configuration
Write-Host "Checking backend: $($configuration.BackendUrl)"
Wait-SquireBackend $configuration.BackendUrl
$env:SQUIRE_BACKEND_URL = $configuration.BackendUrl
Push-Location (Join-Path $PSScriptRoot 'frontend')
try {
    if (-not $SkipInstall) {
        & pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    Write-Host "Configuration: $($configuration.File)"
    Write-Host "Web: $($configuration.WebUrl)"
    Write-Host "Backend proxy: $($configuration.BackendUrl)"
    & pnpm dev --port $configuration.WebPort --strictPort
    if ($LASTEXITCODE -ne 0) { throw "Frontend exited with code $LASTEXITCODE" }
} finally { Pop-Location }
