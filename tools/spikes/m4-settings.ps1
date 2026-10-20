# M4 acceptance on the emulator against the Docker tmux host (needs the device key authorized: m1-connector.ps1).
# (fresh seeded database; the Room 1 -> 2 migration was checked by installing over a real v1 database), then checks:
# session rename, battery-optimisation prompt, Settings tab, and that a changed quiet threshold
# takes effect for alerts.
#   pwsh -File tools\spikes\m4-settings.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$out = "$root\build\m4"
New-Item -ItemType Directory -Force $out | Out-Null

function Dump {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $local = Join-Path $env:TEMP 'wc-ui.xml'
    & $adb pull /sdcard/ui.xml $local | Out-Null
    [xml](Get-Content -Raw -Encoding UTF8 $local)
}
function Nodes([string] $t) { @((Dump).SelectNodes('//node') | Where-Object { $_.text -eq $t }) }
function Find([string] $t, [int] $Index = 0) {
    for ($i = 0; $i -lt 8; $i++) {
        $n = @(Nodes $t)
        if ($n.Count -gt $Index) { return $n[$Index] }
        Start-Sleep 1
    }
    throw "UI node '$t' [$Index] not found"
}
function Tap([string] $t, [int] $Index = 0) {
    $n = Find $t $Index
    $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
    Start-Sleep -Milliseconds 800
}
function Has([string] $t) { @(Nodes $t).Count -gt 0 }
function Shot([string] $name) { & $adb exec-out screencap -p > "$out\$name.png" }
function Host-Run([string] $c) { docker exec -u dev walkcode-testhost bash -lc $c }
function Notifications { (& $adb shell dumpsys notification --noredact) -join "`n" }

if (-not ((& $adb devices) -match 'emulator-\d+\s+device')) { throw 'start the emulator first (m3-monitor.ps1 does)' }

& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null
& $adb shell am force-stop dev.walkcode.app
& $adb shell "run-as dev.walkcode.app sh -c 'rm -f databases/walkcode.db* shared_prefs/walkcode_settings.xml'"
Host-Run 'tmux kill-server 2>/dev/null; true' | Out-Null
$seed = (& $adb shell am instrument -w -e class 'dev.walkcode.app.monitor.SeedWatchedSessionTest#seed' 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner') -join "`n"
if ($seed -notmatch 'OK \(1 test\)') { $seed; throw 'seeding failed' }
& $adb shell "dumpsys deviceidle whitelist -dev.walkcode.app" | Out-Null
& $adb shell pm grant dev.walkcode.app android.permission.POST_NOTIFICATIONS
& $adb logcat -c

# The seeded session must exist on the host.
$name = (Host-Run "tmux list-sessions -F '#{session_name}' 2>/dev/null") | Select-Object -First 1
if (-not $name) { Host-Run 'tmux new-session -d -s wc-seed-1 -c /home/dev -x 80 -y 24' | Out-Null; $name = 'wc-seed-1' }

& $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 6
$migrated = Has 'Work'
Tap 'Work'; Tap 'Seed'

# Rename the session (the second pencil: the first is the workspace editor in the top bar).
$null = Find 'Watching'
Tap '✎' 1
$null = Find 'Rename session'
Tap 'Name'
& $adb shell input text 'build'
if ((& $adb shell dumpsys input_method) -match 'mInputShown=true') { & $adb shell input keyevent 4; Start-Sleep 1 }
Tap 'Save'
$renamed = Has 'build'
Shot 'renamed'

# Un-watch and watch again: the first watch after a fresh install asks about battery optimisation.
Tap 'Watching'
Tap 'Watch'
$prompt = Has 'Allow background activity?'
Shot 'battery-prompt'
if ($prompt) { Tap 'Not now' }

# Settings tab: back to the top level, then open Settings.
for ($i = 0; $i -lt 4 -and -not (Has 'Settings'); $i++) { & $adb shell input keyevent 4; Start-Sleep 1 }
Tap 'Settings'
1..3 | ForEach-Object { & $adb shell input swipe 540 1900 540 600 300 }; Start-Sleep 1   # the battery row sits below the Voice section
$optimised = ((Dump).SelectNodes('//node') | Where-Object { $_.text -like 'Optimised*' }).Count -gt 0
1..3 | ForEach-Object { & $adb shell input swipe 540 600 540 1900 300 }; Start-Sleep 1
Shot 'settings'
Tap '5s'
$prefs = (& $adb shell "run-as dev.walkcode.app cat shared_prefs/walkcode_settings.xml") -join ''
$saved = $prefs -match 'quiet_after_sec" value="5"'

# A 5 s threshold must raise the quiet alert well before the 15 s default would.
& $adb shell input keyevent 3
Start-Sleep 6   # monitors restart after the threshold change
Host-Run "tmux send-keys -t $name 'echo hi' Enter" | Out-Null
$t0 = Get-Date
$quietSec = $null
for ($i = 0; $i -lt 30; $i++) {
    if ((Notifications) -match 'No output for') { $quietSec = [int]((Get-Date) - $t0).TotalSeconds; break }
    Start-Sleep 1
}
$titles = [regex]::Matches((Notifications), 'android\.title=String \(([^)]*)\)') | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -like 'Work*' }
$title = [bool]($titles | Where-Object { $_ -match 'Work.{1,6}Seed.{1,6}build' })

# Exemption granted -> Settings shows Unrestricted.
& $adb shell "dumpsys deviceidle whitelist +dev.walkcode.app" | Out-Null
& $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 3
if (-not (Has 'Settings')) { for ($i = 0; $i -lt 4 -and -not (Has 'Projects'); $i++) { & $adb shell input keyevent 4; Start-Sleep 1 } }
Tap 'Settings'
1..3 | ForEach-Object { & $adb shell input swipe 540 1900 540 600 300 }; Start-Sleep 1
$unrestricted = ((Dump).SelectNodes('//node') | Where-Object { $_.text -like 'Unrestricted*' }).Count -gt 0

$crash = (& $adb logcat -d -s AndroidRuntime:E) -join "`n"
[pscustomobject]@{
    migrationKeptData    = $migrated
    sessionRenamed       = $renamed
    batteryPromptShown   = $prompt
    settingsShowsOptimised = $optimised
    thresholdSaved       = $saved
    quietAlertSeconds    = $quietSec
    alertTitleUsesLabel  = $title
    alertTitles          = ($titles -join ' / ')
    settingsShowsUnrestricted = $unrestricted
    crashed              = [bool]$crash
} | Format-List
"screenshots: $out"
