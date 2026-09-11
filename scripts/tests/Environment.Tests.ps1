#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $projectRoot 'scripts/Environment.ps1')
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('squire-environment-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporary | Out-Null
$configFile = Join-Path $temporary 'fixture.env'
$javaFixture = Join-Path $temporary 'JDK 17'
$valid = @"
# Configuration with spaces, quotes and Windows path separators.
JAVA_HOME="$javaFixture"
SQUIRE_NODE_HOME=
SQUIRE_PORT=18787
SQUIRE_WEB_PORT=15173
SQUIRE_DATA_DIR='state with spaces=a'
SQUIRE_UPSTREAM_IDLE_SECONDS=30
SQUIRE_UPSTREAM_TOTAL_SECONDS=60
"@
$passed = 0

function Assert-Equal($Actual, $Expected, [string]$Name) {
    if ($Actual -ne $Expected) { throw "Failed: $Name" }
}

function Assert-Rejected([string]$Content, [string]$Message) {
    [IO.File]::WriteAllText($configFile, $Content)
    $caught = $null
    try { Read-SquireEnvironment -Path $configFile -ProjectRoot $temporary | Out-Null } catch { $caught = $_ }
    if ($null -eq $caught -or $caught.Exception.Message -notlike "*$Message*") { throw "Expected configuration rejection: $Message" }
}

try {
    [IO.File]::WriteAllText($configFile, $valid)
    Push-Location ([IO.Path]::GetTempPath())
    try { $config = Read-SquireEnvironment -Path 'fixture.env' -ProjectRoot $temporary } finally { Pop-Location }
    Assert-Equal $config.JavaHome $javaFixture 'quoted JDK path'
    Assert-Equal $config.DataDirectory (Join-Path $temporary 'state with spaces=a') 'relative data path and literal equals sign'
    Assert-Equal $config.BackendUrl 'http://127.0.0.1:18787' 'derived backend URL'
    Assert-Equal $config.AllowedOrigins 'http://127.0.0.1:15173,http://localhost:15173' 'derived origins'
    $passed++
    $missing = $null
    try { Read-SquireEnvironment -Path 'missing.env' -ProjectRoot $temporary | Out-Null } catch { $missing = $_ }
    if ($null -eq $missing -or $missing.Exception.Message -notlike '*Copy .env.example*') { throw 'Missing file must explain setup.' }
    $passed++
    Assert-Rejected ($valid.Replace("JAVA_HOME=`"$javaFixture`"", 'JAVA_HOME=')) 'Configure JAVA_HOME'
    $passed++
    Assert-Rejected ($valid + "`nSQUIRE_PORT=18888") 'Duplicate configuration key'
    $passed++
    Assert-Rejected ($valid + "`nSQUIRE_PORRT=18888") 'Unknown configuration key'
    $passed++
    Assert-Rejected ($valid.Replace('18787', '70000')) 'SQUIRE_PORT as an integer'
    $passed++
    Assert-Rejected ($valid.Replace('15173', '18787')) 'must be different'
    $passed++
    Assert-Rejected ($valid.Replace('SQUIRE_UPSTREAM_TOTAL_SECONDS=60', 'SQUIRE_UPSTREAM_TOTAL_SECONDS=10')) 'must be at least'
    $passed++
    Assert-Rejected ($valid + "`nnot an assignment") 'Invalid configuration syntax'
    $passed++
    Assert-Rejected ($valid.Replace("JAVA_HOME=`"$javaFixture`"", 'JAVA_HOME="unclosed')) 'Unclosed quoted value'
    $passed++
    Assert-Rejected ($valid.Replace("JAVA_HOME=`"$javaFixture`"", 'JAVA_HOME=relative-jdk')) 'absolute JDK directory'
    $passed++
    Assert-Rejected ($valid.Replace('SQUIRE_PORT=18787', 'SQUIRE_PORT=$(throw "must not execute")')) 'SQUIRE_PORT as an integer'
    $passed++
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        $portError = $null
        try { Assert-SquirePortAvailable $listener.LocalEndpoint.Port 'SQUIRE_PORT' } catch { $portError = $_ }
        if ($null -eq $portError -or $portError.Exception.Message -notlike '*is in use*') { throw 'Occupied port was not rejected.' }
        $passed++
    } finally { $listener.Stop() }
    $shell = (Get-Process -Id $PID).Path
    foreach ($script in @('start-backend.ps1', 'start-web.ps1')) {
        $output = & $shell -NoProfile -File (Join-Path $projectRoot $script) -EnvFile (Join-Path $temporary 'missing.env') 2>&1 | Out-String
        if ($LASTEXITCODE -eq 0 -or $output -notlike '*Configuration file missing*') { throw "Startup must reject missing configuration: $script" }
        $passed++
    }
    Write-Output "$passed environment and startup checks passed."
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'squire-environment-*') {
        throw 'Refusing to remove an unexpected test directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
