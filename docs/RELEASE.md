# Releases (GitHub Actions + Obtainium)

Phone installs come from GitHub Releases, not from ADB. `.github/workflows/release.yml`
builds a release APK signed with the project's release key and attaches it to a release.
Obtainium on the phone watches the latest release and offers updates.

## Cut a release

```powershell
git tag -a v2026.10.1 -m "WalkCode 2026.10.1"
git push origin v2026.10.1
```

Tags are calendar versions `vYYYY.MM.N` (N counts releases within the month, from 1; see
docs/DECISIONS.md). The tag without the `v` becomes the `versionName` (`2026.10.1`). The workflow run number becomes the
`versionCode`, which always increases, so Android accepts every new release as an update.
The workflow runs the JVM unit tests before it builds the APK. Progress is under the
repo's Actions tab. A finished run creates a GitHub Release with `walkcode-v2026.10.1.apk`.

The release notes list the commit subjects since the previous release (oldest first) and a
compare link. Obtainium shows them with the update. To put hand-written highlights above the list,
add them to the tag message after the subject line:

```powershell
git tag -a v2026.10.2 -m "WalkCode 2026.10.2" -m "Self-hosted voice for walk mode."
```

To build an APK without publishing a release, run the workflow by hand from the Actions tab
(`workflow_dispatch`). The APK is attached to that run as an artifact.

## Signing key

- Keystore: `~/.walkcode/walkcode-release.jks`, passwords in `~/.walkcode/release-signing.env`
  (both outside the repo). Back them up. If the key is lost, the phone can only take updates
  after an uninstall, which wipes app data.
- GitHub repo secrets (Settings -> Secrets and variables -> Actions):
  `WALKCODE_KEYSTORE_BASE64`, `WALKCODE_KEYSTORE_PASSWORD`, `WALKCODE_KEY_ALIAS`,
  `WALKCODE_KEY_PASSWORD`. The workflow fails early if the keystore secret is missing.
- Local release builds without `WALKCODE_KEYSTORE_FILE` set fall back to the debug key.
  Do not publish those.

To build a signed release locally, load the env file and set the version variables:

```powershell
Get-Content ~\.walkcode\release-signing.env | ForEach-Object {
  if ($_ -match '^(\w+)=(.*)$') { Set-Item "env:$($Matches[1])" $Matches[2] }
}
.\gradlew.bat :app:assembleRelease
```

## Obtainium on the phone

1. Install Obtainium (from its GitHub releases page, or F-Droid).
2. Add app -> source URL `https://github.com/MarcelGa/walkcode`.
3. Obtainium asks to install unknown apps for itself. Allow it.
4. Install WalkCode from Obtainium. Future releases show up as updates in Obtainium.

Two apps on the phone, by design:

- **Debug** (`dev.walkcode.app`, label "Walkcode debug"): installed with `adb install -r` for quick
  testing over wireless debugging. Signed with the local debug key.
- **Release** (`dev.walkcode.app.release`, label "Walkcode"): installed and updated by Obtainium from
  GitHub Releases. Signed with the release key. Its own data, so it starts empty.

Moving the setup to the release app: in the debug app, Settings -> Backup -> Export config. In the
release app, Settings -> Backup -> Import config. Then, for each host, copy its Phone key command
and run it on the host, because the release app has its own Keystore key. Test connection confirms
it. Obtainium needs at least one GitHub release (a pushed `v*` tag) before it can install anything.
