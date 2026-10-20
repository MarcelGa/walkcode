# Spike 2: Termux terminal attached to tmux/psmux over SSH, end to end on the emulator.
#   pwsh -File tools\spikes\spike2.ps1 [-Only docker|vm|windows]
# Requires spike 1 to have authorized the emulator's device key on the hosts.
param([string] $Only)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$script:RequiredLocalEnv = @("WALKCODE_VM_HOST", "WALKCODE_VM_IP", "WALKCODE_VM_USER"); . "$PSScriptRoot\..\Import-LocalEnv.ps1"
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$out = "$root\build\spike2"
New-Item -ItemType Directory -Force $out | Out-Null
$k = @('-i', "$env:USERPROFILE\.ssh\walkcode_test", '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
       '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts")
$session = 'spike'
$prompt = 'hello from phone'

function Start-Emulator {
    if ((& $adb devices) -match 'emulator-\d+\s+device') { return }
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-audio', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    & $adb wait-for-device
    while ((& $adb shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
    for ($i = 0; $i -lt 30 -and -not ((& $adb shell 'echo | toybox nc -w 2 10.0.2.2 2222 2>/dev/null')); $i++) { Start-Sleep 2 }
    & $adb shell input keyevent 82 | Out-Null   # dismiss keyguard
}

function Tap-Text([string] $text) {
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $xml = [xml]((& $adb shell cat /sdcard/ui.xml) -join '')
    $node = $xml.SelectNodes('//node') | Where-Object { $_.text -eq $text } | Select-Object -First 1
    if (-not $node) { throw "No UI node with text '$text'" }
    $n = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($n[0] + $n[2]) / 2)) ([int](($n[1] + $n[3]) / 2))
}

$vmIp = (Resolve-DnsName $env:WALKCODE_VM_HOST -Type A -ErrorAction SilentlyContinue | Select-Object -First 1).IPAddress
if (-not $vmIp) { $vmIp = $env:WALKCODE_VM_IP }
$winAgent = "$root\tools\testhost\fake-agent.ps1"

$hosts = @(
    @{ id = 'docker'; host = '10.0.2.2'; port = 2222; user = 'dev'; mux = 'tmux'
       run = { param($c) docker exec -u dev walkcode-testhost bash -lc $c }
       agent = 'fake-agent --profile work'; dir = '~/src/tollbff' },
    @{ id = 'vm'; host = $vmIp; port = 22; user = $env:WALKCODE_VM_USER; mux = 'tmux'
       run = { param($c) ssh @k ${env:WALKCODE_VM_USER}@${env:WALKCODE_VM_HOST} $c }
       agent = 'bash /tmp/walkcode-fake-agent --profile work'; dir = '~' },
    @{ id = 'windows'; host = '10.0.2.2'; port = 22; user = $env:USERNAME; mux = 'psmux'
       run = { param($c) ssh @k "$env:USERNAME@localhost" $c }
       agent = "pwsh -NoProfile -File $winAgent --profile work"; dir = $env:TEMP
       # Over Windows OpenSSH, Ctrl+C is disabled for everything started in the session
       # unless the shell re-enables it (tools/host-config/walkcode-shell-init.ps1).
       shell = "pwsh -NoLogo -NoExit -File $HOME\.config\walkcode\shell-init.ps1" }
)
if ($Only) { $hosts = $hosts | Where-Object { $_.id -eq $Only } }

Start-Emulator
& $adb install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
scp @k "$root\tools\testhost\fake-agent" ${env:WALKCODE_VM_USER}@${env:WALKCODE_VM_HOST}:/tmp/walkcode-fake-agent | Out-Null
ssh @k ${env:WALKCODE_VM_USER}@${env:WALKCODE_VM_HOST} "sed -i 's/\r$//' /tmp/walkcode-fake-agent" | Out-Null
# The Docker test host gets the same Walkcode tmux settings as real hosts.
docker cp "$root\tools\host-config\walkcode.tmux.conf" walkcode-testhost:/tmp/walkcode.tmux.conf | Out-Null
docker cp "$root\tools\host-config\install-tmux-conf.sh" walkcode-testhost:/tmp/install-tmux-conf.sh | Out-Null
docker exec -u dev walkcode-testhost bash -c "tr -d '\r' < /tmp/install-tmux-conf.sh > /tmp/i.sh && bash /tmp/i.sh < /tmp/walkcode.tmux.conf" | Out-Null

$results = @()
foreach ($h in $hosts) {
    $mux = $h.mux
    $fmt = '#{window_width}x#{window_height} bell=#{@wc_bell} title=#{pane_title}'
    "== $($h.id): preparing $mux session '$session'"
    & $h.run "$mux kill-session -t $session" 2>$null | Out-Null
    & $h.run "$mux new-session -d -s $session -c $($h.dir) -x 80 -y 24 $($h.shell)" | Out-Null
    & $h.run "$mux send-keys -t $session `"$($h.agent)`" Enter" | Out-Null
    Start-Sleep 3
    $before = (& $h.run "$mux list-windows -t $session -F `"$fmt`"") -join ''

    & $adb shell am force-stop dev.walkcode.app
    & $adb logcat -c
    $attach = if ($mux -eq 'tmux') { "tmux -u attach -t $session" } else { "psmux attach -t $session" }
    $pty = if ($mux -eq 'tmux') { 'true' } else { 'false' }
    & $adb shell am start -n dev.walkcode.app/.spike.TerminalSpikeActivity --es host $h.host --ei port $h.port --es user $h.user --es cmd "'$attach'" --ez pty $pty | Out-Null
    Start-Sleep 8

    # Type a prompt through the keyboard path, then wait for the fake agent's 5 s "work".
    & $adb shell input text ($prompt -replace ' ', '%s')
    & $adb shell input keyevent 66
    Start-Sleep 8
    $after = (& $h.run "$mux list-windows -t $session -F `"$fmt`"") -join ''
    # The terminal size tmux sees from the phone (window height excludes tmux's status line).
    $clientSize = ((& $h.run "$mux list-clients -t $session -F `"#{client_width}x#{client_height}`"") | Select-Object -First 1)
    $screen = (& $h.run "$mux capture-pane -p -t $session") -join "`n"
    & $adb exec-out screencap -p > "$out\$($h.id)-agent.png"

    # Special-key row: start another turn and interrupt it with the ^C button.
    & $adb shell input text 'long%stask'; & $adb shell input keyevent 66
    Start-Sleep 1
    Tap-Text '^C'
    Start-Sleep 6   # longer than the agent's 5 s turn, so an ignored ^C shows up as "done:"
    $afterCtrlC = (& $h.run "$mux capture-pane -p -t $session") -join "`n"
    & $adb exec-out screencap -p > "$out\$($h.id)-ctrlc.png"

    # Resize: rotate to landscape and check the host follows the phone's new size.
    & $adb shell settings put system accelerometer_rotation 0
    & $adb shell settings put system user_rotation 1
    Start-Sleep 4
    $rotLog = (& $adb logcat -d -s WalkcodeTerm:I) -join "`n"
    $rotPhone = ([regex]::Matches($rotLog, 'size (\d+x\d+)') | Select-Object -Last 1).Groups[1].Value
    $rotHost = (& $h.run "$mux list-clients -t $session -F `"#{client_width}x#{client_height}`"") | Select-Object -First 1
    $rotHost = "$rotHost".Trim()
    & $adb exec-out screencap -p > "$out\$($h.id)-landscape.png"
    & $adb shell settings put system user_rotation 0
    Start-Sleep 2

    $log = (& $adb logcat -d -s WalkcodeTerm:I) -join "`n"
    $phoneSize = ([regex]::Matches($log, 'size (\d+x\d+)') | Select-Object -Last 1).Groups[1].Value
    $hostSize = "$clientSize".Trim()
    $r = [ordered]@{
        host       = $h.id
        connected  = $log -match 'connected '
        typed      = $screen -match "done: $prompt"
        sizeMatch  = $phoneSize -and ($phoneSize -eq $hostSize)
        phoneSize  = $phoneSize
        hostSize   = $hostSize
        bellBefore = ([regex]::Match($before, 'bell=(\d*)')).Groups[1].Value
        bellAfter  = ([regex]::Match($after, 'bell=(\d*)')).Groups[1].Value
        ctrlC      = ($afterCtrlC -match 'working on: long task') -and -not ($afterCtrlC -match 'done: long task')
        keyLog     = ([regex]::Matches($log, 'extra key (\S+)') | ForEach-Object { $_.Groups[1].Value }) -join ','
        resized    = $rotPhone -and ($rotPhone -ne $phoneSize) -and ($rotPhone -eq $rotHost)
        landscape  = "$rotPhone/$rotHost"
        appBells   = ([regex]::Matches($log, 'bell #')).Count
    }
    $results += [pscustomobject]$r
    "   last host lines:"; ($afterCtrlC -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 4) | ForEach-Object { "     | $_" }
    & $adb shell am force-stop dev.walkcode.app
}
$results | Format-List | Out-String -Width 200
"screenshots: $out"
