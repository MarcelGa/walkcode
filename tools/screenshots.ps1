# Regenerates the README screenshots (docs/img/*.png) on the emulator against the Docker
# test host. Needs the state left by `tools\spikes\m2-ui.ps1` (host "docker", project "Work",
# workspace "Demo", one session), so run that first. The terminal shows a scripted demo
# conversation, not a real agent. The walk-mode shot asks the voice assistant about it, so the app
# needs an OpenAI key with credit (one short request).
#   pwsh -File tools\screenshots.ps1
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot\..").Path
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$env:ANDROID_SERIAL = 'emulator-5554'   # a phone on wireless debugging would make adb ambiguous
$out = "$root\docs\img"
New-Item -ItemType Directory -Force $out | Out-Null

function Dump { & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null; [xml]((& $adb shell cat /sdcard/ui.xml) -join '') }
function Tap([string] $t, [switch] $Prefix) {
    for ($i = 0; $i -lt 8; $i++) {
        $n = (Dump).SelectNodes('//node') | Where-Object { if ($Prefix) { $_.text -like "$t*" } else { $_.text -eq $t } } | Select-Object -First 1
        if ($n) {
            $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
            & $adb shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
            Start-Sleep -Milliseconds 900
            return
        }
        Start-Sleep 1
    }
    throw "UI node '$t' not found"
}
function Hide-Keyboard {
    if ((& $adb shell dumpsys input_method) -match 'mInputShown=true') { & $adb shell input keyevent 4; Start-Sleep -Milliseconds 600 }
}
function Shot([string] $name) { & $adb shell screencap -p /sdcard/shot.png; & $adb pull /sdcard/shot.png "$out\$name.png" | Out-Null }
function Host-Run([string] $c) { docker exec -u dev walkcode-testhost bash -lc $c }

# Scripted conversation, drawn the way a CLI agent would.
Host-Run @'
cat > /tmp/demo.txt <<'EOT'
> add retry with backoff to the upload
  client and cover it with a test

● I'll add exponential backoff to
  UploadClient and a failing-network test.

● Update(src/upload/client.ts)
  ⎿ Added 14 lines, removed 2 lines

● Update(src/upload/client.test.ts)
  ⎿ Added 22 lines

● Done. Uploads now retry three times
  with exponential backoff. The new test
  simulates two network failures and
  passes. All 42 tests are green.

>
EOT
'@ | Out-Null

& $adb shell am force-stop dev.walkcode.app
& $adb shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 4
Tap 'Work'; Tap 'Demo'
# The session row shows the agent's title once it has one, else "#1".
if ((Dump).SelectNodes('//node') | Where-Object { $_.text -eq 'testhost' }) { Tap 'testhost' } else { Tap '#1' }
Start-Sleep 4
Host-Run 'tmux send-keys -t wc-demo-1 -l "clear; cat /tmp/demo.txt"; tmux send-keys -t wc-demo-1 Enter' | Out-Null
Start-Sleep 2
Hide-Keyboard
Shot 'terminal'

# Walk mode: needs an OpenAI key with credit saved in the app (tools\spikes\voice-talk.ps1 saves one).
1..3 | ForEach-Object { & $adb shell input keyevent 4; Start-Sleep -Milliseconds 700 }
& $adb logcat -c
Tap 'Walk: off'
# Only on the cheapest model (voice-talk.ps1 picks it in Settings > Voice); stop before the first request otherwise.
Start-Sleep 2
$model = [regex]::Match(((& $adb logcat -d -s WalkcodeVoice:V) -join "`n"), 'start: .* model (\S+),').Groups[1].Value
if ($model -ne 'gpt-realtime-2.1-mini') { Tap 'Walk: on'; throw "walk mode started on '$model'; run tools\spikes\voice-talk.ps1 first" }
Tap 'Walk mode: listening' -Prefix
Tap 'Type instead of speaking'
& $adb shell input text ('What did the agent in Demo just finish?' -replace ' ', '%s')
& $adb shell input keyevent 66
# The answer follows the function results: wait for the second finished response, then its audio.
$deadline = (Get-Date).AddSeconds(45)
while ((Get-Date) -lt $deadline -and ([regex]::Matches(((& $adb logcat -d -s WalkcodeVoice:V) -join "`n"), 'event response\.done')).Count -lt 2) { Start-Sleep 1 }
Start-Sleep 3
Hide-Keyboard
Shot 'walk-mode'
Tap 'Stop walk mode'
Tap 'Walk mode'   # the top bar's back arrow and title

# Settings > Voice: dictation and the voice assistant. The real key's masked tail must not end up
# in a public image, so a placeholder replaces it first (voice-talk.ps1 saves the real one again).
Tap 'Settings'
Tap 'Dictation and walk mode'
& $adb shell input swipe 540 1900 540 900 400
Start-Sleep 1
Tap 'Replace key'
& $adb shell input text 'sk-proj-placeholder-not-a-real-key-a1b2'
& $adb shell input keyevent 66
Start-Sleep 1
Hide-Keyboard
# Slow (no fling), so the models, voice, key and the usage totals end up in one screen.
& $adb shell input swipe 540 1900 540 1400 1000
Start-Sleep 1
Shot 'settings-voice'
Write-Output "screenshots: $out"
