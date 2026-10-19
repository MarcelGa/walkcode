# Walkcode — settled decisions (do not re-ask)

These choices were made during the design Q&A and the spikes. Treat them as
**decided**: implement them, don't re-open them unless the user explicitly asks to
revisit. Each line is a decision, not an open question.

> Provenance: reconstructed from the design Q&A and the spike work. It captures the
> decisions we have evidence for but is **not guaranteed to be the complete Q1–Q83
> transcript**. If you have the original list, append anything missing here rather
> than asking the user again.

## Product & scope

- Personal app, **sideloaded via ADB**. Never published to Play; **no Play/GMS
  dependencies**.
- Runs/watches/interacts with CLI AI agents: **Claude Code, opencode, Copilot CLI,
  Hermes**, inside `tmux` (Linux) / `psmux` (Windows) on the user's own hosts.
- **No backend / no server component.** The phone talks to hosts directly over SSH.
- Control surface is the multiplexer; the app does not reimplement the agents.

## Data model

- Hierarchy: **Hosts → Projects → Workspaces → Sessions**.
- **Host** = an SSH machine: name, username, port, LAN address, tailnet address,
  shell kind (tmux/psmux).
- **Project** = a purely local, colour-coded label grouping workspaces; never sent
  to a host.
- **Workspace** = `{name, project, host, directory, agent, args}` — a place an agent
  runs.
- **Session** = one multiplexer session; **several sessions per workspace** are
  supported.
- Session names are **deterministic**: `wc-<slug>-<id>[-N]` (slug is `[a-z0-9-]`).
- Tapping a workspace **starts the agent and then attaches**.

## Identity & host keys

- The phone's SSH identity is a single **Ed25519 key generated in the Android
  Keystore**; the private key never leaves secure hardware.
- **One device key shared across all hosts.**
- Key provisioning is **manual**: the user copies the phone's `ssh-ed25519 …` line
  into the host's `authorized_keys`. No automatic key installation.
- Host keys use **Trust On First Use**, pinned as **OpenSSH SHA-256** fingerprints
  (`SHA256:…`, matching `ssh-keygen -lf`). A changed key is an error, not a prompt to
  blindly re-trust.

## Networking

- **Direct SSH** from the phone.
- Per host, try the **LAN address first** (e.g. `dev.local`, which resolves on Wi-Fi
  only, not over VPN), then fall back to the **Tailscale** address.
- Tailscale may also be used as the always-on path.

## Attention / alerts

- Watching is **per session and opt-in** (`Session.watched`); not every session is
  watched.
- **Unread** = the screen changed since the session was last viewed.
- There is a **"quiet for N seconds"** fallback alert; **default 15 s, configurable**.
- **No generic "new output" alerts** — only unread/quiet/attention semantics.
- Detection uses the **tmux/psmux bell-count hook plus screen-hash polling**; do
  **not** rely on `window_activity`.
- The app sets tmux options **only on the sessions it watches**.
- Background monitoring uses a **`dataSync` foreground service** (subject to the
  6 h / 24 h background cap; show a "paused" notice when capped).
- **Two notification channels.**

## UI

- Kotlin + **Jetpack Compose**; `mockup/index.html` is the **source of truth** for
  the M2 look.
- **No Nerd Font** requirement.

## Process

- **Spike-first**, then milestones M1–M4 (see `docs/PROGRESS.md`).
- **Do not file psmux upstream bugs.**

## Host/terminal behaviour that shaped the design

- Windows sessions **ignore Ctrl+C** unless the shell was started through
  `tools/host-config/walkcode-shell-init.ps1`; the app starts sessions that way.
- On psmux/Windows, input is submitted with a **raw carriage return**, not the named
  `Enter`/`C-m` key.
- `focus-events` is enabled on hosts so agents know when the user is present and
  only alert when away.

## Added in later sessions

- Target **Android 15+** (`minSdk 35`) because the device is a Galaxy S25; the dataSync time-limit
  pause is therefore a first-class path.
- Sessions can carry an optional **label** (shown instead of `#N`, also in alert titles).
- Quiet-alert threshold is **configurable in Settings** (default 15 s, 5-120 s choices) and quiet
  alerts can be switched off; the battery-optimisation prompt is shown **once**, on the first Watch.
- Windows sessions get a **`WALKCODE_SESSION=1`** marker so users can adapt their PowerShell profile.
- App icon: terminal prompt `>_` (green on the app's dark background), adaptive + monochrome.
