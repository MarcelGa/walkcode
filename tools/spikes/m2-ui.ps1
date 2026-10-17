# M2 UI acceptance on the emulator against the Docker tmux host: drives the real screens
# (add host -> project -> workspace -> new session -> terminal), types into the terminal and
# checks the result on the host. Wipes the app database first; the device key must already be
# authorized on the host (run m1-connector.ps1 once).
#   pwsh -File tools\spikes\m2-ui.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$out = "$root\build\m2-ui"
New-Item -ItemType Directory -Force $out | Out-Null

function Start-Emulator {
    if ((& $adb devices) -match 'emulator-\d+\s+device') { return }
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API34', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
    for ($i = 0; $i -lt 30 -and -not ((& $adb shell 'echo | toybox nc -w 2 10.0.2.2 2222 2>/dev/null')); $i++) { Start-Sleep 2 }
}
function Dump { & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; [xml]((& $adb shell cat /sdcard/ui.xml) -join '') }
function Find([string] $t, [switch] $Prefix) {
    for ($i = 0; $i -lt 8; $i++) {
        $n = (Dump).SelectNodes('//node') | Where-Object { if ($Prefix) { $_.text -like "$t*" } else { $_.text -eq $t } } | Select-Object -First 1
        if ($n) { return $n }
        Start-Sleep 1
    }
    throw "UI node '$t' not found"
}
function Tap([string] $t, [switch] $Prefix) {
    $n = Find $t -Prefix:$Prefix
    $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
    Start-Sleep -Milliseconds 700
}
# Picker overlays are clickable, text-less Views; they appear in form order (Project, Host, Agent).
function Tap-Picker([int] $index) {
    $views = (Dump).SelectNodes('//node') | Where-Object { $_.class -eq 'android.view.View' -and $_.clickable -eq 'true' -and $_.bounds -match '^\[32,' } |
        Sort-Object { [int]([regex]::Match($_.bounds, ',(\d+)\]').Groups[1].Value) }
    $b = [regex]::Matches(@($views)[$index].bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
    Start-Sleep -Milliseconds 700
}
function Hide-Keyboard {
    if ((& $adb shell dumpsys input_method) -match 'mInputShown=true') { & $adb shell input keyevent 4; Start-Sleep -Milliseconds 500 }
}
function Fill([string] $label, [string] $text) {
    Tap $label
    & $adb shell input text ($text -replace ' ', '%s')
    Hide-Keyboard
}
function Shot([string] $name) { & $adb exec-out screencap -p > "$out\$name.png" }
function Host-Run([string] $c) { docker exec -u dev walkcode-testhost bash -lc $c }

Start-Emulator
& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb shell am force-stop dev.walkcode.app
& $adb shell "run-as dev.walkcode.app sh -c 'rm -f databases/walkcode.db*'"
Host-Run 'tmux kill-server 2>/dev/null; true' | Out-Null
& $adb logcat -c
& $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 5

# Host
Tap 'Hosts'; Tap '+'
Fill 'Name' 'docker'
Fill 'User' 'dev'
Tap '22'; & $adb shell input keyevent 123; 1..4 | ForEach-Object { & $adb shell input keyevent 67 }; & $adb shell input text 2222; Hide-Keyboard
Fill 'LAN address (tried first)' '10.0.2.2'
Shot 'host-form'
Tap 'Save'
$null = Find 'docker'
Shot 'hosts'

# Project
Tap 'Projects'; Tap '+'
Fill 'Name' 'Work'
Tap 'Create'
Tap 'Work'

# Workspace
Tap '+'
Fill 'Name' 'Demo'
Fill 'Directory' '/home/dev'
Tap-Picker 2; Tap 'Plain shell'
Shot 'workspace-form'
Tap 'Save'

# Session -> terminal
Tap 'Demo'
$null = Find 'No sessions yet. Start one below.'
Tap '+ New session'
Start-Sleep 8
Shot 'terminal'
& $adb shell input text 'echo%sWC_UI_OK'
& $adb shell input keyevent 66
Start-Sleep 2
$session = (Host-Run "tmux list-sessions -F '#{session_name}'") -join ','
$name = ((Host-Run "tmux list-sessions -F '#{session_name}'") | Select-Object -First 1)
$screen = (Host-Run "tmux capture-pane -p -t $name") -join "`n"
$typed = $screen -match 'WC_UI_OK'

# Back out: the workspace list must show the session as running
# The first Back may only hide the keyboard, so press Back until the workspace screen shows.
for ($i = 0; $i -lt 3; $i++) {
    if ((Dump).SelectNodes('//node') | Where-Object { $_.text -eq '+ New session' }) { break }
    & $adb shell input keyevent 4
    Start-Sleep 2
}
Start-Sleep 3
$running = $null -ne (Find 'running')
Shot 'workspace-running'

$crash = (& $adb logcat -d -s AndroidRuntime:E) -join "`n"
[pscustomobject]@{
    hostSessions   = $session
    typedInTerminal = $typed
    listShowsRunning = $running
    crashed        = [bool]$crash
} | Format-List
"screenshots: $out"
