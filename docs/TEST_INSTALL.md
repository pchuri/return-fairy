# Install the separate Android test app

Debug builds use application ID `com.pchuri.returnfairy.debug` and appear as
**Return Fairy (Test)**, or **반납요정 (Test)** in Korean. They can be installed
alongside the signed release `com.pchuri.returnfairy`, including version 4.0.1.
The Kotlin namespace stays `com.pchuri.returnfairy`; it is not the installed app ID.

The release ID, labels, version, signing configuration and release workflow are
unchanged. The test APK is debug-signed and is not a release update.

## Get the correct APK

1. Open the pull request's latest successful **build-android** run. Check that the
   run belongs to the intended PR commit and that **Verify isolated test APK**
   passed. Older artifacts, produced before this check was added, are not safe
   side-by-side test packages.
2. Download the **return-fairy-debug-apk** artifact from that run and extract its
   ZIP. The installable file inside is `app-debug.apk`, not the ZIP itself.
   The included `test-package-verification.txt` records the built commit, package,
   labels, provider authorities, signing-certificate details and APK SHA-256.
   The same evidence is printed in the verification step's log.
   For pull requests, the built commit is normally GitHub's temporary merge commit,
   which can differ from the PR branch head shown by the run.
3. Keep the existing release installed. Open the APK using Android's normal
   trusted-file installation flow. The installer must identify a **(Test)** app.
   Stop if it offers to replace the ordinary Return Fairy app, reports a conflict
   with the release, or shows a security warning. Do not disable security checks,
   clear the release's data, or uninstall the release to get past an error.
4. Confirm that both launcher entries exist and open the **(Test)** entry.
   A first installation starts with no accounts or cached books. Existing release
   accounts are deliberately not imported. App notification permission and
   settings are separate as well.

No real account or password is needed to check package installation and the empty
state. If you choose to test library lookups later, enter accounts yourself in the
test app; never include credentials in an issue, build log, screenshot or artifact.
Both apps can generate reminders if the same account is configured in both.

In the test app, turn off **Settings → App → Check for updates**. The existing
update feature links to public production releases, not PR test builds. A release
APK cannot update the `.debug` app. This change leaves release update behavior
unchanged.

## Build and verify locally

Use JDK 21 and an Android SDK with platform 35, build-tools and command-line tools:

```bash
python3 -m unittest discover -s scripts -p 'test_*.py' -v
./gradlew testDebugUnitTest assembleDebug
AAPT2="$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/aapt2"
python3 scripts/check_debug_package.py app/build/outputs/apk/debug/app-debug.apk \
  --aapt2 "$AAPT2" \
  --apkanalyzer "$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
```

The last command reads the compiled APK. It checks its installed ID, English and
Korean Test labels, all other resolved labels, debug/backup flags, merged provider
authorities, app-specific permissions, lack of a shared UID and lack of incoming
deep links. CI runs this gate before uploading the APK. Source/fixture tests alone
do not establish that an APK was built successfully or tested on a device.

## Isolation audit

- **Files and preferences:** `AccountStore` and `SettingsStore` use private
  preferences; `SnapshotStore` uses `context.filesDir`. The different application
  ID gives the test app its own Android sandbox. It cannot load the release's
  account list, cached snapshot or schedule-version preferences.
- **Legacy cleanup:** database deletion, internal files/cache and
  `getExternalFilesDir` are resolved through the test app's own context. A fresh
  test app has no saved legacy download ID. No release path or data migration is
  introduced.
- **Keystore:** the `returnfairy_master` alias is unchanged, but Android Keystore
  keys are isolated by application UID. No shared UID is declared, so this alias
  does not grant the test app access to the release key.
- **Background work and notifications:** WorkManager's private database, unique
  work name, settings, notification channels and notification IDs belong to each
  installed app. Identical local names do not replace the release's jobs or
  notifications. The notification PendingIntent uses the current app context and
  an explicit MainActivity class, so it opens the test app.
- **Providers, permissions and links:** the app source declares no provider or
  incoming deep link. AndroidX's merged startup provider and generated permissions
  must use the suffixed application ID; CI inspects the compiled manifest to catch
  collisions. Kotlin component class names correctly keep the original namespace.
- **Network:** this is a separate package, not a sandbox library service. If you
  add a real library account, lookups still contact `splib.or.kr` as in release.

For the platform behavior behind this audit, see Android's
[build variants](https://developer.android.com/build/build-variants),
[application sandbox](https://source.android.com/docs/security/app-sandbox), and
[Keystore namespace](https://source.android.com/docs/security/features/keystore)
documentation.

## Test updates and removal

A test APK can update an existing test installation only if their signing keys
match. CI runners may use different debug keys on different runs. If Android
rejects a test-to-test update for this reason, stop and decide whether losing only
the test app's data is acceptable; do not use an uninstall as an automatic fix.
If you choose to remove it, verify **(Test)** and package
`com.pchuri.returnfairy.debug` in App info first. Removing that app discards its
test accounts, cache and reminders. Leave `com.pchuri.returnfairy` installed.

On-device coexistence, reminder delivery, reboot behavior and retained release
data still need a manual check. Package/manifest validation is not a substitute
for that device test.
