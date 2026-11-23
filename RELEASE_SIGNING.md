# Release signing (Play Protect fix)

## Why this exists

Before v8.10.114 the **release** build was signed with the Android **debug** key:

```kotlin
release { signingConfig = signingConfigs.getByName("debug") }   // old
```

The debug keystore (`CN=Android Debug, O=Android, C=US`) is public: it ships with every
Android Studio and is reused by throwaway builds and by a lot of real malware. Google Play
Protect's on-device scanner treats **"debug-signed APK that holds SMS / call log / contacts /
REQUEST_INSTALL_PACKAGES"** — i.e. this app — as a strong *harmful app* signal. That is the
most likely reason for the "Harmful app blocked" / "Unsafe app blocked" prompts.

From v8.10.114 the release build is signed with a **stable, private key that is yours alone**.
The key is never committed; the build reads it from `keystore.properties` (git-ignored) or, for
CI, from environment variables. With no key configured the build still assembles but falls back
to the debug key **and prints a warning** — never publish that APK.

## One-time: create your signing key (do this on your own machine)

```bash
keytool -genkeypair -v \
  -keystore caller-release.jks \
  -alias caller \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Caller, OU=Caller, O=Caller, L=City, ST=State, C=IN"
# keytool will prompt you to choose and confirm the keystore/key password.
```

Move `caller-release.jks` to the repo root (it is git-ignored), then create `keystore.properties`
(also git-ignored) from the template:

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties and set storePassword / keyPassword to the password you just chose
```

**Back up `caller-release.jks` and its password somewhere safe (not in git).** If you lose this
key you can never ship a signed update over an existing install again — users would have to
uninstall and reinstall.

## Build a signed release

```bash
./gradlew assembleRelease
```

Verify it is NOT debug-signed:

```bash
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk | grep Owner
# must NOT say  CN=Android Debug
```

For CI instead of the file, set `CALLER_KEYSTORE` (path), `CALLER_KEYSTORE_PASSWORD`,
`CALLER_KEY_ALIAS`, `CALLER_KEY_PASSWORD`.

## IMPORTANT: the first signed build is a clean reinstall, not an update

Changing the signing key changes the APK's certificate. Android will **refuse** to install a
new-key build over an existing debug-signed install (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and
the in-app updater will fail the same way for this one transition. On each device, the move from
the old debug-signed app to the first release-signed build is:

1. Uninstall the current app (export anything you keep first — backups are excluded from device
   transfer by design).
2. Install the new release-signed APK.

Since Play Protect has already been disabling/revoking the app, a clean reinstall is the expected
path anyway. After this, every future release-signed update installs over the top normally.

## Still flagged after signing?

Signing is the biggest single signal but not the only one. If a device still blocks the signed
build, from that device: Play Protect → the blocked-app entry → **More details**, and (Settings →
Play Protect → scan history) note the exact category. Also expect that **install-time** blocks for
an SMS-capable app installed from a browser/file manager are a separate Google rule (noted in
v8.10.107) that signing does not remove — install over USB (`adb install`) or the in-app updater.
