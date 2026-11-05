# The open terminal must not stay frozen on a silently dead link. Against the Docker tmux host:
# freezes only the sshd process serving the terminal, so no error ever reaches the phone while new
# connections keep working, updates the session from the host, and times how long until the phone
# shows the update. Watches the emulator's cached screen, which follows the terminal every 2 s.
# The device key must already be authorized on the host (run m1-connector.ps1 once).
# With a phone attached too: $env:ANDROID_SERIAL = 'emulator-5554'
#   pwsh -NoProfile -File tools\spikes\terminal-relink.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'

function Host-Run([string] $c) { docker exec -u dev walkcode-testhost bash -lc $c }
function Client-Pid { (Host-Run "tmux list-clients -F '#{client_pid}'") | Select-Object -First 1 }
# The sshd process above the tmux client: stopping it freezes that one connection only.
function Sshd-Of([string] $clientPid) {
    $p = $clientPid
    while ($p -and $p -ne '1') {
        $comm = (docker exec walkcode-testhost ps -o comm= -p $p)
        if ($comm -like 'sshd*') { return $p }
        $p = (docker exec walkcode-testhost ps -o ppid= -p $p).Trim()
    }
    throw "no sshd above client $clientPid"
}
function Screen { (& $adb shell run-as dev.walkcode.app cat cache/screens/1.txt 2>$null) -join "`n" }
function Mark([string] $mark) { Host-Run "tmux send-keys -t $script:name 'echo $mark' Enter" | Out-Null }
# Seconds until $mark reaches the phone's screen, or $null after $seconds.
function Wait-Mark([string] $mark, [int] $seconds) {
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt $seconds) {
        if ((Screen) -match $mark) { return [int]((Get-Date) - $t0).TotalSeconds }
        Start-Sleep 1
    }
    return $null
}
function Bring-Back { & $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null }
function Wait-Client {
    for ($i = 0; $i -lt 40; $i++) {
        $c = Client-Pid
        if ($c) { return $c }
        Start-Sleep 1
    }
    throw 'no terminal attached'
}
# Waits until every frozen connection's old client is gone and one live client is attached.
function Reset-Link([string] $sshd) {
    docker exec walkcode-testhost kill -CONT $sshd 2>$null
    Start-Sleep 8
    Bring-Back
    Start-Sleep 3
}

& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null
& $adb shell am force-stop dev.walkcode.app
& $adb shell "run-as dev.walkcode.app sh -c 'rm -f databases/walkcode.db*; rm -rf cache/screens'"
& $adb shell pm grant dev.walkcode.app android.permission.POST_NOTIFICATIONS
Host-Run 'tmux kill-server 2>/dev/null; true' | Out-Null

$seed = (& $adb shell am instrument -w -e class 'dev.walkcode.app.monitor.SeedWatchedSessionTest#seed' $runner) -join "`n"
if ($seed -notmatch 'OK \(1 test\)') { $seed; throw 'seeding failed' }
$script:name = (Host-Run "tmux list-sessions -F '#{session_name}'") | Select-Object -First 1
& $adb logcat -c

# The terminal is open and live.
& $adb shell am start -f 0x24000000 -n dev.walkcode.app/.MainActivity --el sessionId 1 | Out-Null
$client = Wait-Client
Start-Sleep 3
Mark 'mark-live'
$live = Wait-Mark 'mark-live' 15

# A brief trip away over a healthy link: nothing to repair, so no second attach.
& $adb shell input keyevent 3
Start-Sleep 4
Bring-Back
Start-Sleep 8
$keptClient = (Client-Pid) -eq $client

# Dead link, then back to the app: the bug as reported.
$sshd = Sshd-Of (Client-Pid)
& $adb shell input keyevent 3
Start-Sleep 2
docker exec walkcode-testhost kill -STOP $sshd
Mark 'mark-return'
Start-Sleep 3
Bring-Back
$afterReturn = Wait-Mark 'mark-return' 40
Reset-Link $sshd

# Dead link while the terminal stays on screen and nothing else happens.
Wait-Client | Out-Null
$sshd = Sshd-Of (Client-Pid)
docker exec walkcode-testhost kill -STOP $sshd
Mark 'mark-idle'
$whileOpen = Wait-Mark 'mark-idle' 120
Reset-Link $sshd

# Dead link, then the open session's own notification is tapped; well before keepalive notices.
Wait-Client | Out-Null
$sshd = Sshd-Of (Client-Pid)
docker exec walkcode-testhost kill -STOP $sshd
Mark 'mark-tap'
Start-Sleep 2
& $adb shell am start -f 0x24000000 -n dev.walkcode.app/.MainActivity --el sessionId 1 | Out-Null
$afterTap = Wait-Mark 'mark-tap' 25
Reset-Link $sshd

# The replaced link must not bounce: its late "finished" callback must not end the new one.
Start-Sleep 15
$stableClient = Client-Pid
Start-Sleep 15
$stable = $stableClient -and ($stableClient -eq (Client-Pid))
Mark 'mark-end'
$end = Wait-Mark 'mark-end' 15

$crash = (& $adb logcat -d -s AndroidRuntime:E) -join "`n"
[pscustomobject]@{
    liveStreamSec       = $live
    keptClientWhenAlive = $keptClient
    afterReturnSec      = $afterReturn
    whileOpenSec        = $whileOpen
    afterTapSec         = $afterTap
    stableAfterRelink   = $stable
    liveAtEndSec        = $end
    crashed             = [bool]$crash
} | Format-List
if ($crash) { $crash }
