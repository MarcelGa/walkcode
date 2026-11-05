# Swiping the terminal must scroll a tmux session's history, not press arrow keys in its agent.
# On the emulator against the Docker tmux host. Saves screenshots of each state to $env:TEMP\walkcode-scroll.
# The device key must already be authorized on the host (run m1-connector.ps1 once).
# With a phone attached too: $env:ANDROID_SERIAL = 'emulator-5554'
#   pwsh -NoProfile -File tools\spikes\terminal-scroll.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'
$shots = "$env:TEMP\walkcode-scroll"
New-Item -ItemType Directory -Force $shots | Out-Null

function Host-Run([string] $c) { docker exec -u dev walkcode-testhost bash -lc $c }
function Pane { (Host-Run "tmux capture-pane -p -t $script:name") -join "`n" }
function Shot([string] $n) {
    & $adb shell screencap -p /sdcard/s.png
    & $adb pull /sdcard/s.png "$shots\$n.png" | Out-Null
}
# Finger down low and moving up shows later text; down shows earlier text.
# Keep inside the terminal, which ends above the Dictate row even with the keyboard up.
function Swipe-Back { & $adb shell input swipe 500 450 500 1050 400 }
function Swipe-Forward { & $adb shell input swipe 500 1050 500 450 400 }
function Flick-Back { & $adb shell input swipe 500 450 500 1050 60 }
# An arrow key would recall an old command onto the prompt, whichever one it was.
function Prompt-Empty { ((Pane).TrimEnd() -split "`n")[-1] -match '\$\s*$' }
function Has-Live-Pill {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    ((& $adb shell cat /sdcard/ui.xml) -join '') -match 'text="Back to live"'
}
function Tap-Live-Pill {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $n = ([xml]((& $adb shell cat /sdcard/ui.xml) -join '')).SelectNodes('//node') | Where-Object { $_.text -eq 'Back to live' } | Select-Object -First 1
    $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
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
& $adb shell am start -f 0x24000000 -n dev.walkcode.app/.MainActivity --el sessionId 1 | Out-Null
for ($i = 0; $i -lt 40 -and -not (Host-Run "tmux list-clients -F x"); $i++) { Start-Sleep 1 }
Start-Sleep 4

# Long output, so there is history above the screen. The bash prompt afterwards is empty, and an Up
# arrow reaching it would put this command back on the prompt line.
Host-Run "tmux send-keys -t $script:name 'clear; for i in `$(seq 1 300); do echo long-line-`$i; done' Enter" | Out-Null
Start-Sleep 3
Shot '1-live'

Swipe-Back
Start-Sleep 3
Shot '2-swiped-back'
$promptClean = Prompt-Empty
$pillShown = Has-Live-Pill

Flick-Back
Start-Sleep 3
Shot '3-flicked-back'

# New output while reading: the view stays put, and the live screen has it on the way back.
Host-Run "tmux send-keys -t $script:name 'echo arrived-while-reading' Enter" | Out-Null
Start-Sleep 2
Shot '4-reading-with-new-output'
$pillStill = Has-Live-Pill
$pillBack = $false
if ($pillStill) { Tap-Live-Pill; Start-Sleep 2; $pillBack = -not (Has-Live-Pill) }
Shot '5-back-to-live'

# Swiping to the bottom also returns to live, and a swipe the other way at the bottom sends nothing.
Swipe-Back; Start-Sleep 3
Swipe-Forward; Start-Sleep 1
Swipe-Forward; Start-Sleep 1
Swipe-Forward; Start-Sleep 2
$bottomReturns = -not (Has-Live-Pill)
Swipe-Forward; Start-Sleep 2
$forwardNoKeys = Prompt-Empty

# Typing while reading goes to the session and returns to live.
Swipe-Back; Start-Sleep 3
& $adb shell input text 'echo%styped-while-reading'
Start-Sleep 2
$typedArrived = (Pane) -match 'echo typed-while-reading'
$typedBack = -not (Has-Live-Pill)
& $adb shell input keyevent 66
Start-Sleep 2

# A full-screen program in the pane has no history to read, and keeps getting arrow keys.
Host-Run "tmux send-keys -t $script:name 'seq 1 500 | less' Enter" | Out-Null
Start-Sleep 2
Swipe-Forward
Start-Sleep 3
$lessScrolled = (Pane) -notmatch '(?m)^1$'
$lessNoPill = -not (Has-Live-Pill)
Shot '6-less'
Host-Run "tmux send-keys -t $script:name q" | Out-Null

$crash = (& $adb logcat -d -s AndroidRuntime:E) -join "`n"
[pscustomobject]@{
    swipeSendsNoArrowKeys  = $promptClean
    historyViewOpens       = $pillShown
    staysOpenOnNewOutput   = $pillStill
    backToLivePillReturns  = $pillBack
    swipeToBottomReturns   = $bottomReturns
    forwardAtBottomNoKeys  = $forwardNoKeys
    typingReachesSession   = $typedArrived
    typingReturnsToLive    = $typedBack
    fullScreenGetsArrows   = $lessScrolled
    fullScreenNoHistory    = $lessNoPill
    crashed                = [bool]$crash
    screenshots            = $shots
} | Format-List
if ($crash) { $crash }
