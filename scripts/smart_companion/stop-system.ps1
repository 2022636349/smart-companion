param([ValidateSet('ai')][string]$Service)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$run='D:\CodexEnvs\EducationAgent\smart-companion\runtime\official-system'
$path="$run\processes.json"
if (-not (Test-Path $path)) { Write-Host 'No managed system processes.'; return }
function Identity($p) { [pscustomobject]@{Id=$p.Id;StartTimeUtc=$p.StartTime.ToUniversalTime().ToString('o');Path=$p.Path} }
function Matches($entry) {
    $p=Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
    if (-not $p) { return $false }
    try {
        $started=$p.StartTime
        return ($null -ne $started -and $started.ToUniversalTime().Ticks -eq ([datetime]$entry.StartTimeUtc).ToUniversalTime().Ticks -and $p.Path -eq $entry.Path)
    } catch { return $false }
}
function Children([int]$parentId) {
    foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$parentId")) {
        Children ([int]$child.ProcessId)
        $p=Get-Process -Id $child.ProcessId -ErrorAction SilentlyContinue
        if ($p) { Identity $p }
    }
}
$entries=@(Get-Content $path -Raw | ConvertFrom-Json)
if ($Service) { $entries=@($entries | Where-Object { $_.Name -eq $Service }) }
[array]::Reverse($entries)
foreach ($entry in $entries) {
    if (-not (Matches $entry)) { continue }
    if ($entry.Name -eq 'mysql') {
        $admin='D:\DevTools\mysql-8.4.10-winx64\bin\mysqladmin.exe'
        & $admin "--defaults-extra-file=$run\secrets\mysql.cnf" '--protocol=TCP' '--host=127.0.0.1' '--port=33416' '--user=root' '--ssl-mode=REQUIRED' shutdown
        if ($LASTEXITCODE -ne 0) { throw 'Graceful isolated MySQL shutdown failed; leaving it intact.' }
        $mysqlProcess=Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
        if ($mysqlProcess) { $mysqlProcess.WaitForExit(30000) | Out-Null }
        continue
    }
    foreach ($child in @(Children $entry.Id)) { if (Matches $child) { Stop-Process -Id $child.Id -Force } }
    if (Matches $entry) { Stop-Process -Id $entry.Id -Force }
}
if ($Service) { Write-Host "Stopped only the managed isolated $Service service." }
else { Write-Host 'Stopped only the managed isolated system. D: database, reports and logs retained.' }

