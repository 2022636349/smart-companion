param([switch]$DatabaseOnly, [switch]$RefreshBuild, [switch]$AnalysisOnly, [switch]$PlatformServices)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$run = 'D:\CodexEnvs\EducationAgent\smart-companion\runtime\official-system'
$mysqlHome = 'D:\DevTools\mysql-8.4.10-winx64'
$javaHome = 'D:\DevTools\EducationAgent\jdk-portable'
$maven = 'D:\DevTools\EducationAgent\maven-portable\bin\mvn.cmd'
$node = 'D:\DevTools\node-v24.19.0-win-x64\node.exe'
$graphPython = 'D:\CodexEnvs\EducationAgent\knowledge-service\Scripts\python.exe'
$aiPython = 'D:\CodexEnvs\EducationAgent\ai-service\Scripts\python.exe'
if (-not (Test-Path D:\) -or (Get-PSDrive D).Free -lt 5GB) { throw 'D: requires at least 5 GiB free.' }
foreach ($path in @("$mysqlHome\bin\mysqld.exe", "$javaHome\bin\java.exe", $maven, $node, $graphPython, $aiPython)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing existing tool: $path" }
}
New-Item -ItemType Directory -Force -Path $run,"$run\logs","$run\tmp","$run\files","$run\secrets",'D:\PackageCache\maven','D:\PackageCache\npm' | Out-Null
$env:TEMP = "$run\tmp"; $env:TMP = $env:TEMP
$env:JAVA_HOME = $javaHome; $env:MAVEN_USER_HOME = 'D:\PackageCache\maven-home'
$env:MAVEN_OPTS = "-Duser.home=D:\PackageCache\maven-home -Djava.io.tmpdir=$run\tmp"
$env:JAVA_TOOL_OPTIONS = "-Duser.home=D:\PackageCache\maven-home -Djava.io.tmpdir=$run\tmp"
$env:npm_config_cache = 'D:\PackageCache\npm'
$env:PYTHONDONTWRITEBYTECODE = '1'
$env:PYTHONUTF8 = '1'
$env:PIP_CACHE_DIR = 'D:\PackageCache\pip'
$env:HF_HOME = 'D:\PackageCache\huggingface'
$statePath = "$run\processes.json"
$managed = [System.Collections.Generic.List[object]]::new()
if (Test-Path $statePath) { foreach ($entry in @(Get-Content $statePath -Raw | ConvertFrom-Json)) { $managed.Add($entry) } }
function Save-State { ConvertTo-Json -InputObject @($managed.ToArray()) -Depth 5 | Set-Content $statePath -Encoding utf8 }
function Is-Managed($entry) {
    $p = Get-Process -Id $entry.Id -ErrorAction SilentlyContinue
    if (-not $p) { return $false }
    try {
        $started = $p.StartTime
        return ($null -ne $started -and $started.ToUniversalTime().Ticks -eq ([datetime]$entry.StartTimeUtc).ToUniversalTime().Ticks -and $p.Path -eq $entry.Path)
    } catch { return $false }
}
function Port-Open([int]$port) {
    $c = [Net.Sockets.TcpClient]::new()
    try { return $c.ConnectAsync('127.0.0.1',$port).Wait(500) -and $c.Connected } catch { return $false } finally { $c.Dispose() }
}
function Start-Managed($name,$exe,$arguments,$working,$port) {
    $old = @($managed | Where-Object { $_.Name -eq $name -and (Is-Managed $_) })
    if ($old.Count -gt 0) { return Get-Process -Id $old[-1].Id }
    if (Port-Open $port) { throw "Port $port is occupied by an unmanaged service; refusing to replace it." }
    $p = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $working -WindowStyle Hidden -PassThru -RedirectStandardOutput "$run\logs\$name.out.log" -RedirectStandardError "$run\logs\$name.err.log"
    Start-Sleep -Milliseconds 150
    $actual=Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)"
    $managed.Add([pscustomobject]@{Name=$name;Id=$p.Id;StartTimeUtc=$p.StartTime.ToUniversalTime().ToString('o');Path=$actual.ExecutablePath})
    Save-State
    return $p
}
function Wait-Port($process,$port) {
    $until = [DateTime]::UtcNow.AddSeconds(300)
    while ([DateTime]::UtcNow -lt $until) {
        if ($process.HasExited) { throw "Service exited; inspect $run\logs" }
        if (Port-Open $port) { return }
        Start-Sleep -Milliseconds 500
    }
    throw "Service port $port did not become ready."
}
function Wait-Health($process, [string]$uri) {
    $until = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $until) {
        if ($process.HasExited) { throw "Service exited before becoming healthy; inspect $run\logs" }
        try {
            $health = Invoke-RestMethod -Uri $uri -TimeoutSec 3
            if ($health.status -eq 'UP' -or $health.status -eq 'ok') { return }
        } catch { }
        Start-Sleep -Milliseconds 500
    }
    throw "Service health check failed: $uri"
}
function Sql([string]$query,[switch]$Initial) {
    $psi = [Diagnostics.ProcessStartInfo]::new("$mysqlHome\bin\mysql.exe")
    $psi.UseShellExecute=$false; $psi.CreateNoWindow=$true; $psi.RedirectStandardInput=$true; $psi.RedirectStandardOutput=$true; $psi.RedirectStandardError=$true
    if (-not $Initial) { $psi.ArgumentList.Add("--defaults-extra-file=$run\secrets\mysql.cnf") } else { $psi.ArgumentList.Add('--no-defaults') }
    foreach ($arg in @('--protocol=TCP','--host=127.0.0.1','--port=33416','--user=root','--ssl-mode=REQUIRED','--batch','--skip-column-names')) { $psi.ArgumentList.Add($arg) }
    $p=[Diagnostics.Process]::Start($psi); $p.StandardInput.WriteLine($query); $p.StandardInput.Close()
    $output=$p.StandardOutput.ReadToEnd(); $err=$p.StandardError.ReadToEnd(); $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw 'Isolated database operation failed (SQL and credentials withheld).' }
    return $output
}
$secretPath="$run\secrets\runtime.json"
if (-not (Test-Path $secretPath)) {
    @{mysqlPassword=[Guid]::NewGuid().ToString('N');jwtSecret=([Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N'));internalToken=[Guid]::NewGuid().ToString('N')} | ConvertTo-Json | Set-Content $secretPath -Encoding utf8
}
$secret=Get-Content $secretPath -Raw | ConvertFrom-Json
if (-not (Test-Path "$run\mysql-data\mysql")) {
    New-Item -ItemType Directory -Force "$run\mysql-data" | Out-Null
    $init=Start-Process "$mysqlHome\bin\mysqld.exe" -ArgumentList @('--no-defaults','--initialize-insecure',"--basedir=$mysqlHome","--datadir=$run\mysql-data") -WorkingDirectory $run -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput "$run\logs\mysql-init.out.log" -RedirectStandardError "$run\logs\mysql-init.err.log"
    if ($init.ExitCode -ne 0) { throw 'Database initialization failed; inspect D: logs.' }
}
$db=Start-Managed 'mysql' "$mysqlHome\bin\mysqld.exe" @('--no-defaults',"--basedir=$mysqlHome","--datadir=$run\mysql-data",'--port=33416','--bind-address=127.0.0.1','--mysqlx=OFF',"--log-error=$run\logs\mysql-error.log",'--character-set-server=utf8mb4','--collation-server=utf8mb4_0900_ai_ci') $run 33416
Wait-Port $db 33416
if (-not (Test-Path "$run\secrets\mysql.cnf")) {
    Sql "ALTER USER 'root'@'localhost' IDENTIFIED BY '$($secret.mysqlPassword)';" -Initial | Out-Null
    "[client]`npassword=$($secret.mysqlPassword)" | Set-Content "$run\secrets\mysql.cnf" -Encoding ascii
}
Sql 'CREATE DATABASE IF NOT EXISTS education_companion CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' | Out-Null
if ($DatabaseOnly) { Write-Host 'Isolated MySQL ready on 127.0.0.1:33416; data preserved on D:.'; return }
$snapshotFile="$run\backend-snapshot.txt"
$snapshot=if(Test-Path $snapshotFile){(Get-Content $snapshotFile -Raw).Trim()}else{"$run\backend-source"}
if ($RefreshBuild -or -not (Test-Path "$snapshot\pom.xml")) {
    if (@($managed | Where-Object { $_.Name -eq 'spring' -and (Is-Managed $_) }).Count) { throw 'Stop the isolated system before refreshing its build snapshot.' }
    $snapshot="$run\backend-source-$(Get-Date -Format yyyyMMdd-HHmmss)"
    New-Item -ItemType Directory -Force $snapshot | Out-Null
    Copy-Item "$repo\business-service\pom.xml" "$snapshot\pom.xml" -Force
    Copy-Item "$repo\business-service\src" $snapshot -Recurse -Force
    $snapshot | Set-Content $snapshotFile -Encoding utf8
}
$env:SERVER_ADDRESS='127.0.0.1'; $env:SERVER_PORT='18080'
$env:MYSQL_URL='jdbc:mysql://127.0.0.1:33416/education_companion?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:MYSQL_USERNAME='root'; $env:MYSQL_PASSWORD=$secret.mysqlPassword
$env:JWT_SECRET=$secret.jwtSecret; $env:INTERNAL_SERVICE_TOKEN=$secret.internalToken
$env:FLYWAY_ENABLED='true'; $env:DEMO_DATA_ENABLED='true'
$env:FILE_STORAGE_ROOT="$run\files"; $env:MODEL_SETTINGS_KEY_FILE="$run\secrets\model-settings.key"
$env:CORS_ALLOWED_ORIGINS='http://127.0.0.1:18081,http://127.0.0.1:18090'
$env:AI_SERVICE_URL='http://127.0.0.1:18000'; $env:KNOWLEDGE_SERVICE_URL='http://127.0.0.1:18001'
$env:BUSINESS_SERVICE_INTERNAL_BASE_URL='http://127.0.0.1:18080'
$env:MAX_UPLOAD_FILE_SIZE='100MB'; $env:MAX_UPLOAD_REQUEST_SIZE='110MB'
$env:COMPANION_EXPERIENCE_ENABLED='true'
$env:COMPANION_EXPERIENCE_COURSE_ID='3'
if (-not $AnalysisOnly) {
    $env:GRAPH_BACKEND = 'json'
    $env:GRAPH_ALLOW_PENDING = 'false'
    $env:GRAPH_DRAFT_BACKEND = 'file'
    $env:GRAPH_FILE = "$run\graph\knowledge_graph_v2.json"
    $env:GRAPH_INGESTION_ROOT = "$run\graph\releases"
    New-Item -ItemType Directory -Force "$run\graph" | Out-Null
    if (-not (Test-Path -LiteralPath $env:GRAPH_FILE)) {
        Copy-Item -LiteralPath "$repo\knowledge-service\knowledge_graph_v2.json" -Destination $env:GRAPH_FILE
    }
    $graph = Start-Managed 'knowledge' $graphPython @('-m','uvicorn','knowledge_service.main:app','--host','127.0.0.1','--port','18001') "$repo\knowledge-service" 18001
    Wait-Health $graph 'http://127.0.0.1:18001/health'
}
$spring=Start-Managed 'spring' $maven @('-Dmaven.repo.local=D:\PackageCache\maven','spring-boot:run') $snapshot 18080
Wait-Port $spring 18080
$ready=$false
for($attempt=0;$attempt -lt 60;$attempt++) {
    try {
        $login=Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18080/api/auth/login' -ContentType 'application/json' -Body '{"username":"teacher_demo","password":"Teacher@123"}' -TimeoutSec 3
        if($login.data.tokens.accessToken){$ready=$true;break}
    } catch { }
    Start-Sleep -Milliseconds 500
}
if(-not $ready){throw 'Spring demo login did not become ready. Inspect D: logs.'}
if ($AnalysisOnly) {
    Write-Host 'Analysis API ready: http://127.0.0.1:18080 ; persistent data on D:.'
    return
}
. "$repo\scripts\import-ai-environment.ps1"
$aiEnvironmentFile = "$repo\ai-service\.env"
if (Test-Path -LiteralPath $aiEnvironmentFile) {
    Import-AiEnvironment $aiEnvironmentFile | Out-Null
}
# The isolated stack shares one token and all callbacks target its own Spring.
$env:INTERNAL_SERVICE_TOKEN = $secret.internalToken
$env:BUSINESS_SERVICE_INTERNAL_BASE_URL = 'http://127.0.0.1:18080'
$env:KNOWLEDGE_SERVICE_URL = 'http://127.0.0.1:18001'
$env:KNOWLEDGE_SERVICE_BASE_URL = 'http://127.0.0.1:18001'
$env:AI_RUNTIME_SETTINGS_ENABLED = 'true'
$env:CHROMA_PERSIST_DIR = "$run\memory-index"
$ai = Start-Managed 'ai' $aiPython @('-m','uvicorn','app.main:app','--host','127.0.0.1','--port','18000') "$repo\ai-service" 18000
Wait-Health $ai 'http://127.0.0.1:18000/health'
# The independent platform needs graph and AI without starting the original UI.
if (-not $PlatformServices) {
# Verify a real protected student request before announcing readiness.
$studentLogin = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18080/api/auth/login' -ContentType 'application/json' -Body '{"username":"student_demo","password":"Student@123"}' -TimeoutSec 5
$studentHeaders = @{ Authorization = "Bearer $($studentLogin.data.tokens.accessToken)" }
$dashboard = Invoke-RestMethod -Uri 'http://127.0.0.1:18080/api/learning/me/dashboard?courseId=1' -Headers $studentHeaders -TimeoutSec 15
if ($dashboard.code -ne 'OK') { throw 'Student dashboard failed its readiness check.' }
$env:VITE_USE_MOCK='false'; $env:VITE_API_PROXY_TARGET='http://127.0.0.1:18080'
$vite=Start-Managed 'vue' $node @("$repo\book\node_modules\vite\bin\vite.js",'--host','127.0.0.1','--port','18081','--strictPort') "$repo\book" 18081
Wait-Port $vite 18081
}
$modelHeaders = @{
    'X-Internal-Token' = $secret.internalToken
    'X-Request-Id' = "sc-startup-model-$(Get-Date -Format yyyyMMddHHmmss)"
}
$models = Invoke-RestMethod -Uri 'http://127.0.0.1:18000/internal/models/health' -Headers $modelHeaders -TimeoutSec 60
if ($models.generation_provider.status -ne 'UP' -or $models.chat_model.status -ne 'UP') {
    throw 'The saved model connection is unavailable; graph extraction and model explanations require a working generation provider.'
}
Sql 'SELECT version,success FROM education_companion.flyway_schema_history ORDER BY installed_rank;' | Set-Content "$run\logs\flyway-history.tsv"
if ($PlatformServices) {
    Write-Host 'Platform API, graph and AI ready on 18080/18001/18000; persistent data on D:.'
    return
}
Write-Host 'System ready: http://127.0.0.1:18081 ; demo teacher: teacher_demo / Teacher@123'
Write-Host 'MySQL, graph and reports persist on D:. All five services are ready. Stop with scripts/smart_companion/stop-system.ps1.'


