# M3 acceptance on the emulator against the Docker tmux host: a watched session in the
# background raises an attention alert (bell) and a quiet alert, tapping the alert intent opens
# the session, and a frozen host shows "connection lost" and recovers.
# The device key must already be authorized on the host (run m1-connector.ps1 once).
#   pwsh -File tools\spikes\m3-monitor.ps1 [-SkipStale]
param([switch] $SkipStale)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'
$out = "$root\build\m3"
New-Item -ItemType Directory -Force $out | Out-Null

function Start-Emulator {
    if ((& $adb devices) -match 'emulator-\d+\s+device') { return }
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API34', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
    for ($i = 0; $i -lt 30 -and -not ((& $adb shell 'echo | toybox nc -w 2 10.0.2.2 2222 2>/dev/null')); $i++) { Start-Sleep 2 }
}
function Host-Run([string] $c) { docker exec -u dev walkcode-testhost bash -lc $c }
function Notifications { (& $adb shell dumpsys notification --noredact) -join "`n" }
function Wait-Notification([string] $pattern, [int] $seconds) {
    for ($i = 0; $i -lt $seconds; $i += 2) {
        if ((Notifications) -match $pattern) { return $true }
        Start-Sleep 2
    }
    return $false
}
function Dump { & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; [xml]((& $adb shell cat /sdcard/ui.xml) -join '') }

Start-Emulator
& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null
& $adb shell am force-stop dev.walkcode.app
& $adb shell "run-as dev.walkcode.app sh -c 'rm -f databases/walkcode.db*'"
& $adb shell pm grant dev.walkcode.app android.permission.POST_NOTIFICATIONS

# The Docker host needs the Walkcode tmux settings (bell counter hook).
docker cp "$root\tools\host-config\walkcode.tmux.conf" walkcode-testhost:/tmp/walkcode.tmux.conf | Out-Null
docker cp "$root\tools\host-config\install-tmux-conf.sh" walkcode-testhost:/tmp/install-tmux-conf.sh | Out-Null
docker exec -u dev walkcode-testhost bash -c "tr -d '\r' < /tmp/install-tmux-conf.sh > /tmp/i.sh && bash /tmp/i.sh < /tmp/walkcode.tmux.conf" | Out-Null
Host-Run 'tmux kill-server 2>/dev/null; true' | Out-Null

$seed = (& $adb shell am instrument -w -e class 'dev.walkcode.app.monitor.SeedWatchedSessionTest#seed' $runner) -join "`n"
if ($seed -notmatch 'OK \(1 test\)') { $seed; throw 'seeding failed' }
$name = (Host-Run "tmux list-sessions -F '#{session_name}'") | Select-Object -First 1
"watched session on host: $name"

& $adb logcat -c
& $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 8
& $adb shell input keyevent 3   # HOME: app goes to the background, the service keeps monitoring
Start-Sleep 5
$serviceUp = (((& $adb shell dumpsys activity services dev.walkcode.app) -join "`n") -match "MonitorService")

# Bell -> attention alert.
Host-Run "tmux send-keys -t $name `"printf '\a'`" Enter" | Out-Null
$attention = Wait-Notification 'Needs your attention' 20
& $adb exec-out screencap -p > "$out\attention.png"

# Output then silence -> quiet alert (default 15 s).
Host-Run "tmux send-keys -t $name 'echo hi' Enter" | Out-Null
$quiet = Wait-Notification 'No output for' 45

# Alert intent opens the session terminal.
& $adb shell am start -f 0x24000000 -n dev.walkcode.app/.MainActivity --el sessionId 1 | Out-Null
Start-Sleep 8
$opened = $null -ne ((Dump).SelectNodes('//node') | Where-Object { $_.text -eq 'Seed / #1' } | Select-Object -First 1)
& $adb exec-out screencap -p > "$out\opened.png"
& $adb shell input keyevent 3
Start-Sleep 3

$lost = $null; $recovered = $null
if (-not $SkipStale) {
    # Frozen host: connections stall, so the monitor must notice the missing heartbeat.
    docker pause walkcode-testhost | Out-Null
    try { $lost = Wait-Notification 'Connection lost' 150 } finally { docker unpause walkcode-testhost | Out-Null }
    for ($i = 0; $i -lt 60 -and ((Notifications) -match 'Connection lost'); $i += 2) { Start-Sleep 2 }
    $recovered = -not ((Notifications) -match 'Connection lost')
}

$crash = (& $adb logcat -d -s AndroidRuntime:E) -join "`n"
[pscustomobject]@{
    serviceRunning  = [bool]$serviceUp
    attentionAlert  = $attention
    quietAlert      = $quiet
    alertOpensSession = $opened
    connectionLost  = $lost
    recovered       = $recovered
    crashed         = [bool]$crash
} | Format-List
"screenshots: $out"
