# Pane title -> session name on the Windows desktop (psmux): AutoTitleTest. The device key must already be in the host's administrators_authorized_keys (see m1-connector.ps1).
#   pwsh -File tools\spikes\autotitle.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$env:ANDROID_SERIAL = 'emulator-5554'
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'
$cls = 'dev.walkcode.app.session.AutoTitleTest'

if (-not ((& $adb devices) -match 'emulator-5554\s+device')) {
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
}

& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null

$out = (& $adb shell am instrument -w -e class "$cls#paneTitleNamesSessionWindows" -e winUser $env:USERNAME $runner) -join "`n"
if ($out -match 'OK \(1 test\)') { 'paneTitleNamesSessionWindows : PASS' }
else {
    'paneTitleNamesSessionWindows : FAIL'
    ($out -split "`n" | Where-Object { $_ -match 'Exception|Error|at dev\.walkcode|Caused by|expected' } | Select-Object -First 12) -join "`n"
    exit 1
}
