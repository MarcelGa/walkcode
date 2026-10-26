# Walk mode end to end on the emulator: saves the OpenAI key through Settings, switches walk mode
# on from the Projects screen, types a question about the workspaces and checks that the reply came
# after a workspace function ran, with audio. The emulator mic is silent, so typing stands in for speech.
# Needs OPENAI_API_KEY in tools\local.env; the key is typed into the app and never printed.
# The emulator must have audio: start it without -no-audio (this script does, if none is running).
#   pwsh -File tools\spikes\voice-talk.ps1
$ErrorActionPreference = 'Stop'
$script:RequiredLocalEnv = @("OPENAI_API_KEY"); . "$PSScriptRoot\..\Import-LocalEnv.ps1"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$adb = "$sdk\platform-tools\adb.exe"
$serial = 'emulator-5554'   # the phone may be attached too
$out = "$root\build\voice"
# RealtimeProtocol.CHEAPEST_MODEL and its label in Settings > Voice.
$cheapModel = 'gpt-realtime-2.1-mini'
$cheapLabel = 'GPT Realtime 2.1 mini'
New-Item -ItemType Directory -Force $out | Out-Null

function A { & $adb -s $serial @args }
function Dump {
    A shell uiautomator dump /sdcard/ui.xml | Out-Null
    $local = Join-Path $env:TEMP 'wc-ui.xml'
    A pull /sdcard/ui.xml $local | Out-Null
    [xml](Get-Content -Raw -Encoding UTF8 $local)
}
function Texts { @((Dump).SelectNodes('//node') | ForEach-Object { $_.text } | Where-Object { $_ }) }
function Node([scriptblock] $match) { @((Dump).SelectNodes('//node') | Where-Object { $_.text -and (& $match $_.text) }) | Select-Object -First 1 }
function Wait-Node([scriptblock] $match, [int] $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $n = Node $match
        if ($n) { return $n }
        Start-Sleep 1
    }
    $null
}
function Tap-Node($n) {
    $b = [regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    A shell input tap ([int](($b[0] + $b[2]) / 2)) ([int](($b[1] + $b[3]) / 2))
    Start-Sleep -Milliseconds 800
}
function Tap([string] $t) {
    $n = Wait-Node { param($x) $x -eq $t } 8
    if (-not $n) { throw "UI node '$t' not found" }
    Tap-Node $n
}
# Small upward swipes until the text is on screen (Settings is longer than one screen).
function Scroll-To([string] $t) {
    $last = -2
    for ($i = 0; $i -lt 12; $i++) {
        $n = Node { param($x) $x -eq $t }
        $y = if ($n) { [int]([regex]::Matches($n.bounds, '\d+')[1].Value) } else { -1 }
        if ($y -gt 250 -and $y -lt 2100) { return }
        # Low on screen but the page cannot scroll further: it is as visible as it gets.
        if ($y -gt 0 -and $y -eq $last) { return }
        $last = $y
        A shell input swipe 540 1700 540 1200 300
        Start-Sleep -Milliseconds 600
    }
    throw "UI node '$t' not found after scrolling"
}
# On the device, then pulled: PowerShell redirection would mangle the PNG bytes.
function Shot([string] $name) { A shell screencap -p /sdcard/wc-shot.png; A pull /sdcard/wc-shot.png "$out\$name.png" | Out-Null }
function Type-Text([string] $s) { A shell input text ($s -replace ' ', '%s') }
# First capture of [pattern] in the voice log, or $null after [seconds].
function Wait-Log([string] $pattern, [int] $seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $m = [regex]::Match(((A logcat -d -s WalkcodeVoice:V) -join "`n"), $pattern)
        if ($m.Success) { return $m.Groups[1].Value }
        Start-Sleep 1
    }
    $null
}
function Fail([string] $why) {
    Shot fail
    "--- screen:"; Texts
    "--- voice log:"; A logcat -d -s WalkcodeVoice:V
    throw "FAIL: $why (screenshot: $out\fail.png)"
}

if (-not ((& $adb devices) -match "$serial\s+device")) {
    Start-Process "$sdk\emulator\emulator.exe" -ArgumentList '-avd', 'Phone_PlayStore_API35', '-no-window', '-no-snapshot-save', '-no-boot-anim' -WindowStyle Hidden
    A wait-for-device
    while ((A shell getprop sys.boot_completed 2>$null) -notmatch '1') { Start-Sleep 3 }
}

A install -r -t "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
A shell pm grant dev.walkcode.app android.permission.RECORD_AUDIO
A shell am force-stop dev.walkcode.app
A logcat -c
A shell am start -n dev.walkcode.app/.MainActivity | Out-Null
Start-Sleep 4

# Key through the real Settings UI, so the paste cleaning and storage are exercised too.
Tap 'Settings'
Tap 'Dictation and walk mode'
# The cheapest model, so test runs cost little; checked again in the log when walk mode starts.
Scroll-To $cheapLabel
Tap $cheapLabel
Scroll-To 'Save key'
$field = Wait-Node { param($x) $x -eq 'Paste key' -or $x -eq 'Replace key' } 5
if (-not $field) { Fail 'API key field not found' }
Tap-Node $field
Type-Text $env:OPENAI_API_KEY
A shell input keyevent 66   # the keyboard's Done key saves (the Save button is behind the keyboard)
# Same masking as OpenAiKey.masked, so an older saved key cannot pass for this one.
$k = $env:OPENAI_API_KEY.Trim()
$saved = "Saved: " + $k.Substring(0, $(if ($k.StartsWith('sk-proj-')) { 8 } else { 3 })) + [char]0x2026 + $k.Substring($k.Length - 4)
if (-not (Wait-Node { param($x) $x.StartsWith($saved) } 5)) { Fail 'key was not saved' }

# Walk mode is switched on in the Projects top bar; the strip under it opens the conversation.
# Settings > Voice has no tab bar: back out to it first.
for ($i = 0; $i -lt 4 -and -not (Node { param($x) $x -eq 'Projects' }); $i++) { A shell input keyevent 4; Start-Sleep 1 }
Tap 'Projects'
Tap 'Walk: off'
# Never let a test talk to a pricier model: stop before the first request if the pick did not stick.
$started = Wait-Log 'start: .* model (\S+),' 10
if ($started -ne $cheapModel) {
    Tap 'Walk: on'
    Fail "walk mode started on '$started', not $cheapModel"
}
$strip = Wait-Node { param($x) $x.StartsWith('Walk mode: listening') } 20
if (-not $strip) { Fail 'walk mode did not go live' }
Tap-Node $strip
if (-not (Wait-Node { param($x) $x.StartsWith('Listening') } 5)) { Fail 'walk screen not shown' }

# A question that needs the workspace functions; the reply must come after at least one ran.
$prompt = 'What is going on in my workspaces? Is any agent waiting for me?'
$box = Wait-Node { param($x) $x -eq 'Type instead of speaking' } 5
if (-not $box) { Fail 'message field not found' }
Tap-Node $box
Type-Text $prompt
A shell input keyevent 66   # the keyboard's Send key
$reply = Wait-Node { param($x) $x.StartsWith('Walkcode: ') -and $x.Length -gt 12 } 40
if (-not $reply) { Fail 'no reply transcript within 40 s' }
# The first reply is often a preamble ("let me check"); the answer follows the function results.
$deadline = (Get-Date).AddSeconds(40)
while ((Get-Date) -lt $deadline -and ([regex]::Matches(((A logcat -d -s WalkcodeVoice:V) -join "`n"), 'event response\.done')).Count -lt 2) { Start-Sleep 1 }
Start-Sleep 4   # let the rest of the audio arrive
$reply = @((Dump).SelectNodes('//node') | Where-Object { $_.text -and $_.text.StartsWith('Walkcode: ') }) | Select-Object -Last 1
$log = (A logcat -d -s WalkcodeVoice:V) -join "`n"
$audio = ([regex]::Matches($log, 'event response\.output_audio\.delta')).Count
$tools = ([regex]::Matches($log, 'tool (\w+)') | ForEach-Object { $_.Groups[1].Value }) -join ', '
$usage = @([regex]::Matches($log, 'usage (\S+) tokens=(\d+) .* usd=(\S+)') | ForEach-Object { "$($_.Groups[1].Value) $($_.Groups[2].Value) tokens $($_.Groups[3].Value)" })
$spent = Node { param($x) $x.StartsWith('This conversation: ') }
Shot talk
Tap 'Stop walk mode'

"reply: $($reply.text)"
"functions run: $tools"
"audio deltas: $audio"
"usage per response: $($usage -join '; ')"
"walk screen: $($spent.text)"
if (-not $tools) { Fail 'the reply came without any workspace function' }
if ($audio -lt 1) { Fail 'transcript arrived but no audio deltas' }
if ($usage.Count -lt 1) { Fail 'no usage reported by response.done' }
if (-not $spent) { Fail 'walk screen does not show the conversation cost' }

# The totals in Settings > Voice include this run.
for ($i = 0; $i -lt 4 -and -not (Node { param($x) $x -eq 'Settings' }); $i++) { A shell input keyevent 4; Start-Sleep 1 }
Tap 'Settings'
Tap 'Dictation and walk mode'
Scroll-To 'Today'
$today = Node { param($x) $x -eq 'Today' }
if (-not (Wait-Node { param($x) $x -match '^\d+(\.\d)?[kM]? tokens' -and $x -notmatch '^0 tokens' } 5)) { Fail 'usage totals not shown in Settings' }
Shot usage
"settings usage: $((Texts | Where-Object { $_ -match 'tokens|^\$' }) -join ' | ')"
"PASS (screenshot: $out\talk.png)"
