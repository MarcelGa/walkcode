# Which way of pressing Enter submits a prompt to Claude Code in a psmux pane?
$k = @('-i', "$env:USERPROFILE\.ssh\walkcode_test", '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
       '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts", "$env:USERNAME@localhost")
$s = 'wc-enter'
$dir = "$env:TEMP\opencode\claude-bell"
function Remote([string] $c) { ssh @k $c }
function InputLine { ((Remote "psmux capture-pane -p -t $s") | Where-Object { $_ -match 'say (one|two|three)' }) -join ' / ' }

Remote "psmux kill-session -t $s 2>nul & psmux new-session -d -s $s -c $dir -x 110 -y 32 pwsh -NoLogo -NoExit -File %USERPROFILE%\.config\walkcode\shell-init.ps1" | Out-Null
Start-Sleep 4
Remote "psmux send-keys -t $s `"claude --model haiku`" Enter" | Out-Null
Start-Sleep 14

"--- 1: send-keys -l text + send-keys -H 0d"
Remote "psmux send-keys -t $s -l `"say one`"" | Out-Null; Start-Sleep 1
Remote "psmux send-keys -t $s -H 0d" | Out-Null; Start-Sleep 4
"input box: $(InputLine)"

"--- 2: typed through 'psmux attach' over ssh -T, CR byte"
$psi = [Diagnostics.ProcessStartInfo]::new('ssh')
foreach ($a in $k[0..($k.Count - 2)] + @('-T', $k[-1], "psmux attach -t $s")) { $psi.ArgumentList.Add($a) }
$psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false
$p = [Diagnostics.Process]::Start($psi)
$null = $p.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
$in = $p.StandardInput.BaseStream
Start-Sleep 3
$b = [Text.Encoding]::ASCII.GetBytes("say two"); $in.Write($b, 0, $b.Length); $in.Flush(); Start-Sleep 1
$in.Write([byte[]](13), 0, 1); $in.Flush(); Start-Sleep 6
"input box: $(InputLine)"
$p.Kill()
Start-Sleep 2
"--- screen tail"
(Remote "psmux capture-pane -p -t $s") | Where-Object { $_.Trim() } | Select-Object -Last 12
Remote "psmux send-keys -t $s Escape" | Out-Null
Remote "psmux kill-session -t $s" | Out-Null
