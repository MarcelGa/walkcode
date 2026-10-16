# Does real Claude Code ring the bell when it runs in a psmux session started over SSH (as Walkcode
# starts it) with no client attached? Uses preferredNotifChannel=terminal_bell for this run only.
#   pwsh -File tools\testhost\experiments\claude-bell.ps1
param([switch] $SkipIdle, [switch] $FocusOff)
$ErrorActionPreference = 'Continue'
$k = @('-i', "$env:USERPROFILE\.ssh\walkcode_test", '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
       '-o', "UserKnownHostsFile=$env:USERPROFILE\.ssh\walkcode_test_known_hosts", "$env:USERNAME@localhost")
$s = 'wc-claude'
$dir = "$env:TEMP\opencode\claude-bell"
New-Item -ItemType Directory -Force $dir | Out-Null
$settings = "$dir\walkcode-settings.json"
'{"preferredNotifChannel":"terminal_bell"}' | Set-Content $settings -Encoding ascii
$t0 = Get-Date
function T { '{0,5:N0}s' -f ((Get-Date) - $t0).TotalSeconds }
function Remote([string] $c) { ssh @k $c }
function Bell { (Remote "psmux list-windows -t $s -F `"#{?@wc_bell,#{@wc_bell},0}|#{session_attached}`"") -join '' }
function Screen([int] $n = 8) { (Remote "psmux capture-pane -p -t $s") | Where-Object { $_.Trim() } | Select-Object -Last $n }
# psmux's named Enter/C-m reach Claude on Windows as a newline; a raw CR byte submits.
function Submit([string] $text) { Remote "psmux send-keys -t $s -l `"$text`"" | Out-Null; Start-Sleep 1; Remote "psmux send-keys -t $s -H 0d" | Out-Null }
function Show([string] $label) { "$(T) [$label] bell|attached=$(Bell)"; Screen | ForEach-Object { "        | $_" } }

Remote "psmux kill-session -t $s 2>nul & psmux new-session -d -s $s -c $dir -x 110 -y 32 pwsh -NoLogo -NoExit -File %USERPROFILE%\.config\walkcode\shell-init.ps1" | Out-Null
if ($FocusOff) { Remote "psmux set -g -t $s focus-events off" | Out-Null; "focus-events: $(Remote "psmux show -gv -t $s focus-events")" }
Start-Sleep 4
Remote "psmux send-keys -t $s `"claude --model haiku --permission-mode default --settings $settings`" Enter" | Out-Null
Start-Sleep 12
Show 'started'
# First run in a new folder asks to trust it; the default is "No, exit", so pick "Yes".
if ((Screen 30) -join "`n" -match '(?i)trust this folder') { Remote "psmux send-keys -t $s Down Enter" | Out-Null; Start-Sleep 8; Show 'trusted' }
if ((Screen 40) -join "`n" -match 'is not recognized|claude-bell \S* \S* ERROR') { 'Claude is not running; aborting.'; Remote "psmux kill-session -t $s" | Out-Null; exit 1 }

"=== A: permission prompt (expect bell ~6 s after the prompt appears)"
$b0 = Bell
Submit "Create a file named bell.txt containing the word hi. Use the Write tool."
foreach ($w in 8, 8, 8) { Start-Sleep $w; Show 'waiting on permission' }
$bA = Bell
Remote "psmux send-keys -t $s Escape" | Out-Null
Start-Sleep 4

if (-not $SkipIdle) {
"=== B: idle after a reply (expect bell ~60 s after it finishes)"
Submit "Reply with just the word OK."
foreach ($w in 15, 25, 25, 20) { Start-Sleep $w; "$(T) bell|attached=$(Bell)" }
Show 'after idle wait'
$bB = Bell
}

"=== summary: before=$b0 afterPermission=$bA afterIdle=$bB"
Remote "psmux send-keys -t $s `"/exit`" Enter" | Out-Null
Start-Sleep 3
Remote "psmux kill-session -t $s" | Out-Null
