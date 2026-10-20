# Releases (GitHub Actions + Obtainium)

Phone installs come from GitHub Releases, not from ADB. `.github/workflows/release.yml`
builds a release APK signed with the project's release key and attaches it to a release.
Obtainium on the phone watches the latest release and offers updates.

## Cut a release

```powershell
git tag v0.2.0
git push origin v0.2.0
```

The tag becomes the `versionName` (`0.2.0`). The workflow run number becomes the
`versionCode`, which always increases, so Android accepts every new release as an update.
The workflow runs the JVM unit tests before it builds the APK. Progress is under the
repo's Actions tab. A finished run creates a GitHub Release with `walkcode-v0.2.0.apk`.

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

**One-time switch from the debug build:** the phone has the debug-signed WalkCode now, and
Android will not install a release-signed APK over it. Uninstall WalkCode once, then install
from Obtainium. Afterwards, hosts, projects, sessions and the Keystore SSH key are gone, so
re-add the phone's public key on each host (see [DEVICE-TESTING.md](DEVICE-TESTING.md)).
