# Run the Walkcode host poller against a host for N seconds while driving a fake agent turn.
#   pwsh -File tools\testhost\experiments\poller-run.ps1 -Target vm|docker|windows [-Seconds 25]
param([Parameter(Mandatory)] [ValidateSet('vm', 'docker', 'windows')] [string] $Target, [int] $Seconds = 25)
$root = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$script:RequiredLocalEnv = if ($Target -eq 'vm') { @("WALKCODE_VM_HOST", "WALKCODE_VM_USER") } else { @() }; . "$PSScriptRoot\..\..\Import-LocalEnv.ps1"
$k = @('-i', "$env:USERPROFILE\.ssh\walkcode_test", '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
       '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts")
switch ($Target) {
    'vm'      { $dest = @("${env:WALKCODE_VM_USER}@${env:WALKCODE_VM_HOST}"); $mux = 'tmux' }
    'docker'  { $dest = @('-p', '2222', 'dev@localhost'); $mux = 'tmux' }
    'windows' { $dest = @("$env:USERNAME@localhost"); $mux = 'psmux' }
}
function Remote([string] $cmd) { ssh @k @dest $cmd }

if ($mux -eq 'tmux') {
    $b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("$root\app\src\main\assets\poller\poll.sh"))
    $pollCmd = "printf %s '$b64' | base64 -d | bash -s -- 1 3 10"
    $agent = if ($Target -eq 'vm') { 'bash /tmp/walkcode-fake-agent' } else { 'fake-agent' }
    Remote 'tmux kill-session -t wc-poll 2>/dev/null; tmux new-session -d -s wc-poll -x 80 -y 24' | Out-Null
} else {
    $script = Get-Content "$root\app\src\main\assets\poller\poll.ps1" -Raw
    $enc = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes("& {`n$script`n} 1 3 10"))
    $pollCmd = "pwsh -NoLogo -NoProfile -NonInteractive -EncodedCommand $enc"
    "encoded command length: $($pollCmd.Length)"
    $agent = "pwsh -NoProfile -File $root\tools\testhost\fake-agent.ps1"
    Remote "psmux kill-session -t wc-poll 2>nul & psmux new-session -d -s wc-poll -x 80 -y 24 pwsh -NoLogo -NoExit -File %USERPROFILE%\.config\walkcode\shell-init.ps1" | Out-Null
}
Start-Sleep 3
Remote "$mux send-keys -t wc-poll `"$agent`" Enter" | Out-Null

$outFile = "$env:TEMP\opencode\poller-$Target.out"
$argList = @($k + $dest | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }) + "`"$pollCmd`""
$p = Start-Process ssh -ArgumentList $argList -RedirectStandardOutput $outFile -RedirectStandardError "$outFile.err" -PassThru -WindowStyle Hidden
Start-Sleep 6
Remote "$mux send-keys -t wc-poll `"task one`" Enter" | Out-Null
$sent = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
Start-Sleep ($Seconds - 6)
Stop-Process $p -Force; Start-Sleep 1
"sent 'task one' at host-relative t=0"
foreach ($l in Get-Content $outFile) {
    $f = $l -split "`t"
    '{0,4}s  {1}' -f ([long]$f[1] - $sent), (($f | Select-Object -Skip 2) -join ' | ') -replace '^', "$($f[0]) "
}
"bytes: $((Get-Item $outFile).Length)   stderr: $((Get-Content "$outFile.err" -Raw) -replace '\s+$','')"
Remote "$mux kill-session -t wc-poll" 2>$null | Out-Null
