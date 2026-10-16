# Does Ctrl-C (0x03) reach a psmux pane through `psmux attach` over SSH? Run from repo root.
param([switch] $Pty, [string] $Shell, [string] $Run = "ping -t 127.0.0.1")
$k = @('-i', "$env:USERPROFILE\.ssh\walkcode_test", '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
       '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts")
$px = "$env:LOCALAPPDATA\Programs\psmux\psmux.exe"
function Cap { (& $px capture-pane -p -t cc | Where-Object { $_.Trim() -and $_ -notmatch 'Kubernetes|Subscription' } | Select-Object -Last 3) -join ' | ' }

& $px kill-session -t cc 2>$null
if ($Shell) { & $px new-session -d -s cc -x 100 -y 30 $Shell } else { & $px new-session -d -s cc -x 100 -y 30 }
Start-Sleep 4

$psi = [Diagnostics.ProcessStartInfo]::new('ssh')
foreach ($a in $k) { $psi.ArgumentList.Add($a) }
$psi.ArgumentList.Add($(if ($Pty) { '-tt' } else { '-T' }))
$psi.ArgumentList.Add("$env:USERNAME@localhost")
$psi.ArgumentList.Add('psmux attach -t cc')
$psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false
$p = [Diagnostics.Process]::Start($psi)
$null = $p.StandardOutput.BaseStream.CopyToAsync([IO.Stream]::Null)
$in = $p.StandardInput.BaseStream
function Send([byte[]] $b) { $in.Write($b, 0, $b.Length); $in.Flush() }
Start-Sleep 3
Send ([Text.Encoding]::ASCII.GetBytes("$Run`r"))
Start-Sleep 3
"before ^C: $(Cap)"
Send ([byte[]](3))
Start-Sleep 3
"after ^C:  $(Cap)"
$p.Kill()
& $px kill-session -t cc
