# M1 acceptance: HostConnector TOFU pinning + LAN/tailnet fallback (HostConnectorTest),
# against the Docker test host. Installs the APKs, authorizes the device key, runs the test.
#   pwsh -File tools\spikes\m1-connector.ps1 [-Reset]
param([switch] $Reset)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'
$cls = 'dev.walkcode.app.ssh.HostConnectorTest'

function Start-Emulator {
    if ((& $adb devices) -match 'emulator-\d+\s+device') { return }
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
    for ($i = 0; $i -lt 30 -and -not ((& $adb shell 'echo | toybox nc -w 2 10.0.2.2 2222 2>/dev/null')); $i++) { Start-Sleep 2 }
}

function Invoke-Spike([string] $method, [hashtable] $extra = @{}) {
    $a = @('shell', 'am', 'instrument', '-w', '-e', 'class', "$cls#$method")
    foreach ($k in $extra.Keys) { $a += @('-e', $k, "'$($extra[$k])'") }
    $a += $runner
    $out = (& $adb @a) -join "`n"
    $ok = $out -match 'OK \(1 test\)'
    if (-not $ok) { ($out -split "`n" | Where-Object { $_ -match 'Exception|Error|at dev\.walkcode|Caused by' } | Select-Object -First 8) -join "`n" | Write-Host }
    return $ok
}

Start-Emulator
& $adb logcat -c
& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
& $adb install -r -t "$root\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk" | Out-Null

$resetArg = if ($Reset) { @{ reset = '1' } } else { @{} }
& $adb shell am instrument -w -e class 'dev.walkcode.app.ssh.SshSpikeTest#deviceKey' @resetArg $runner | Out-Null
$pub = ((& $adb shell run-as dev.walkcode.app cat files/device_key.pub 2>$null) -join '').Trim()
if (-not $pub.StartsWith('ssh-ed25519 ')) { throw 'Device key not available.' }
$keysFile = "$root\tools\testhost\keys\authorized_keys"
if (-not (Select-String -Path $keysFile -SimpleMatch $pub -Quiet)) { Add-Content $keysFile $pub }
docker exec walkcode-testhost bash -c "grep -qxF '$pub' /home/dev/.ssh/authorized_keys || echo '$pub' >> /home/dev/.ssh/authorized_keys"

$results = foreach ($m in 'pinsOnFirstUse_thenMatches_andRejectsChange', 'fallsBackToSecondAddress', 'noAddress_fails') {
    "$m : $(if (Invoke-Spike $m) { 'PASS' } else { 'FAIL' })"
}
$results
"--- device log"
& $adb logcat -d -s WalkcodeSpike:I | Select-String 'WalkcodeSpike' | ForEach-Object { '   ' + ($_.Line -replace '^.*WalkcodeSpike:\s*', '') }
