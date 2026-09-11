[CmdletBinding()]
param(
    [ValidateRange(1024, 65535)][int]$Port = 5173,
    [string]$BackendUrl = 'http://127.0.0.1:8787',
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 22.12+ is required.' }
if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { throw 'pnpm 11 or newer is required.' }
$uri = [Uri]$BackendUrl
if (-not $uri.IsAbsoluteUri -or $uri.Scheme -notin @('http', 'https')) { throw 'BackendUrl must be an absolute HTTP(S) URL.' }
Push-Location (Join-Path $PSScriptRoot 'frontend')
try {
    if (-not $SkipInstall) {
        & pnpm install --frozen-lockfile
        if ($LASTEXITCODE -ne 0) { throw 'Frontend dependency installation failed.' }
    }
    while (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        if ($Port -eq 65535) { throw 'No available port.' }
        $Port++
    }
    $env:SQUIRE_BACKEND_URL = $BackendUrl.TrimEnd('/')
    Write-Host "Web: http://127.0.0.1:$Port"
    Write-Host "Backend proxy: $env:SQUIRE_BACKEND_URL"
    if ($Port -notin @(5173, 5174)) {
        Write-Warning "Add http://127.0.0.1:$Port to the backend -AllowedOrigins argument."
    }
    & pnpm dev --port $Port
    if ($LASTEXITCODE -ne 0) { throw "Frontend exited with code $LASTEXITCODE" }
} finally { Pop-Location }
