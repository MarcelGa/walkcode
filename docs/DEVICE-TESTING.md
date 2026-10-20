# Testing on the real phone (Galaxy S25)

Target: Android 15+ (`minSdk 35`). The S25 runs Android 16 / One UI 8.5.

## Install

1. Phone: Developer options -> USB debugging on. If the toggle is greyed out, turn off
   Settings -> Security and privacy -> Auto Blocker first.
2. From the repo (PowerShell, JDK 17 as in AGENTS.md): `.\gradlew.bat :app:assembleDebug`, then
   `adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk`.
   `-r` keeps app data (hosts, projects, sessions, the Keystore key). Only an uninstall, a factory
   reset, or installing a build signed with a different debug key (another computer) wipes it.
   `allowBackup` is off, so there is no cloud restore.
3. If USB debugging will not stick, use wireless debugging: phone Developer options -> Wireless
   debugging -> pair with code; `adb mdns services` shows the pairing and connect endpoints;
   `adb pair <ip:port> <code>`, then `adb connect <ip:port>`. Use the mDNS serial
   (`adb-<serial>-<id>._adb-tls-connect._tcp`) if the port changes. Keep the work VPN off.

## Authorizing the phone on a host

The phone's SSH key lives in the Android Keystore. It is shown in full on the Hosts tab
(**Copy pubkey**).

Easier: open the host in the app, use **Phone key -> Copy** and paste the one-line command on the
host (Linux/macOS shell, or PowerShell run as administrator for psmux hosts). Then tap **Test
connection** to confirm. The command is idempotent, so running it again is harmless. The manual
steps below do the same by hand:

Add that `ssh-ed25519 ... walkcode@android` line to the host:

- Linux/macOS: `~/.ssh/authorized_keys`.
- Windows (admin user): `C:\ProgramData\ssh\administrators_authorized_keys` from an *elevated*
  shell, ACL limited to Administrators and SYSTEM (`icacls ... /inheritance:r /grant Administrators:F /grant SYSTEM:F`).
- Then run `tools/host-config/install-tmux-conf.sh` (or `.ps1` on Windows) once per host: bell
  counter, `focus-events`, and on Windows the Ctrl+C-capable shell init.

A new device key (new phone, reinstall after uninstall, new emulator) must be re-authorized.

## Hosts set up so far (state as of 2026-10-19, lives in the phone's app data, not in git)

| Host | LAN | Tailnet | User | Mux | Notes |
|------|-----|---------|------|-----|-------|
| desktop (this Windows PC) | `<lan-ip>` | `<tailnet-ip>` | `<windows-user>` | psmux | key in administrators_authorized_keys |
| homelab-vm | dev.local (`<vm-lan-ip>`) | none yet | `<vm-user>` | tmux 3.6 | key in authorized_keys, tmux conf installed |

Projects on the phone: `Personal`, `Test` (workspaces `Desktop`, `VM`, plus the user's own).
`dev.local` only resolves on the home Wi-Fi/LAN; with the work VPN on the VM is unreachable.

## Samsung specifics

Settings -> Battery -> Background usage limits -> Never auto sleeping apps -> Walkcode, on top
of the in-app battery-optimisation prompt, or alerts stop while the screen is off. USB-tethering
or a changed USB mode drops adb.

## Not yet tried on the phone

Real Claude Code as the bell source, a locked-screen alert round trip, switching Wi-Fi <-> mobile
data (reconnect), and the tailnet fallback away from home. Everything else is covered by the
emulator scripts listed in AGENTS.md.
