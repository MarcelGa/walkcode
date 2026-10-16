# Bell behaviour of psmux with a real client attached over SSH. Run from repo root in pwsh.
$o = @('-i', "$env:USERPROFILE\.ssh\walkcode_test", '-o', 'IdentitiesOnly=yes',
       '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts", '-o', 'BatchMode=yes',
       "$env:USERNAME@localhost")
$s = 'wc-ssh'
function State { ssh @o "psmux list-windows -t $s -F `"bell=#{window_bell_flag} @bell=#{@wc_bell} clients=#{session_attached}`"" }
function Turn([string] $p) { ssh @o "psmux send-keys -t $s `"$p`" Enter" | Out-Null; Start-Sleep 7 }

ssh @o "psmux set-hook -g alert-bell `"set -wF @wc_bell '#{e|+:#{?@wc_bell,#{@wc_bell},0},1}'`" & psmux set -wu -t $s @wc_bell" | Out-Null
"start:                 $(State)"

# Attach a real PTY client over SSH for ~40 s, stdin held open so no EOF reaches the pane.
$client = Start-Job -ScriptBlock {
    param($o, $s)
    & { Start-Sleep 40 } | ssh -tt @o "psmux attach -t $s" | Out-Null
} -ArgumentList (, $o), $s
Start-Sleep 4
"client attached:       $(State)"
Turn 'bell one';   "after turn 1 attached: $(State)"
Turn 'bell two';   "after turn 2 attached: $(State)"
Stop-Job $client; Remove-Job $client -Force
Start-Sleep 2
"client gone:           $(State)"
Turn 'bell three'; "after turn 3 detached: $(State)"
