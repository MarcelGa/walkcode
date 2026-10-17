# Android 15+ dataSync time limit: shortens the limit with a device_config flag, lets the
# monitoring service hit it in the background and checks that it stops itself (no
# "did not stop in time" crash), posts the "Monitoring paused" notice, and resumes from the
# notice's intent. Needs the seeded database from m3-monitor.ps1 / m4-settings.ps1.
#   pwsh -File tools\spikes\m3-timeout.ps1
$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$out = (Resolve-Path "$PSScriptRoot\..\..").Path + '\build\m3'
New-Item -ItemType Directory -Force $out | Out-Null

function Notifications { (& $adb shell dumpsys notification --noredact) -join "`n" }
function ServiceUp { (((& $adb shell dumpsys activity services dev.walkcode.app) -join "`n") -match 'MonitorService') }

if ((& $adb shell getprop ro.build.version.sdk) -lt 35) { throw 'needs an API 35+ device' }
& $adb shell pm grant dev.walkcode.app android.permission.POST_NOTIFICATIONS
& $adb shell "device_config put activity_manager data_sync_fgs_timeout_duration 20000" | Out-Null
try {
    & $adb shell am force-stop dev.walkcode.app
    & $adb logcat -c
    & $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
    Start-Sleep 6
    $startedUp = ServiceUp
    & $adb shell input keyevent 3   # HOME: the limit only counts against a backgrounded app
    $paused = $false
    for ($i = 0; $i -lt 60; $i += 2) {
        if ((Notifications) -match 'Monitoring paused') { $paused = $true; break }
        Start-Sleep 2
    }
    Start-Sleep 3
    $stopped = -not (ServiceUp)
    & $adb exec-out screencap -p > "$out\paused.png"
    $crash = (& $adb logcat -d) -join "`n" -match 'ForegroundServiceDidNotStopInTimeException|did not stop within'

    # Tapping the notice resumes monitoring.
    & $adb shell am start -f 0x24000000 -n dev.walkcode.app/.MainActivity --ez resumeMonitoring true | Out-Null
    Start-Sleep 6
    $resumed = ServiceUp
    $noticeCleared = -not ((Notifications) -match 'Monitoring paused')
} finally {
    & $adb shell "device_config delete activity_manager data_sync_fgs_timeout_duration" | Out-Null
}
[pscustomobject]@{
    serviceStarted   = $startedUp
    pausedNotice     = $paused
    serviceStopped   = $stopped
    didNotStopCrash  = [bool]$crash
    resumedFromNotice = $resumed
    noticeCleared    = $noticeCleared
} | Format-List
