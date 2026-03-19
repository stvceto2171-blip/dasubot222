param(
    [int]$DebounceMilliseconds = 1500
)

$ErrorActionPreference = "Stop"

$ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptRoot
$MavenWrapper = Join-Path $RepoRoot "mvnw.cmd"
$BuiltJar = Join-Path $RepoRoot "target\TutorialBot.jar"
$RuntimeDir = Join-Path $RepoRoot "TBot"
$RuntimeJar = Join-Path $RuntimeDir "TutorialBot.jar"
$EnvFile = Join-Path $RepoRoot ".bot.env"
$StdOutLog = Join-Path $RepoRoot "bot-run.log"
$StdErrLog = Join-Path $RepoRoot "bot-run.err.log"
$WatcherPidFile = Join-Path $RepoRoot ".bot-auto.pid"
$RuntimePidFile = Join-Path $RepoRoot ".bot-runtime.pid"

function Write-Log {
    param([string]$Message)

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host "[$timestamp] $Message"
}

function Get-BotProcesses {
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -match "^javaw?\.exe$" -and
            $_.CommandLine -match '(?i)-jar\s+TBot\\TutorialBot\.jar'
        }
}

function Stop-BotProcess {
    $processes = Get-BotProcesses
    foreach ($process in $processes) {
        Write-Log "Stopping bot process $($process.ProcessId)."
        Stop-Process -Id $process.ProcessId -Force
    }
}

function Import-BotEnvironment {
    if (-not (Test-Path $EnvFile)) {
        return
    }

    foreach ($line in Get-Content $EnvFile) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }

        if ($line.TrimStart().StartsWith("#")) {
            continue
        }

        $parts = $line -split '=', 2
        if ($parts.Count -ne 2) {
            continue
        }

        $name = $parts[0].Trim()
        $value = $parts[1].Trim()

        if ($name) {
            Set-Item -Path "Env:$name" -Value $value
        }
    }
}

function Start-BotProcess {
    if (-not (Test-Path $RuntimeJar)) {
        throw "Runtime jar not found at $RuntimeJar"
    }

    Import-BotEnvironment

    foreach ($requiredName in @("TELEGRAM_BOT_USERNAME", "TELEGRAM_BOT_TOKEN")) {
        $requiredValue = (Get-Item -Path "Env:$requiredName" -ErrorAction SilentlyContinue).Value
        if (-not $requiredValue) {
            throw "Missing $requiredName. Set it in the environment or create $EnvFile."
        }
    }

    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if (-not $javaCommand) {
        throw "java is not available on PATH."
    }

    $process = Start-Process `
        -FilePath $javaCommand.Source `
        -ArgumentList "-jar", "TBot\TutorialBot.jar" `
        -WorkingDirectory $RepoRoot `
        -RedirectStandardOutput $StdOutLog `
        -RedirectStandardError $StdErrLog `
        -PassThru

    Set-Content -Path $RuntimePidFile -Value $process.Id -NoNewline
    Write-Log "Started bot process $($process.Id)."
}

function Publish-Bot {
    Write-Log "Building updated bot jar."
    & $MavenWrapper package
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE."
    }

    if (-not (Test-Path $BuiltJar)) {
        throw "Expected built jar not found at $BuiltJar"
    }

    if (-not (Test-Path $RuntimeDir)) {
        New-Item -ItemType Directory -Path $RuntimeDir | Out-Null
    }

    Copy-Item -Path $BuiltJar -Destination $RuntimeJar -Force
    Write-Log "Copied updated jar to TBot."

    Stop-BotProcess
    Start-Sleep -Milliseconds 500
    Start-BotProcess
}

function Register-RepoWatcher {
    param(
        [string]$Path,
        [string]$Filter = "*",
        [bool]$IncludeSubdirectories = $true
    )

    $watcher = New-Object System.IO.FileSystemWatcher
    $watcher.Path = $Path
    $watcher.Filter = $Filter
    $watcher.IncludeSubdirectories = $IncludeSubdirectories
    $watcher.NotifyFilter = [System.IO.NotifyFilters]'FileName, LastWrite, DirectoryName, CreationTime'
    $watcher.EnableRaisingEvents = $true
    return $watcher
}

function Should-IgnorePath {
    param([string]$FullPath)

    if ($fullPath -match '\\(target|TBot|\.git|\.idea)\\') {
        return $true
    }

    if ($fullPath -match 'bot-run(\.err)?\.log$' -or $fullPath -match '\.pid$') {
        return $true
    }

    return $false
}

$script:PendingChange = $false
$script:LastChangeAt = Get-Date

$watchers = @(
    (Register-RepoWatcher -Path (Join-Path $RepoRoot "src")),
    (Register-RepoWatcher -Path $RepoRoot -Filter "pom.xml" -IncludeSubdirectories $false)
)

$registrations = @()
foreach ($watcher in $watchers) {
    $registrations += Register-ObjectEvent -InputObject $watcher -EventName Changed
    $registrations += Register-ObjectEvent -InputObject $watcher -EventName Created
    $registrations += Register-ObjectEvent -InputObject $watcher -EventName Deleted
    $registrations += Register-ObjectEvent -InputObject $watcher -EventName Renamed
}

Write-Log "Watching $RepoRoot for source changes."
Write-Log "Press Ctrl+C to stop the watcher."
Set-Content -Path $WatcherPidFile -Value $PID -NoNewline

try {
    Publish-Bot

    while ($true) {
        $nextEvent = Wait-Event -Timeout 1
        if ($nextEvent) {
            $queuedEvents = @($nextEvent)
            $queuedEvents += Get-Event

            foreach ($queuedEvent in $queuedEvents) {
                $fullPath = $queuedEvent.SourceEventArgs.FullPath
                if (-not (Should-IgnorePath -FullPath $fullPath)) {
                    $script:PendingChange = $true
                    $script:LastChangeAt = Get-Date
                    Write-Log "Detected change: $fullPath"
                }

                Remove-Event -EventIdentifier $queuedEvent.EventIdentifier -ErrorAction SilentlyContinue
            }
        }

        if (-not $script:PendingChange) {
            continue
        }

        $elapsed = (Get-Date) - $script:LastChangeAt
        if ($elapsed.TotalMilliseconds -lt $DebounceMilliseconds) {
            continue
        }

        $script:PendingChange = $false

        try {
            Publish-Bot
        } catch {
            Write-Log "Automatic update failed: $($_.Exception.Message)"
        }
    }
} finally {
    Remove-Item -Path $WatcherPidFile -ErrorAction SilentlyContinue

    foreach ($registration in $registrations) {
        Unregister-Event -SubscriptionId $registration.Id -ErrorAction SilentlyContinue
        Remove-Job -Id $registration.Id -Force -ErrorAction SilentlyContinue
    }

    foreach ($watcher in $watchers) {
        $watcher.Dispose()
    }
}
