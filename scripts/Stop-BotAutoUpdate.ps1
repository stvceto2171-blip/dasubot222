$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptRoot
$WatcherPidFile = Join-Path $RepoRoot ".bot-auto.pid"

if (-not (Test-Path $WatcherPidFile)) {
    Write-Host "Watcher is not running."
    exit 0
}

$watcherPid = Get-Content $WatcherPidFile -ErrorAction SilentlyContinue
if (-not $watcherPid) {
    Remove-Item $WatcherPidFile -ErrorAction SilentlyContinue
    Write-Host "Watcher pid file was empty."
    exit 0
}

$watcher = Get-Process -Id $watcherPid -ErrorAction SilentlyContinue
if (-not $watcher) {
    Remove-Item $WatcherPidFile -ErrorAction SilentlyContinue
    Write-Host "Watcher process $watcherPid was not running."
    exit 0
}

Stop-Process -Id $watcherPid -Force
Remove-Item $WatcherPidFile -ErrorAction SilentlyContinue
Write-Host "Stopped watcher process $watcherPid."
