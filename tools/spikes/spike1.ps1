# Spike 1: sshj + Android Keystore Ed25519 key, end to end on the emulator.
#   pwsh -File tools\spikes\spike1.ps1 [-Reset] [-Windows]
# Hosts: Docker test host (10.0.2.2:2222), homelab VM (dev.local -> 192.168.1.88),
# and with -Windows this workstation's OpenSSH (10.0.2.2:22, needs one UAC prompt).
# -Reset deletes the device key first so generation is exercised.
param([switch] $Reset, [switch] $Windows)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$testKey = "$env:USERPROFILE\.ssh\walkcode_test"
$sshOpts = @('-i', $testKey, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
             '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts")
$runner = 'dev.walkcode.app.test/androidx.test.runner.AndroidJUnitRunner'
$cls = 'dev.walkcode.app.ssh.SshSpikeTest'

function Start-Emulator {
    if ((& $adb devices) -match 'emulator-\d+\s+device') { return }
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
    # Networking comes up a little after boot completes.
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

"== deviceKey: $(Invoke-Spike 'deviceKey' $(if ($Reset) { @{ reset = '1' } } else { @{} }))"
$pub = ((& $adb shell run-as dev.walkcode.app cat files/device_key.pub 2>$null) -join '').Trim()
if (-not $pub.StartsWith('ssh-ed25519 ')) {
    & $adb logcat -d -s WalkcodeSpike:I | Select-String 'WalkcodeSpike'
    throw 'Device key not available; see errors above.'
}
"   $pub"

# Authorize on the Docker test host (persisted in the mounted keys file and applied live).
$keysFile = "$root\tools\testhost\keys\authorized_keys"
if (-not (Select-String -Path $keysFile -SimpleMatch $pub -Quiet)) { Add-Content $keysFile $pub }
docker exec walkcode-testhost bash -c "grep -qxF '$pub' /home/dev/.ssh/authorized_keys || echo '$pub' >> /home/dev/.ssh/authorized_keys"

# Authorize on the homelab VM.
ssh @sshOpts marcel@dev.local "grep -qxF '$pub' ~/.ssh/authorized_keys || echo '$pub' >> ~/.ssh/authorized_keys"
$vmIp = (Resolve-DnsName dev.local -Type A -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
if (-not $vmIp) { $vmIp = '192.168.1.88' }

$targets = @(
    @{ name = 'docker test host'; host = '10.0.2.2'; port = '2222'; user = 'dev'; cmd = 'tmux -V; uname -sr' },
    @{ name = 'homelab VM'; host = $vmIp; port = '22'; user = 'marcel'; cmd = 'tmux -V; uname -sr' }
)
if ($Windows) {
    $tmp = "$env:TEMP\walkcode-device.pub"; Set-Content $tmp $pub
    Start-Process pwsh -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-File', "$root\tools\windows-host\setup-sshd.ps1", '-PublicKeyFile', $tmp
    $targets += @{ name = 'Windows workstation'; host = '10.0.2.2'; port = '22'; user = $env:USERNAME; cmd = 'psmux -V' }
}

foreach ($t in $targets) {
    $ok = Invoke-Spike 'connect' @{ host = $t.host; port = $t.port; user = $t.user; cmd = $t.cmd }
    "== connect $($t.name) ($($t.host):$($t.port)): $(if ($ok) { 'PASS' } else { 'FAIL' })"
}
"--- device log"
& $adb logcat -d -s WalkcodeSpike:I | Select-String 'WalkcodeSpike' | ForEach-Object { '   ' + ($_.Line -replace '^.*WalkcodeSpike:\s*', '') }
