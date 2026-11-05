# Session notifications on the emulator against the Docker tmux host: one row per watched session
# with its label and last message, grouped by workspace, silent until an alert, and a tap on a row
# opens that session, also from another session's terminal.
# The device key must already be authorized on the host (run m1-connector.ps1 once).
#   pwsh -File tools\spikes\session-notifications.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'

function Start-Emulator {
    if ((& $adb devices) -match 'emulator-\d+\s+device') { return }
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
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
# The key of the notification whose sound is playing; Android prints "null" when there is none.
function Sound-Key {
    $key = [regex]::Match((Notifications), 'mSoundNotificationKey=(\S*)').Groups[1].Value
    if ($key -eq 'null') { '' } else { $key }
}
# Watches the sound key for $seconds and returns every key that played.
function Watch-Sounds([int] $seconds) {
    $end = (Get-Date).AddSeconds($seconds)
    $seen = @()
    while ((Get-Date) -lt $end) { $k = Sound-Key; if ($k -and $seen -notcontains $k) { $seen += $k } }
    $seen
}
function Nodes {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    ([xml]((& $adb shell cat /sdcard/ui.xml) -join '')).SelectNodes('//node')
}
function Tap-Node($node) {
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
}
# Opens the shade and taps the line titled $label; with -Expand, opens its group first.
function Tap-Row([string] $label, [switch] $Expand) {
    & $adb shell cmd statusbar expand-notifications
    Start-Sleep 3
    if ($Expand) {
        $nodes = @(Nodes)
        $row = $nodes | Where-Object { $_.text -eq $label } | Select-Object -First 1
        if (-not $row) { throw "no line titled '$label' in the shade" }
        # The group's Expand button comes right before its lines in the dump.
        $button = $nodes[0..([array]::IndexOf($nodes, $row))] | Where-Object { $_.'content-desc' -eq 'Expand' } | Select-Object -Last 1
        Tap-Node $button
        Start-Sleep 3
    }
    $row = (Nodes) | Where-Object { $_.text -eq $label } | Select-Object -First 1
    if (-not $row) { throw "no line titled '$label' in the shade" }
    # The middle of the row's width, as a thumb would: a tap on the short title text can get lost.
    $y = [regex]::Matches($row.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap 540 ([int](($y[1] + $y[3]) / 2))
    Start-Sleep 8
}
function Go-Home { & $adb shell input keyevent 3; Start-Sleep 2 }
function Top-Bar { (Nodes) | Where-Object { $_.text -match '^(Alpha|Beta) / ' } | ForEach-Object { $_.text } | Select-Object -First 1 }

Start-Emulator
& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null
& $adb shell am force-stop dev.walkcode.app
& $adb shell "run-as dev.walkcode.app sh -c 'rm -f databases/walkcode.db*'"
& $adb shell pm grant dev.walkcode.app android.permission.POST_NOTIFICATIONS

docker cp "$root\tools\host-config\walkcode.tmux.conf" walkcode-testhost:/tmp/walkcode.tmux.conf | Out-Null
docker cp "$root\tools\host-config\install-tmux-conf.sh" walkcode-testhost:/tmp/install-tmux-conf.sh | Out-Null
docker exec -u dev walkcode-testhost bash -c "tr -d '\r' < /tmp/install-tmux-conf.sh > /tmp/i.sh && bash /tmp/i.sh < /tmp/walkcode.tmux.conf" | Out-Null
Host-Run 'tmux kill-server 2>/dev/null; true' | Out-Null

$seed = (& $adb shell am instrument -w -e class 'dev.walkcode.app.monitor.SeedWatchedSessionTest#seedTwoWorkspaces' $runner) -join "`n"
if ($seed -notmatch 'OK \(1 test\)') { $seed; throw 'seeding failed' }
$sessions = @(Host-Run "tmux list-sessions -F '#{session_name}'")
$alpha = @($sessions | Where-Object { $_ -like '*alpha*' } | Sort-Object)
$beta = $sessions | Where-Object { $_ -like '*beta*' } | Select-Object -First 1
"sessions: $($sessions -join ', ')"

& $adb logcat -c
& $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 8
& $adb shell input keyevent 3
Start-Sleep 5

# Rows and groups: 3 rows and 2 summaries, each row in its workspace's group. They must appear
# without any session changing; right after a cold start the service can take a while to come up.
$rowsAfterSec = $null
for ($i = 0; $i -lt 40; $i++) {
    if ([regex]::Matches((Notifications), 'tag=session ').Count -ge 3) { $rowsAfterSec = $i; break }
    Start-Sleep 1
}
$shade = Notifications
$tags = [regex]::Matches($shade, 'NotificationRecord\([^)]*tag=(session|workspace) ')
$rowCount = @($tags | Where-Object { $_.Groups[1].Value -eq 'session' }).Count
$groupCount = @($tags | Where-Object { $_.Groups[1].Value -eq 'workspace' }).Count
$titles = [regex]::Matches($shade, 'android\.title=String \(([^)]*)\)') | ForEach-Object { $_.Groups[1].Value }
$labels = ('build', 'tests', 'docs') | Where-Object { $titles -contains $_ }
$headers = ($shade -match 'android\.subText=String \(Work.{1,6}Alpha\)') -and ($shade -match 'android\.subText=String \(Work.{1,6}Beta\)')
$silentOnWatch = -not (Sound-Key)

# Messages reach the row silently. Steady output, so no quiet alert can fire during the watch.
Host-Run "tmux send-keys -t $($alpha[0]) 'for i in 1 2 3 4 5 6 7 8; do echo tick-`$i; sleep 3; done' Enter" | Out-Null
$soundsDuringMessage = @(Watch-Sounds 22)
$message = (Notifications) -match 'android\.text=String \((Working: )?tick-\d\)'
$silentOnMessage = $soundsDuringMessage.Count -eq 0

# A bell alerts: the sound belongs to that session's row (session ids follow the seed order, docs is 3).
Start-Sleep 25   # past build's quiet alert
Host-Run "tmux send-keys -t $beta `"printf '\a'; echo bell-rung`" Enter" | Out-Null
$soundsAfterBell = @(Watch-Sounds 12)
$bellSound = [bool]($soundsAfterBell | Where-Object { $_ -match '\|3\|session\|' })
# The alert's wording must survive the next refresh (10 s) rather than turn back into the plain line.
Start-Sleep 12
$alertWordingKept = (Notifications) -match 'android\.text=String \(Done: bell-rung\)'

# A tap on a collapsed group of several sessions opens the workspace's list.
Tap-Row 'build'
$collapsedShowsList = [bool]((Nodes) | Where-Object { $_.text -eq 'Alpha' -or $_.text -like '*+ New session*' })
$collapsedClients = @(Host-Run "tmux list-clients -F '#{session_name}'")

# A tap on an expanded row opens exactly that session.
Go-Home
Tap-Row 'tests' -Expand
$expandedTap = Top-Bar
$expandedClients = @(Host-Run "tmux list-clients -F '#{session_name}'")

# From inside that terminal, a tap on another workspace's single row switches session.
Tap-Row 'docs'
$fromTerminal = Top-Bar
Start-Sleep 2
$clientsAfterSwitch = @(Host-Run "tmux list-clients -F '#{session_name}'")

$crash = (& $adb logcat -d -s AndroidRuntime:E) -join "`n"
[pscustomobject]@{
    rowsAfterSec        = $rowsAfterSec
    rows                = $rowCount
    groupSummaries      = $groupCount
    labelsAsTitles      = ($labels -join ',')
    workspaceHeaders    = $headers
    silentOnWatch       = $silentOnWatch
    messageInRow        = $message
    silentOnMessage     = $silentOnMessage
    bellSoundOnItsRow   = $bellSound
    soundsAfterBell     = ($soundsAfterBell -join ' ')
    alertWordingKept    = $alertWordingKept
    collapsedShowsList  = $collapsedShowsList
    collapsedClients    = ($collapsedClients -join ',')
    expandedTap         = $expandedTap
    expandedClients     = ($expandedClients -join ',')
    expectedExpanded    = $alpha[1]
    tapFromTerminal     = $fromTerminal
    clientsAfterSwitch  = ($clientsAfterSwitch -join ',')
    expectedSwitch      = $beta
    crashed             = [bool]$crash
} | Format-List
if ($crash) { $crash }
