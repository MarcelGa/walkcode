# psmux behaviour probe for Walkcode. Run from the repo root in pwsh.
$ErrorActionPreference = 'Continue'
$px = "$env:LOCALAPPDATA\Programs\psmux\psmux.exe"
$agent = (Resolve-Path "$PSScriptRoot\..\fake-agent.ps1").Path
$dir = "$env:TEMP\opencode\wc-ws\tollbff"
New-Item -ItemType Directory -Force $dir | Out-Null
$s = 'wc-probe'
$sha = [Security.Cryptography.SHA1]::Create()

function Get-ScreenHash {
    $text = (& $px capture-pane -p -t $s) -join "`n"
    -join ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($text)) | Select-Object -First 4 | ForEach-Object { $_.ToString('x2') })
}
function Get-State {
    & $px list-windows -t $s -F 'bell=#{window_bell_flag}|@bell=#{@wc_bell}|title=#{pane_title}'
}
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }

& $px kill-server 2>$null
Start-Sleep 1
& $px new-session -d -s $s -c $dir -x 120 -y 40
& $px set -g allow-set-title on
& $px set-hook -g alert-bell 'set -wF @wc_bell "#{e|+:#{?@wc_bell,#{@wc_bell},0},1}"'
Start-Sleep 2
& $px send-keys -t $s "pwsh -NoProfile -File `"$agent`"" Enter
Start-Sleep 3

"idle:     $(Get-ScreenHash) $(Get-ScreenHash) | $(Get-State)"
& $px send-keys -t $s 'one' Enter
Start-Sleep 1; "working:  $(Get-ScreenHash) | $(Get-State)"
Start-Sleep 1; "working:  $(Get-ScreenHash) | $(Get-State)"
Start-Sleep 5; "done:     $(Get-ScreenHash) $(Get-ScreenHash) | $(Get-State)"

"--- second turn (no client attached)"
& $px send-keys -t $s 'two' Enter
Start-Sleep 7; "done2:    $(Get-State)"

"--- third turn after a control-mode client attached and detached"
$ctl = Start-Job { param($px, $s) 'detach-client' | & $px -C attach -t $s } -ArgumentList $px, $s
Wait-Job $ctl -Timeout 5 | Out-Null; Receive-Job $ctl -ErrorAction SilentlyContinue | Out-Null; Remove-Job $ctl -Force
"after ctl: $(Get-State)"
& $px send-keys -t $s 'three' Enter
Start-Sleep 7; "done3:    $(Get-State)"

"--- server survives this process? sessions: $(& $px ls 2>&1)"
