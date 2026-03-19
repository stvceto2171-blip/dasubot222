$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptRoot
$WatcherScript = Join-Path $ScriptRoot "Watch-BotAutoUpdate.ps1"
$WatcherPidFile = Join-Path $RepoRoot ".bot-auto.pid"
$StdOutLog = Join-Path $RepoRoot "bot-auto.log"
$StdErrLog = Join-Path $RepoRoot "bot-auto.err.log"

if (Test-Path $WatcherPidFile) {
    $existingPid = Get-Content $WatcherPidFile -ErrorAction SilentlyContinue
    if ($existingPid) {
        $running = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
        if ($running) {
            Write-Host "Watcher already running with PID $existingPid."
            exit 0
        }
    }
}

$process = Start-Process `
    -FilePath "powershell.exe" `
    -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $WatcherScript `
    -WorkingDirectory $RepoRoot `
    -RedirectStandardOutput $StdOutLog `
    -RedirectStandardError $StdErrLog `
    -PassThru

Write-Host "Started auto-update watcher with PID $($process.Id)."
