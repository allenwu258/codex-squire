Set-StrictMode -Version Latest

function Read-SquireEnvironment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ProjectRoot
    )
    $root = [IO.Path]::GetFullPath($ProjectRoot)
    $file = [IO.Path]::GetFullPath($Path, $root)
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Configuration file missing: $file. Copy .env.example to .env and configure JAVA_HOME, ports and SQUIRE_DATA_DIR."
    }
    $required = @('JAVA_HOME', 'SQUIRE_PORT', 'SQUIRE_WEB_PORT', 'SQUIRE_DATA_DIR',
        'SQUIRE_UPSTREAM_IDLE_SECONDS', 'SQUIRE_UPSTREAM_TOTAL_SECONDS')
    $allowed = $required + @('SQUIRE_NODE_HOME')
    $values = [Collections.Generic.Dictionary[string, string]]::new([StringComparer]::Ordinal)
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($file)) {
        $lineNumber++
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text -notmatch '^([A-Z][A-Z0-9_]*)\s*=(.*)$') {
            throw "Invalid configuration syntax at line $lineNumber in $file. Expected KEY=value."
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($key -cnotin $allowed) { throw "Unknown configuration key $key at line $lineNumber." }
        if ($values.ContainsKey($key)) { throw "Duplicate configuration key $key at line $lineNumber." }
        if ($value.StartsWith('"') -or $value.StartsWith("'")) {
            if ($value.Length -lt 2 -or $value[-1] -ne $value[0]) {
                throw "Unclosed quoted value for $key at line $lineNumber."
            }
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values.Add($key, $value)
    }
    foreach ($key in $required) {
        if (-not $values.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($values[$key])) {
            throw "Configure $key in $file before starting. See .env.example."
        }
    }
    $backendPort = ConvertTo-SquireInteger $values['SQUIRE_PORT'] 'SQUIRE_PORT' 1024 65535
    $webPort = ConvertTo-SquireInteger $values['SQUIRE_WEB_PORT'] 'SQUIRE_WEB_PORT' 1024 65535
    if ($backendPort -eq $webPort) { throw 'SQUIRE_PORT and SQUIRE_WEB_PORT must be different.' }
    $idleSeconds = ConvertTo-SquireInteger $values['SQUIRE_UPSTREAM_IDLE_SECONDS'] 'SQUIRE_UPSTREAM_IDLE_SECONDS' 1 86400
    $totalSeconds = ConvertTo-SquireInteger $values['SQUIRE_UPSTREAM_TOTAL_SECONDS'] 'SQUIRE_UPSTREAM_TOTAL_SECONDS' 1 86400
    if ($totalSeconds -lt $idleSeconds) { throw 'SQUIRE_UPSTREAM_TOTAL_SECONDS must be at least SQUIRE_UPSTREAM_IDLE_SECONDS.' }
    if (-not [IO.Path]::IsPathFullyQualified($values['JAVA_HOME'])) { throw 'JAVA_HOME must be an absolute JDK directory.' }
    $nodeHome = if ($values.ContainsKey('SQUIRE_NODE_HOME')) { $values['SQUIRE_NODE_HOME'] } else { '' }
    if ($nodeHome -and -not [IO.Path]::IsPathFullyQualified($nodeHome)) { throw 'SQUIRE_NODE_HOME must be an absolute directory or empty.' }
    return [pscustomobject]@{
        File = $file
        JavaHome = [IO.Path]::GetFullPath($values['JAVA_HOME'])
        NodeHome = $nodeHome
        BackendPort = $backendPort
        WebPort = $webPort
        DataDirectory = [IO.Path]::GetFullPath($values['SQUIRE_DATA_DIR'], $root)
        BackendUrl = "http://127.0.0.1:$backendPort"
        WebUrl = "http://127.0.0.1:$webPort"
        AllowedOrigins = "http://127.0.0.1:$webPort,http://localhost:$webPort"
        IdleSeconds = $idleSeconds
        TotalSeconds = $totalSeconds
    }
}

function ConvertTo-SquireInteger {
    param([string]$Value, [string]$Name, [int]$Minimum, [int]$Maximum)
    $number = 0
    if ($Value -notmatch '^\d+$' -or -not [int]::TryParse($Value, [ref]$number) -or $number -lt $Minimum -or $number -gt $Maximum) {
        throw "Configure $Name as an integer in $Minimum..$Maximum."
    }
    return $number
}

function Assert-SquirePortAvailable {
    param([int]$Port, [string]$Name)
    $listeners = [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if (@($listeners | Where-Object Port -eq $Port).Count -gt 0) {
        throw "Port $Port is in use. Stop its owner or update $Name in the shared environment file, then restart both services."
    }
}

function Initialize-SquireJava {
    param([Parameter(Mandatory)]$Configuration)
    $java = Join-Path $Configuration.JavaHome 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'JAVA_HOME must point to an installed JDK 17 containing bin/java.exe.' }
    $versionText = (& $java -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch 'version "17\.') { throw 'JAVA_HOME must select Java 17.' }
    $env:JAVA_HOME = $Configuration.JavaHome
    $env:PATH = "$($Configuration.JavaHome)\bin;$env:PATH"
    return $java
}

function Initialize-SquireNode {
    param([Parameter(Mandatory)]$Configuration)
    if ($Configuration.NodeHome) {
        if (-not (Test-Path -LiteralPath (Join-Path $Configuration.NodeHome 'node.exe') -PathType Leaf)) {
            throw 'SQUIRE_NODE_HOME must contain node.exe, or be empty to use PATH.'
        }
        $env:PATH = "$($Configuration.NodeHome);$env:PATH"
    }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 22.12+ is required. Configure SQUIRE_NODE_HOME or PATH.' }
    $nodeVersion = (& node --version | Out-String).Trim().TrimStart('v')
    if ($LASTEXITCODE -ne 0 -or [version]$nodeVersion -lt [version]'22.12.0') { throw 'Node.js 22.12+ is required.' }
    if (-not (Get-Command pnpm -ErrorAction SilentlyContinue)) { throw 'Install pnpm 12.3.4 and make it available in SQUIRE_NODE_HOME or PATH.' }
    $pnpmVersion = (& pnpm --version | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [version]$pnpmVersion -lt [version]'11.0.0') { throw 'pnpm 11+ is required; the project pins 12.3.4.' }
}

function Wait-SquireBackend {
    param([string]$Url, [int]$TimeoutSeconds = 15)
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $health = Invoke-RestMethod -Uri "$Url/api/health" -TimeoutSec 2 -ErrorAction Stop
            if ($health.status -eq 'UP' -and $health.version) { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Backend not ready at $Url. Start start-backend.ps1 with the same -EnvFile first; check its startup output."
}
