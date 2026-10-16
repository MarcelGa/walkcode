#Requires -RunAsAdministrator
<#
  Walkcode: enable Windows OpenSSH Server on this workstation (key-only login).

  What it does:
    1. Sets the sshd service to start automatically and starts it.
    2. Disables password login (PasswordAuthentication no).
    3. Installs the given public keys into administrators_authorized_keys
       (your account is in the Administrators group, so sshd reads keys from
       there, not from ~/.ssh/authorized_keys) with the ACL sshd requires.
    4. Restarts sshd so the config takes effect.
  It does not change the default SSH shell or any firewall rule
  (OpenSSH-Server-In-TCP already allows port 22 on Private networks).

  Usage (elevated):
    pwsh -File tools\windows-host\setup-sshd.ps1 -PublicKeyFile $HOME\.ssh\walkcode_test.pub
#>
param(
    [Parameter(Mandatory)] [string[]] $PublicKeyFile
)
$ErrorActionPreference = 'Stop'

$sshDir = Join-Path $env:ProgramData 'ssh'
$config = Join-Path $sshDir 'sshd_config'
$adminKeys = Join-Path $sshDir 'administrators_authorized_keys'

# 1. Service
Set-Service sshd -StartupType Automatic
Start-Service sshd   # first start generates host keys if missing

# 2. Key-only login
$text = Get-Content $config -Raw
if ($text -match '(?m)^\s*#?\s*PasswordAuthentication\s+\S+') {
    $text = $text -replace '(?m)^\s*#?\s*PasswordAuthentication\s+\S+', 'PasswordAuthentication no'
} else {
    $text = "PasswordAuthentication no`r`n" + $text
}
if ($text -match '(?m)^\s*#?\s*KbdInteractiveAuthentication\s+\S+') {
    $text = $text -replace '(?m)^\s*#?\s*KbdInteractiveAuthentication\s+\S+', 'KbdInteractiveAuthentication no'
} else {
    $text = "KbdInteractiveAuthentication no`r`n" + $text
}
Set-Content $config $text -NoNewline -Encoding ascii

# 3. Authorized keys (append, no duplicates)
# Always build real arrays: a one-line file otherwise comes back as a single string,
# and "string + string" glues keys onto one line (sshd then sees only the first key).
$existing = [System.Collections.Generic.List[string]]::new()
if (Test-Path $adminKeys) {
    # Repair lines glued by earlier versions of this script: split before every key type
    # that directly follows non-whitespace (e.g. "...walkcode-testssh-ed25519 AAAA...").
    $raw = Get-Content $adminKeys -Raw
    $raw = [regex]::Replace($raw, '(?<=\S)(?=(ssh-(ed25519|rsa|dss)|ecdsa-sha2-nistp\d+|sk-[a-z0-9-]+@openssh\.com) AAAA)', "`n")
    foreach ($l in ($raw -split "`r?`n")) { if ($l.Trim()) { $existing.Add($l.Trim()) } }
}
foreach ($f in $PublicKeyFile) { $existing.Add((Get-Content $f -Raw).Trim()) }
# De-duplicate on the key blob, keeping the first comment seen.
$seen = @{}
$merged = foreach ($l in $existing) {
    $blob = ($l -split '\s+')[1]
    if ($blob -and -not $seen.ContainsKey($blob)) { $seen[$blob] = $true; $l }
}
$merged = @($merged)
Set-Content $adminKeys $merged -Encoding ascii
icacls.exe $adminKeys /inheritance:r /grant 'Administrators:F' /grant 'SYSTEM:F' | Out-Null

# 4. Apply
Restart-Service sshd
Get-Service sshd | Format-List Name, Status, StartType
"Authorized keys: $($merged.Count)"
