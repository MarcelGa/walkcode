# Walkcode progress

Single source of truth for milestone status and the next steps. Update this file
at the end of each work session.

## Milestones

| # | Scope | State |
|---|-------|-------|
| S1 | sshj + Android Keystore Ed25519 login over SSH | done (`168f9f5` era, `2fd60cf`) |
| S2 | Vendored Termux terminal attached to tmux/psmux | done (`168f9f5`) |
| S3 | Host poller → unread / quiet / attention | done (`6f5dfd1`) |
| **M1** | Data layer + Keystore key + TOFU + LAN/tailnet connect | **done (`fb1766f`)** |
| M2 | UI: projects, workspaces, session list, launcher, terminal | **done** |
| M3 | Unread/foreground service/alerts, reconnect, "paused" notice | **done** (paused notice untested, see below) |
| M4 | Labels, rename, settings, battery-optimisation prompt | **done** |

## What exists now

### SSH identity & connection (`app/src/main/java/dev/walkcode/app/ssh/`)
- `DeviceKey.kt` — Ed25519 key in the Android Keystore; `openSshPublicKey()` prints
  the `authorized_keys` line; signing via `KeystoreEd25519Signature`.
- `Ssh.kt` — `SshProviders.ensureInstalled()` (full BouncyCastle minus the Ed25519
  OID aliases) and `Ssh.client()`.
- `HostKeyTrust.kt` — `PinnedHostKey` (OpenSSH **SHA-256** fingerprint),
  `TofuHostKeyVerifier`, `HostKeyMismatchException`.
- `HostConnector.kt` — tries `Host.addresses` in order (LAN → tailnet), pins on
  first use, stops on a key mismatch. Returns `Connected(client, address, hostKey,
  newlyTrusted)`; the caller persists the pin.

### Terminal (`app/src/main/java/dev/walkcode/app/terminal/`)
- `SshTerminalTransport.kt` — PTY (tmux) and no-PTY (psmux) transport.
- `TerminalController.kt` — key row, raw carriage return for Windows submit.

### Poller (`app/src/main/java/dev/walkcode/app/poll/` + `assets/poller/`)
- `poll.sh` / `poll.ps1` — emit tab-separated transitions (`S/G/R/H`).
- `PollProtocol.kt`, `PollerCommand.kt` (inline, base64; Windows ≤ 8000 chars),
  `HostPoller.kt`, `ActivityTracker.kt` (active/attention/quiet/unread).

### Data layer (`app/src/main/java/dev/walkcode/app/data/`)
- `Entities.kt` — `Host` (with `lanHost`/`tailnetHost`, TOFU pin columns,
  `ShellKind`), `Project`, `AgentPreset` (+ `BuiltInAgents.defaults()`),
  `Workspace` (host/project/preset FKs, `slug`), `Session` (`tmuxName`, `ordinal`,
  `watched`, `lastViewedHash`).
- `Daos.kt`, `Converters.kt`, `WalkcodeDatabase.kt` (version 1, seeds built-in
  presets with raw SQL), `WalkcodeRepository.kt` (incl. `createSession()` picking
  the next ordinal), `Naming.kt` (`wc-<slug>-<id>[-N]`).

### Tests
- JVM: `poll/PollTests.kt`, `data/NamingTest.kt`, `ssh/HostKeyTrustTest.kt` (26 total).
- Instrumented: `ssh/SshSpikeTest.kt`, `poll/PollSpikeTest.kt`,
  `ssh/HostConnectorTest.kt` (pin-on-first-use, reuse, reject-changed, LAN→tailnet
  fallback, no-address).

## Verified

- `:app:testDebugUnitTest` green (26 tests).
- `tools/spikes/spike1.ps1`, `spike2.ps1`, `spike3.ps1` pass on the Docker host,
  homelab VM and Windows desktop.
- `tools/spikes/m1-connector.ps1` passes on the emulator against the Docker host.
- The computed SHA-256 fingerprint equals `ssh-keygen -lf` for the Docker host key.
- Real Claude Code (v2.1.301) on Windows rings the bell in a headless psmux session:
  ~6 s after a permission prompt, ~60 s idle. Raw CR submits input; named `Enter`
  does not. See `tools/testhost/experiments/claude-bell.ps1`, `claude-enter.ps1`.

## Next: M2 (UI)

Source of truth for the look is `mockup/index.html`. Build with Jetpack Compose,
reading from `WalkcodeRepository` (expose flows, collect in ViewModels).

1. **App shell** — `MainActivity` hosts a `NavHost`; a `WalkcodeApp` singleton
   exposes the repository (same pattern as `WalkcodeRepository.get(context)`).
2. **Hosts screen** — list/add/edit hosts (name, user, port, LAN + tailnet address,
   `ShellKind`). Show the device public key with a copy button; show the pinned
   fingerprint; offer "forget host key". On save, nothing connects yet.
3. **Projects + Workspaces** — project colour labels; workspaces grouped by
   project, showing host + agent. Add/edit/delete workspace (host picker, directory,
   agent preset picker, args).
4. **Session list / launcher** — tapping a workspace: connect via `HostConnector`,
   create a `Session` (`repository.createSession`), ensure the tmux/psmux session
   exists (create if missing) and start the agent, then attach. Support multiple
   sessions per workspace and a "resume" action using `AgentPreset.resumeCommand`.
5. **Terminal screen** — embed the vendored `terminal-view`, drive it from
   `SshTerminalTransport`/`TerminalController`, include the key row (Esc, Tab,
   Ctrl, arrows, Enter→raw CR on Windows, and a Ctrl+C that works on Windows).

Keep `mockup/index.html` open as the visual reference. Do not build M3 concerns
(alerts/foreground service) yet, but leave hooks (the `Session.watched` flag is
already persisted).

## Decisions

All settled product/design decisions live in **[DECISIONS.md](DECISIONS.md)** —
treat them as decided and do not re-ask the user about them.

## Session log

- **S1–S3**: SSH login, terminal attach, poller. Committed as `2fd60cf`, `168f9f5`,
  `6f5dfd1`.
- **This session**: real Claude Code bell test on Windows (fixed the submission path
  to a raw CR; added `focus-events` to the host tmux config). Then **M1**: Room data
  layer + KSP, SHA-256 TOFU pinning, `HostConnector` with LAN→tailnet fallback and
  mismatch reporting, unit + instrumented tests. The e2e test caught that a rejected
  host key arrives as a `TransportException` — fixed in `HostConnector`. Committed as
  `fb1766f`.

## M2 status (UI)

Implemented under `app/src/main/java/dev/walkcode/app/ui/` and `session/`:
Projects / Project detail / Workspace (session list with running/stopped state
queried from the host) / Workspace editor / Hosts / Host editor (pubkey copy, pinned
fingerprint, forget key) / Terminal screen (key row, connect/retry/reconnect overlay).
`session/MuxCommands.kt` builds the tmux/psmux commands (unit-tested);
`session/SessionService.kt` connects, persists the TOFU pin, creates the session and
starts the agent if missing, supports resume via `AgentPreset.resumeCommand`.
Navigation is a simple in-memory back stack (no navigation-compose).

**Verified:** unit tests + `assembleDebug` green; app launches on the emulator and
renders the Projects tab.
**Verified on the Docker tmux host (`tools/spikes/m2-launch.ps1`, `SessionServiceTest`):** create session in a `~` directory with spaces, agent starts, listed as running, re-launch does not restart, kill, resume-after-stop uses the resume command.
(2026-10-27) Resume is per session: `{id}` in preset commands carries `Session.agentSessionId`; verified on the
emulator against the homelab VM with real Claude, two sessions in one workspace each reopening their own conversation.
**Also verified on the Windows desktop with psmux (`tools/spikes/m2-launch-windows.ps1`):** same flow incl. `%USERPROFILE%` paths with spaces, raw-CR submit, no PTY.
**Compose UI verified end to end on the emulator (`tools/spikes/m2-ui.ps1`, Docker host):** add host -> project -> workspace -> new session -> terminal attach -> typed input reaches tmux -> workspace list shows the session as running. M2 is done. Original note: the full flow against a real host
(add host -> workspace -> new session -> terminal), esp. psmux quoting
(`send-keys` text, `%USERPROFILE%` paths) and `~` expansion for tmux. Docker host
was not running. Needs an instrumented/e2e runner like `tools/spikes/*.ps1`.
Known gaps: `markSessionViewed` is not called yet (M3); no battery/foreground bits.

## M3 status (monitoring)

Code in `app/src/main/java/dev/walkcode/app/monitor/`:
- `HostMonitor` — one per host: opens a `PollLink`, feeds `ActivityTracker`, raises alerts
  **only for watched sessions**, reconnects with backoff (2/5/10/30 s) and treats 75 s
  without any poller event as a dead link (poller heartbeats every 30 s; a stalled TCP
  connection otherwise never errors).
- `Monitor` — process singleton: reconciles one `HostMonitor` per host that has a watched
  session; exposes `activity`, `status`, `alerts`, `paused`, `setFocused`.
- `MonitorService` — `dataSync` foreground service, two channels (`agent_alerts` high,
  `connection_status` low), "Connection lost" notice after 2 failed attempts, `onTimeout`
  (Android 15 dataSync cap) stops monitoring and posts "Monitoring paused - tap to resume".
  Tapping an alert opens that session's terminal.
- UI: per-session Watch/Watching toggle (asks for POST_NOTIFICATIONS), active/quiet/unread in
  the session list, unread dot on workspaces, host link status on the Hosts tab, paused banner
  on Projects. The terminal on screen is "focused": no alerts, unread cleared on leave.
- Quiet threshold is the 15 s default (`HostMonitor(quietAfterSec)`); not configurable yet (M4).

**Verified:** `HostMonitorTest` (JVM: watched-only alerts, focused no-alert, backoff/reconnect,
quiet alert); `tools/spikes/m3-monitor.ps1` on the emulator + Docker host: service runs in the
background, bell -> "Needs your attention", output then silence -> "No output for Ns", alert
intent opens the session, frozen host (`docker pause`) -> "Connection lost" then recovery.
**Not verified:** (the dataSync `onTimeout` pause path is now verified on API 35, see below);
psmux/Windows monitoring through the service (poller itself was verified in spike 3).

## M4 status (labels, settings, battery)

- **Session labels / rename:** `Session.label` (Room schema 2, `MIGRATION_1_2`); `Session.displayName()`
  is the label else `#N`. Pencil pill on a session row renames; projects have a rename + recolour
  dialog (pencil in the project top bar); workspaces were already editable. Alert titles read
  `Project · Workspace · <label|#N>`.
- **Settings tab** (`ui/SettingsScreen.kt`, `data/AppSettings.kt`): quiet alerts on/off, quiet
  threshold (5-120 s, default 15), battery-optimisation status with an "Allow" shortcut. A changed
  threshold rebuilds the host monitors (`Monitor.restartHostMonitors`); the on/off switch is live.
- **Battery prompt:** the first time a session is watched and the app is still battery-optimised,
  a dialog offers the system "ignore battery optimisations" request (shown once).
- Fixed on the way: re-watching after un-watching the last session used to stop the service
  immediately (stale `watchedByHost` from the previous run).

**Verified:** `tools/spikes/m4-settings.ps1` (emulator + Docker host, fresh seeded database): rename
shows in the list and alert title, battery prompt appears, Settings shows Optimised then Unrestricted
after the exemption, threshold persists and a 5 s threshold fires the quiet alert in ~9-10 s (vs >=15 s
default). The 1 -> 2 migration was verified once by installing over a real v1 database (data kept, no
crash); it is not part of the script. `SessionDisplayNameTest` covers `displayName()`.
**Not verified:** granting the exemption through the real system dialog (set with `dumpsys deviceidle`
instead); Windows/psmux monitoring through the service.

## What is left

All four planned milestones are done. Remaining gaps / ideas: the Android 15 dataSync `onTimeout`
pause path and Windows monitoring through the service are untested; agent presets cannot be edited in
the UI yet (only the four built-ins).

## Config export/import (2026-10-20)

Settings -> Backup exports hosts (with pinned host keys), projects, custom agent presets, workspaces
and settings to `walkcode-config.json`, and imports one back by name (existing names are kept). No
sessions and no keys. Host editor -> Phone key: a copy-paste command that authorizes this phone's
key on the host (`ssh/AuthorizeCommand`: POSIX for tmux hosts, PowerShell for psmux hosts), and Test
connection, which reports whether the key is accepted. Release signing is documented in
[RELEASE.md](RELEASE.md).
**Verified:** JVM tests (`ConfigCodecTest`, `AuthorizeCommandTest`) and `assembleDebug`.
**Not verified:** the import/export round trip on a device, the file pickers, and the Test connection
button against a real host (run the Phone key command on the Docker test host first).

## Target: Android 15+ (Galaxy S25)

`minSdk` is 35 (the device is a Galaxy S25; Android 15, upgradable to 16). The emulator is now
`Phone_PlayStore_API35` (the API 34 AVD can no longer install the app). All acceptance scripts were
rerun on it and pass: `m1-connector`, `m2-launch`, `m2-ui`, `m3-monitor`, `m4-settings`.
New `tools/spikes/m3-timeout.ps1` shortens the dataSync limit with
`device_config put activity_manager data_sync_fgs_timeout_duration 20000` and verifies:
`onTimeout` stops the service in time (no `ForegroundServiceDidNotStopInTimeException`), the
"Monitoring paused" notice appears, and its intent resumes monitoring. A new emulator has a new
Keystore key, so run `m1-connector.ps1` once to authorize it on the Docker host.
**Not rerun on API 35:** `m2-launch-windows.ps1` (the new device key must be added to the Windows
host's `administrators_authorized_keys`, which needs an elevated shell).

## Real-device session (2026-10-19)

- Installed on the user's Galaxy S25 (Android 16). Added hosts `desktop` (Windows/psmux, LAN +
  tailnet) and `homelab-vm` (tmux) and verified a session on each from the phone. Details and the
  step-by-step are in [DEVICE-TESTING.md](DEVICE-TESTING.md).
- App changes made from that: full device key shown on the Hosts tab; Windows sessions export
  `WALKCODE_SESSION=1` before pwsh starts (commit `37d6d7c`; the profile can use it to skip
  Nerd Font prompts - the user's profile has not been edited yet); terminal-prompt adaptive launcher
  icon with a monochrome variant (`0320b48`).
- Open follow-ups: the user's PowerShell profile still draws glyph-heavy prompts in phone sessions
  (wrap oh-my-posh / ListView prediction in `if (-not $env:WALKCODE_SESSION)`); `homelab-vm` has no
  tailnet address; alerts with a real agent and the Wi-Fi <-> mobile reconnect are untested on the phone.

## Where to pick up

1. Ask the user what they hit while testing on the phone (logs: `adb logcat -s WalkcodeTerm AndroidRuntime`).
2. Candidate work: agent preset editor, import/export of hosts/workspaces, in-app log viewer,
   configurable per-session quiet/alert behaviour, tailnet address for the VM.
3. Before changing behaviour re-run the relevant `tools/spikes/*.ps1` (list in AGENTS.md).

## Walk mode (2026-10-19)

`walk/` (`ResponseExtractor`, `Speaker`, `Dictation`) + terminal bar buttons (Walk on/off, Dictate/Done,
Hush) + Settings section. `MuxCommands.capture` / `SessionService.capture` read pane history.
**Verified:** JVM tests (`ResponseExtractorTest`, `MuxCommandsTest`) and `assembleDebug`.
**Not verified on a device:** TTS output, mic permission flow, recognizer availability on the S25,
and how well the extractor filters real Claude Code / opencode screens (tune the regexes in
`ResponseExtractor` from real captures). Not built: hands-free auto-listen after a spoken reply.
Voice settings (Settings > Voice): TTS engine, voice, speed (with a Test pill), dictation recognizer and
language. Stored in `AppSettings`, null = phone default; listed from the installed engines/services via
`walk/VoiceCatalog`. Changing the engine clears the chosen voice. Same verification level as above (build
and JVM tests only).

## Cached terminal screen (2026-10-20)

Each session's last screen (emulator transcript, last 200 lines) is written to `cacheDir/screens/<id>.txt`
every 2 s while it changes and when the terminal is left. Opening the session shows that text under the
"Connecting" banner instead of an empty terminal. Killing or removing a session deletes its file.
**Verified:** `ScreenCacheTest` (JVM) and `assembleDebug`.
**Not verified on a device:** how the cached text looks next to the live terminal after attach.

## Voice orchestrator PoC (2026-10-23): step 2 (walk mode) built

Decided in [DECISIONS.md](DECISIONS.md) (phone-only, OpenAI Realtime, preset voices per workspace).
Step 1 is built: `voice/` (`RealtimeProtocol`, `RealtimeClient`, `VoiceSession`, `VoiceAudio`, `AudioRoute`,
`OpenAiKey`), `ui/TalkScreen.kt` (status, red error line, "type instead of speaking"), key (masked, Done saves)
and voice in Settings. JVM tests: `RealtimeProtocolTest`, `OpenAiKeyTest`.
**Verified on the emulator against the live API (2026-10-25):** with an invalid key the socket opens, then an
`error` event (`invalid_api_key`) arrives and the server closes; Talk now shows "Disconnected." with the
message in red instead of staying "Live". `tools/spikes/voice-talk.ps1` drives Settings and Talk end to end.
**Not verified yet:** a conversation with a valid key (needs `OPENAI_API_KEY` in `tools/local.env`), so the
session settings (`gpt-realtime-2`, `gpt-4o-mini-transcribe` input transcription) are still unconfirmed; real
speech, echo cancellation and speaker/headset routing on the S25.
Phone finding: the first pasted key held a line break as its 7th character (likely a label line, e.g.
"OpenAI", above the key); the next build glued the lines into one invalid key, and OpenAI rejected it with the
"Incorrect API key … platform.openai.com/account/api-keys" message. `OpenAiKey.clean` now keeps the `sk-` token
and also repairs a glued value on read (confirmed on the phone: stored 171 chars = 6-char label + line break +
164-char `sk-proj-` key). With the cleaned key OpenAI accepts the key but refuses the session:
`credit_balance_exhausted` ("You have no credits remaining"), so the account needs API credit before step 1
can be verified. Talk runs only while its screen is open (no foreground service yet).
Voice picker (2026-10-26): `ui/VoicePicker.kt` (`RealtimeVoicePicker`) is a dropdown of all ten Realtime
preset voices with a play button on the field and on each entry; the sample says "Hey, this is voice agent
<name>" via the speech endpoint (`voice/VoicePreview`, `gpt-4o-mini-tts`, raw 24 kHz PCM, cached in memory
and, since 2026-10-26, as files in `files/voice-samples/` keyed by model, voice and text, so each sample is paid
for once; verified on the emulator: one request, then none after an app restart).
It takes an `assistantName` and a "use default" entry (`noneLabel`/`noneVoice`) so per-workspace voices can
reuse it. Settings uses it for the default voice. **Verified:** `VoicePreviewTest`, and on the emulator the
menu, the play button and the request (HTTP 429 "no credits remaining", shown under the field).
**Not verified:** hearing a sample (needs API credit).
Step 1 verified by the user on the phone (2026-10-26): a voice conversation works with a key that has credit.
Step 2, walk mode (2026-10-26): the Walk toggle moved from the terminal bar to the Projects top bar and now
starts one app-wide assistant (`assistant/WalkAssistant`) with four functions (`assistant/WorkspaceTools`):
`list_workspaces`, `workspace_overview` (last lines of every session's screen, one SSH connection per host),
`read_session`, `propose_message` + `send_proposed` (only the proposed, read-back text can be sent). A strip under the Projects top bar shows its status and last line and opens `ui/WalkScreen.kt`
(transcript with the functions that ran, typing instead of speaking, Start/Stop). `assistant/WalkService`
keeps it listening with the screen off (microphone foreground service, Stop in the notification). The
terminal's Walk/Hush buttons, its read-aloud loop, `walk/Speaker` and the platform TTS settings are gone;
Dictate stays. Settings keeps the key, the default voice and the dictation settings.
**Verified:** JVM tests (`WorkspaceDirectoryTest`, protocol tests for tools and function calls,
`ResponseExtractorTest.screen_tail…`); `WorkspaceToolsTest` on the emulator against the Docker host
(list, nothing sent without a proposal, overview and read show the sent text); on the emulator the toggle, the strip,
the walk screen and the terminal bar (Dictate only); with a key without credit the strip shows the error and
the service is not left running.
**Verified with the live model on the emulator (typed, key from `tools/local.env`):** "what is going on"
runs `list_workspaces` + `workspace_overview` and names the agent waiting on a question (`voice-talk.ps1`
PASS); "tell the Demo agent yes" proposes "y", reads it back, and one typed "yes" sends it to the tmux pane.
**Verified by the user on the S25:** walk mode keeps listening and answering with the phone locked.
Fixed after that: stopping walk mode while a reply was still playing crashed the app (the track was released
under a blocked `AudioTrack.write`); the capture and playback loops now own and release their audio objects.
README screenshots regenerated (`tools/screenshots.ps1`, now with walk mode; it saves a placeholder key before
the Settings shot so no real key fragment is published). Mockup v5 matches the new screens.
**Not verified:** how well the model summarises real agent screens (only scripted ones so far), echo and
interrupting on the phone's loudspeaker.
Steps: (1) Realtime voice session from the phone with a key from Settings;
(2) workspace tools over the existing SSH connections; (3) two or three workspaces with distinct voices
and persona prompts; (4) measure time to first audio, interruption handling and cost per minute on the
S25. Open: the foreground-service and background-audio path for Android 15+.
Cost work (2026-10-26): (a) model picker in the new Settings > Dictation and walk mode screen
(`ui/VoiceSettingsScreen.kt`; dictation and the assistant moved there), stored in `AppSettings.realtimeModel`;
`voice-talk.ps1` picks `gpt-realtime-2.1-mini` and `voice-talk.ps1` / `screenshots.ps1` refuse any other model.
(b) Fewer false turns outdoors: `near_field` noise reduction, VAD threshold 0.65, 700 ms end-of-turn silence.
**Verified:** JVM tests; `voice-talk.ps1` PASS on the mini model (both workspace functions ran, audio played).
**Not verified:** whether noise reduction and the stricter VAD cut false turns on a real walk, or clip quiet
speech (tune `VAD_THRESHOLD` if so); the mini model on the phone with real agents.
(c) Usage tracking: `response.done` and transcription `usage` are parsed into `voice/Usage`, priced with the
list prices in `RealtimeProtocol.MODELS` (`Prices`, OpenAI pricing page 2026-10-26), and summed per day and
model in Room (`voice_usage`, v5; cost fixed at the time of use). The walk screen shows "This conversation:
tokens · about $x"; Settings > Dictation and walk mode shows today / this month / all time, this month by
model, and a Reset; the Settings row shows this month's spend. Logcat has `usage <model> tokens=… usd=…`.
**Verified:** JVM tests (`RealtimeProtocolTest` usage/cost/format, `UsageTotalTest`); `voice-talk.ps1` PASS
with usage checks: 3 responses, 3.3k tokens, about $0.009 on mini, same totals in Settings; the v4 -> v5
migration ran over the emulator's existing database. **Not verified:** a spoken (transcribed) turn, which
adds a `gpt-4o-mini-transcribe` row; how close the estimate is to OpenAI's billing page.
Known gap: a response still running when walk mode is stopped is billed but not counted (its `response.done`
arrives after the socket is closed).
Mockup v6 (Settings row, new Settings › Voice screen, cost line on the walk screen), README and its
screenshots (`walk-mode.png` with the cost line, `settings-voice.png` with models and usage) updated.
Further cost work (shorter context, idle auto-stop, shorter answers, push-to-talk, a text-in/text-out
economy engine) is written down in [VOICE-COSTS.md](VOICE-COSTS.md) and **deferred on purpose**: not to be
started until the user decides, based on the measured usage.

## Manual reorder (2026-10-25)

Holding a project, workspace or session row and dragging it reorders the list (`ui/Reorderable.kt`,
a long-press drag on a plain `Column`; a short tap still opens the row). The order is stored in `sortOrder`
(projects already had it; workspaces and sessions got it in Room v4, existing rows start at 0 and keep their
old order until dragged). New items go last. Workspace positions are only compared within one project.
**Verified on the emulator:** v3 -> v4 migration over an existing database, project reorder persists across a
restart, tap still opens a project, the drop does not open it, session reorder both ways
(`adb shell input draganddrop`). **Not verified:** workspace reorder (same component; the emulator had one
workspace) and the feel on the phone (haptic, auto-scroll is not implemented for long lists).
