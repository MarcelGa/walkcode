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
