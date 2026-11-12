param([switch]$RefreshBuild)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$appRoot = Join-Path $repo 'smart-companion-platform'
$run = 'D:\CodexEnvs\EducationAgent\smart-companion\runtime\official-system'
$node = 'D:\DevTools\node-v24.19.0-win-x64\node.exe'
$dependencies = 'D:\CodexEnvs\EducationAgent\frontend\node_modules'
if (-not (Test-Path -LiteralPath "$appRoot\node_modules")) {
    if (-not (Test-Path -LiteralPath "$dependencies\vite\bin\vite.js")) { throw 'Existing D: frontend dependencies were not found.' }
    New-Item -ItemType Junction -Path "$appRoot\node_modules" -Target $dependencies | Out-Null
}
& "$PSScriptRoot\start-system.ps1" -PlatformServices -RefreshBuild:$RefreshBuild
if (-not (Test-Path -LiteralPath "$run\processes.json")) { throw 'Analysis service process state is missing.' }
$managed = @(Get-Content "$run\processes.json" -Raw | ConvertFrom-Json)
function Matches($entry) {
    $process = Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
    if (-not $process) { return $false }
    try {
        $started = $process.StartTime
        return ($null -ne $started -and $started.ToUniversalTime().Ticks -eq ([datetime]$entry.StartTimeUtc).ToUniversalTime().Ticks -and $process.Path -eq $entry.Path)
    } catch { return $false }
}
$running = @($managed | Where-Object { $_.Name -eq 'analysis-platform' -and (Matches $_) })
if (-not $running.Count) {
    if (Get-NetTCPConnection -State Listen -LocalPort 18090 -ErrorAction SilentlyContinue) { throw 'Port 18090 is occupied by another service.' }
    $env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:18080'
    $env:VITE_COMPANION_WORKSPACE = '3'
    $process = Start-Process $node -ArgumentList @("$appRoot\node_modules\vite\bin\vite.js",'--host','127.0.0.1','--port','18090','--strictPort') -WorkingDirectory $appRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run\logs\platform.out.log" -RedirectStandardError "$run\logs\platform.err.log"
    Start-Sleep -Milliseconds 200
    $identity = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    $entry = [pscustomobject]@{Name='analysis-platform';Id=$process.Id;StartTimeUtc=$process.StartTime.ToUniversalTime().ToString('o');Path=$identity.ExecutablePath}
    $managed = @($managed | Where-Object { $_.Name -ne 'analysis-platform' -or (Matches $_) }) + $entry
    ConvertTo-Json -InputObject @($managed) -Depth 5 | Set-Content "$run\processes.json" -Encoding utf8
}
$ready = $false
for ($attempt = 0; $attempt -lt 40; $attempt++) {
    try {
        $response = Invoke-WebRequest 'http://127.0.0.1:18090/' -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $ready = $true; break }
    } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $ready) { throw 'Independent platform did not become ready. Inspect D: platform logs.' }
Write-Host 'Independent analysis platform ready: http://127.0.0.1:18090'
