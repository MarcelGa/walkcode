# Install Walkcode tmux settings for psmux on Windows (what the app will do at host setup).
# Also installs ~/.config/walkcode/shell-init.ps1; Walkcode starts session shells on Windows as
#   pwsh -NoLogo -NoExit -File ~/.config/walkcode/shell-init.ps1
# Usage: pwsh -File install-tmux-conf.ps1 -ConfigFile walkcode.tmux.conf
param([Parameter(Mandatory)] [string] $ConfigFile)
$ErrorActionPreference = 'Stop'
$dir = Join-Path $HOME '.config\walkcode'
New-Item -ItemType Directory -Force $dir | Out-Null
$target = Join-Path $dir 'tmux.conf'
(Get-Content $ConfigFile -Raw) -replace "`r`n", "`n" | Set-Content $target -NoNewline -Encoding utf8NoBOM
$rc = Join-Path $HOME '.tmux.conf'
# psmux does not support `source-file -q`; the file exists because we just wrote it.
$line = 'source-file ~/.config/walkcode/tmux.conf'
$ours = '# Walkcode', $line, 'source-file -q ~/.config/walkcode/tmux.conf'
$kept = [System.Collections.Generic.List[string]]::new()
if (Test-Path $rc) { Get-Content $rc | Where-Object { $_ -notin $ours } | ForEach-Object { $kept.Add($_) } }
while ($kept.Count -gt 0 -and -not $kept[$kept.Count - 1].Trim()) { $kept.RemoveAt($kept.Count - 1) }
$block = @('# Walkcode', $line)
$out = if ($kept.Count) { @($kept) + '' + $block } else { $block }
Set-Content $rc $out -Encoding utf8NoBOM
# Shell init that re-enables Ctrl+C in sessions started over SSH (see walkcode-shell-init.ps1).
Copy-Item (Join-Path $PSScriptRoot 'walkcode-shell-init.ps1') (Join-Path $dir 'shell-init.ps1') -Force
if ((Get-Command psmux -ErrorAction SilentlyContinue) -and (psmux ls 2>$null)) { psmux source-file $target }
'installed'
