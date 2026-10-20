# Loads tools/local.env (KEY=VALUE per line, '#' comments; gitignored) into the process
# environment so the scripts need no personal hosts, users or addresses in git. A variable
# that is already set wins, so CI or a one-off override still works.
# See tools/local.env.example for the keys.
$localEnv = Join-Path $PSScriptRoot 'local.env'
if (Test-Path $localEnv) {
    foreach ($line in Get-Content $localEnv) {
        if ($line -match '^\s*([A-Za-z_]\w*)\s*=\s*(.*?)\s*$' -and $line -notmatch '^\s*#') {
            if (-not [Environment]::GetEnvironmentVariable($Matches[1])) { [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2]) }
        }
    }
}
foreach ($required in $script:RequiredLocalEnv) {
    if (-not [Environment]::GetEnvironmentVariable($required)) { throw "Missing ${required}: copy tools\local.env.example to tools\local.env and fill it in." }
}
