# Walkcode host poller (psmux on Windows). Same protocol as poll.sh; see there.
# Usage: & { <this script> } INTERVAL IDLE_GRACE HEARTBEAT
param([int] $Interval = 2, [int] $Grace = 4, [int] $Heartbeat = 30)
$ErrorActionPreference = 'Continue'
$sha = [Security.Cryptography.SHA1]::Create()
$fmt = "#{session_name}:#{window_index}.#{pane_index}`t#{session_name}`t#{?@wc_bell,#{@wc_bell},0}`t#{?pane_dead,1,0}`t#{?pane_current_command,#{pane_current_command},-}`t#{pane_title}"
$hash = @{}; $chg = @{}; $state = @{}; $key = @{}
$out = [Console]::OpenStandardOutput()
$enc = [Text.UTF8Encoding]::new($false)
function Emit([string] $s) {
    try { $b = $enc.GetBytes($s + "`n"); $out.Write($b, 0, $b.Length); $out.Flush() } catch { exit 0 }
}
function Now { [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
$first = $true; $lastHb = 0
while ($true) {
    $now = Now
    $seen = @{}
    foreach ($line in @(psmux list-panes -a -F $fmt 2>$null)) {
        $f = "$line" -split "`t", 6
        if ($f.Count -lt 6 -or -not $f[0]) { continue }
        $tgt, $sess, $bell, $dead, $cmd, $title = $f
        if (-not $bell) { $bell = '0' }
        $seen[$tgt] = $true
        $text = (psmux capture-pane -p -t $tgt 2>$null) -join "`n"
        $h = [BitConverter]::ToString($sha.ComputeHash($enc.GetBytes($text)), 0, 6).Replace('-', '').ToLower()
        $k = "$bell|$dead|$cmd"
        $changed = $false
        if (-not $hash.ContainsKey($tgt)) {
            $hash[$tgt] = $h; $chg[$tgt] = 0; $state[$tgt] = 'idle'; $key[$tgt] = $k; $changed = $true
        } else {
            if ($h -ne $hash[$tgt]) {
                $hash[$tgt] = $h; $chg[$tgt] = $now
                if ($state[$tgt] -eq 'idle') { $state[$tgt] = 'active'; $changed = $true }
            } elseif ($state[$tgt] -eq 'active' -and ($now - $chg[$tgt]) -ge $Grace) {
                $state[$tgt] = 'idle'; $changed = $true
            }
            if ($k -ne $key[$tgt]) { $key[$tgt] = $k; $changed = $true }
        }
        if ($changed) { Emit "S`t$now`t$tgt`t$sess`t$($state[$tgt])`t$($chg[$tgt])`t$h`t$bell`t$dead`t$cmd`t$title" }
    }
    foreach ($tgt in @($hash.Keys)) {
        if (-not $seen.ContainsKey($tgt)) {
            Emit "G`t$now`t$tgt"
            $hash.Remove($tgt); $chg.Remove($tgt); $state.Remove($tgt); $key.Remove($tgt)
        }
    }
    if ($first) { Emit "R`t$now"; $first = $false; $lastHb = $now }
    if (($now - $lastHb) -ge $Heartbeat) { Emit "H`t$now"; $lastHb = $now }
    Start-Sleep -Seconds $Interval
}
