# Simulated interactive CLI agent (Windows) for Walkcode end-to-end tests.
# Mirrors tools/testhost/fake-agent: sets the console title, shows a spinner
# while "working", then rings the bell and waits for the next prompt.
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $AgentArgs)

$name = if ($env:FAKE_AGENT_NAME) { $env:FAKE_AGENT_NAME } else { 'fake-agent' }
$esc = [char]27; $bel = [char]7
function Set-Title([string] $t) { [Console]::Write("$esc]2;$t$bel") }

Set-Title $name
Write-Host "$name started in $((Get-Location).Path)"
if ($AgentArgs) { Write-Host "args: $($AgentArgs -join ' ')" }
Write-Host 'Type a prompt, or /exit to quit.'

$frames = '|', '/', '-', '\'
while ($true) {
    [Console]::Write("`n> ")
    $line = [Console]::ReadLine()
    if ($null -eq $line -or $line -eq '/exit') { break }
    if ($line -eq '') { continue }
    for ($i = 1; $i -le 25; $i++) {
        $f = $frames[$i % 4]
        [Console]::Write("`r$f working on: $line")
        Set-Title "$f $name"
        Start-Sleep -Milliseconds 200
    }
    [Console]::Write("`r$esc[Kdone: $line`n")
    Set-Title $name
    [Console]::Write("Waiting for input.$bel")
}
