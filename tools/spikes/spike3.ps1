# Spike 3: host poller -> ActivityTracker on the emulator, against all test hosts.
#   pwsh -File tools\spikes\spike3.ps1 [-Only docker|vm|windows]
param([string] $Only)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$script:RequiredLocalEnv = @("WALKCODE_VM_HOST", "WALKCODE_VM_IP", "WALKCODE_VM_USER"); . "$PSScriptRoot\..\Import-LocalEnv.ps1"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"

if (-not ((& $adb devices) -match 'emulator-\d+\s+device')) {
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
    for ($i = 0; $i -lt 30 -and -not ((& $adb shell 'echo | toybox nc -w 2 10.0.2.2 2222 2>/dev/null')); $i++) { Start-Sleep 2 }
}
& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null

$vmIp = (Resolve-DnsName $env:WALKCODE_VM_HOST -Type A -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
if (-not $vmIp) { $vmIp = $env:WALKCODE_VM_IP }
$hosts = @(
    @{ id = 'docker'; host = '10.0.2.2'; port = '2222'; user = 'dev'; platform = 'posix'; agent = 'fake-agent' },
    @{ id = 'vm'; host = $vmIp; port = '22'; user = $env:WALKCODE_VM_USER; platform = 'posix'; agent = 'bash /tmp/walkcode-fake-agent' },
    @{ id = 'windows'; host = '10.0.2.2'; port = '22'; user = $env:USERNAME; platform = 'windows'
       agent = "pwsh -NoProfile -File $root\tools\testhost\fake-agent.ps1" }
)
if ($Only) { $hosts = $hosts | Where-Object { $_.id -eq $Only } }

foreach ($h in $hosts) {
    & $adb logcat -c
    $out = (& $adb shell am instrument -w -e class 'dev.walkcode.app.poll.PollSpikeTest' `
        -e host $h.host -e port $h.port -e user $h.user -e platform $h.platform -e agent "'$($h.agent)'" `
        dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    $status = if ($out -match 'OK \(1 test\)') { 'PASS' } else { 'FAIL' }
    $log = (& $adb logcat -d -s WalkcodePoll:I) -join "`n"
    "== $($h.id): $status"
    ([regex]::Match($log, 'RESULT [^\r\n]*')).Value -replace '^RESULT ', '   '
    if ($status -eq 'FAIL') {
        ($out -split "`n" | Where-Object { $_ -match 'Error|Exception|at dev\.walkcode' } | Select-Object -First 6) | ForEach-Object { "   $_" }
        ($log -split "`n" | Where-Object { $_ -match 'event|ALERT|sent|poller' } | Select-Object -Last 20) | ForEach-Object { '   ' + ($_ -replace '^.*WalkcodePoll:\s*', '') }
    }
}
