[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sizes = @(16, 20, 24, 28, 32, 40, 48, 64)
$arguments = @('--dir', 'frontend', 'exec', 'tauri', 'icon',
    '../src-tauri/icons/window-icon.svg', '--output', '../src-tauri/icons/window')
foreach ($size in $sizes) { $arguments += @('--png', "$size") }

Push-Location $root
try {
    & pnpm @arguments
    if ($LASTEXITCODE -ne 0) { throw "Window icon generation failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
}
