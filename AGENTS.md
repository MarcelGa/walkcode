# Walkcode — agent guide

Personal, native-Android app to **run, watch and interact with CLI AI agents**
(Claude Code, opencode, Copilot CLI, Hermes) inside `tmux` (Linux) / `psmux`
(Windows) on your own hosts, over SSH. No backend. Sideloaded — never published to
Play, so **do not add Play/GMS dependencies**.

Repo root: `C:\Users\galov\sources\personal\walkcode`. Package: `dev.walkcode.app`.

## Build & test

`JAVA_HOME` must point at a JDK 17 (Gradle will otherwise fail):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
.\gradlew.bat :app:testDebugUnitTest          # JVM unit tests (fast, no device)
.\gradlew.bat :app:assembleDebug              # debug APK
.\gradlew.bat :app:assembleDebugAndroidTest   # instrumented-test APK
```

First build after adding a dependency can take several minutes (Gradle downloads).
If the shell tool times out, run Gradle in the background and poll the log:

```powershell
$log = "$env:TEMP\opencode\gradle.log"
Start-Process -FilePath ".\gradlew.bat" -ArgumentList ':app:testDebugUnitTest','--console=plain' `
  -RedirectStandardOutput $log -RedirectStandardError "$log.err" -WindowStyle Hidden
# then poll for the java process and read $log
```

End-to-end checks run on the emulator via PowerShell runners in `tools/spikes/`.
They start the AVD headless, install both APKs, authorize the device key and run
instrumentation. Emulator/AVD `Phone_PlayStore_API34`, SDK at
`%LOCALAPPDATA%\Android\Sdk`.

```powershell
pwsh -File tools\spikes\spike1.ps1            # sshj + Keystore Ed25519 login
pwsh -File tools\spikes\spike2.ps1            # terminal attached to tmux/psmux
pwsh -File tools\spikes\spike3.ps1            # host poller -> unread/quiet/attention
pwsh -File tools\spikes\m1-connector.ps1      # TOFU pinning + LAN/tailnet fallback
```

## Layout

```
app/                      the Android app
  src/main/java/dev/walkcode/app/
    ssh/                  identity, providers, TOFU, connector
    terminal/             SSH transport + terminal controller
    poll/                 host poller protocol, command builder, tracker
    data/                 Room entities/DAOs/database/repository/naming
  src/main/assets/poller/ poll.sh, poll.ps1 (sent inline to hosts)
  src/debug/              spike screens (debug only)
  src/androidTest/        instrumentation tests (spikes, M1 connector)
  src/test/               JVM unit tests
vendor/                   vendored Termux terminal-emulator/-view (Apache 2.0)
tools/
  spikes/                 e2e runners (see above)
  host-config/            tmux/psmux config + installers + shell-init
  windows-host/           elevated Windows OpenSSH setup
  testhost/               Docker SSH+tmux test host; experiments/ diagnostics
mockup/index.html         approved UI mockup (source of truth for M2 UI)
```

Design model: **Hosts** (SSH machines) → **Projects** (local colour labels) →
**Workspaces** `{name, project, host, directory, agent, args}` → **Sessions**
(several per workspace). Session names are deterministic: `wc-<slug>-<id>[-N]`.

## Test hosts

| Host | Address (from emulator) | From workstation | User | Mux |
|------|-------------------------|------------------|------|-----|
| Docker test host | `10.0.2.2:2222` | `127.0.0.1:2222` | `dev` | tmux 3.4 |
| Homelab VM | `192.168.1.88:22` (`dev.local`) | `dev.local:22` | `marcel` | tmux 3.6 |
| Windows desktop | `10.0.2.2:22` | `localhost:22`, tailnet `100.66.206.62` | `galov` | psmux |

- Start the Docker host: `docker compose up -d` in `tools/testhost`.
- Workstation SSH key for driving hosts: `~/.ssh/walkcode_test`
  (`-o IdentitiesOnly=yes -o UserKnownHostsFile=~/.ssh/walkcode_test_known_hosts`).
- The **phone's** key is separate: generated in the Android Keystore. Read its
  public line from the device with
  `adb shell run-as dev.walkcode.app cat files/device_key.pub` and paste it into
  the host's `authorized_keys`.

## Conventions

- Kotlin + Jetpack Compose. `minSdk 34`, `targetSdk 36`, **`compileSdk 37`**
  (AndroidX requires it). Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, Room 2.8.5 via
  KSP 2.3.12, sshj 0.41.2, BouncyCastle 1.86.
- Comments explain **why** (trade-offs, workarounds). Keep that style; don't add
  noise. No emojis.
- Persist with Room. Host-side scripts are streamed inline over SSH — never write
  files onto a host, so there is no version drift.
- Keep `local.properties`, `tools/testhost/keys/`, and any private keys out of git
  (already in `.gitignore`).

## Hard-won gotchas (do not relearn these)

- **Keystore Ed25519**: generate with `ECGenParameterSpec("ed25519")`; remove the
  BouncyCastle OID aliases for `1.3.101.112` or the Keystore rejects its own key;
  load `getKey` + `getCertificate` separately, never `getEntry`. See
  `ssh/Ssh.kt` (`SshProviders`) and `ssh/DeviceKey.kt`.
- **sshj `SecurityUtils.getFingerprint` returns MD5.** M1 wants OpenSSH SHA-256, so
  it is computed in `PinnedHostKey.of` (SHA-256 over the wire blob, base64 no
  padding). Verified equal to `ssh-keygen -lf`.
- **A rejected host key reaches sshj as a `TransportException`.** Read
  `TofuHostKeyVerifier.check` after the failure to tell a mismatch apart from a
  plain connect failure (`ssh/HostConnector.kt`).
- **psmux quirks**: `window_activity` never updates; the bell flag is not cleared
  until a client attaches (hook fires twice per bell); no PTY; terminal size is set
  in-band with `ESC[8;rows;cols t`.
- **Windows sessions ignore Ctrl+C** unless the shell was started through
  `tools/host-config/walkcode-shell-init.ps1` (calls `SetConsoleCtrlHandler(NULL,false)`).
- **psmux `Enter`/`C-m` do not submit input to Claude Code on Windows.** Send a raw
  carriage return instead (`psmux send-keys -t S -H 0d`).
- **Claude Code bells in a headless session** ~6 s after a permission prompt and
  ~60 s idle; enable `focus-events` in the host tmux config so it knows when you
  are present.
- **tmux 3.4 escapes control characters in formats**; the poller uses tab-separated
  fields and forces every field but the title non-empty.
- **Windows OpenSSH runs commands via `cmd.exe` (8191-char limit).** The Windows
  poller is base64/UTF-16LE and asserted `<= 8000` chars.

## Status

Spikes 1–3 and milestones **M1–M4** are done and committed. See the "What is left" section of `docs/PROGRESS.md`.
See `docs/PROGRESS.md` for the milestone breakdown and the exact next steps.

**Before asking the user anything about product/design, read
`docs/DECISIONS.md`.** Those choices are already made; do not re-ask them.
