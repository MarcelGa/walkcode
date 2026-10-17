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
| M2 | UI: projects, workspaces, session list, launcher, terminal | **code complete, only smoke-tested** (see below) |
| M3 | Unread/foreground service/alerts, reconnect, "paused" notice | todo |
| M4 | Labels, rename, settings, battery-optimisation prompt | todo |

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
**Not verified yet:** the Compose screens driven end to end (add host -> terminal). Original note: the full flow against a real host
(add host -> workspace -> new session -> terminal), esp. psmux quoting
(`send-keys` text, `%USERPROFILE%` paths) and `~` expansion for tmux. Docker host
was not running. Needs an instrumented/e2e runner like `tools/spikes/*.ps1`.
Known gaps: `markSessionViewed` is not called yet (M3); no battery/foreground bits.
