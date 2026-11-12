@echo off
setlocal
set "SC_START=%~dp0scripts\smart_companion\start-platform.ps1"
where pwsh.exe >nul 2>nul
if errorlevel 1 goto bundled
pwsh.exe -NoLogo -NoProfile -File "%SC_START%"
goto result

:bundled
powershell.exe -NoLogo -NoProfile -Command "$pkg = Get-AppxPackage -Name 'OpenAI.CodexPrimaryRuntime*' | Sort-Object Version -Descending | Where-Object { Test-Path -LiteralPath (Join-Path $_.InstallLocation 'dependencies\native\powershell\pwsh.exe') } | Select-Object -First 1; if (-not $pkg) { Write-Error 'PowerShell 7 or Codex Primary Runtime is required.'; exit 1 }; $exe = Join-Path $pkg.InstallLocation 'dependencies\native\powershell\pwsh.exe'; & $exe -NoLogo -NoProfile -File $env:SC_START; exit $LASTEXITCODE"

:result
if errorlevel 1 goto failed
start "" "http://127.0.0.1:18090/"
exit /b 0
:failed
echo Could not start the local system. The output above contains the startup error.
pause
exit /b 1
