# Integrated local ADB app (0.3.0)

The APK owns pairing, connection monitoring and the existing system blur engine.
Shizuku is no longer a runtime dependency. Its previous implementation is archived
under `legacy/shizuku/`; `docs/SHIZUKU_APP.md` describes the old release.

## Connection lifecycle

- `AdbDiscovery` discovers pairing/connection services through Android NSD, serializes
  resolutions, ignores stale callbacks, and accepts only addresses belonging to this
  phone. Actual connections always use loopback, never a discovered remote host.
- `LocalAdb` generates an RSA identity once. Its PKCS8 key and certificate are stored
  atomically in `noBackupFilesDir`, encrypted with a non-exportable Keystore AES-GCM
  key. Corruption fails explicitly rather than silently changing the identity.
- `PairingReceiver` accepts a six-digit notification reply. The user keeps Android's
  pairing dialog open. Neither the code nor private key is logged or sent to a server.
- `FoldApplication` runs network work on one scheduled executor. It opens an authenticated
  raw ADB shell stream using the installed APK path from PackageManager. The command
  starts `LocalFoldDaemon` as shell UID 2000. No user-controlled shell command is accepted.
- The stream accepts only STATUS, INTENSITY and STOP. The daemon shares the existing
  file lock, runs the engine on its main Looper, and closes on EOF, STOP or 45 seconds
  without a request. No additional server socket or exported Binder is created.
- The foreground service keeps the connection active after the activity closes.
  A five-second status check and bounded reconnect delay handle connection loss;
  explicit engine errors pause retries until toggled off/on.
- Boot/package replacement attempts service restoration using saved enabled state.
  The app does not enable wireless debugging, change ADB authorization expiry, or
  obtain root. Android restrictions and wireless availability still apply.

## Validation

Build/lint and the existing JVM motion suite pass. The identity instrumentation test
passed on SM-F966N / Android 16, checking reload stability, encrypted storage and
tamper rejection. To run it on a device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

The installed APK daemon returned RUNNING to STATUS and STOPPED to STOP via USB
ADB. No daemon process remained afterward. The app_process command returned exit
137, so this is not recorded as a clean exit-code test. App-owned wireless pairing
and recovery have not yet been verified. Required physical checks:

1. Stop the old effect before upgrade, install, pair through the app notification.
2. With USB already removed, enable and check cover/inner/awake-lock-screen effects.
3. Disable and verify layer cleanup; enable again without re-pairing.
4. Unplug USB during an active session and check reconnection.
5. Disable/re-enable wireless debugging and check saved-key reconnection.
6. Reboot, make wireless debugging available, and check recovery without a new code.

A successful build is not evidence that Samsung permits the local ADB session.
Gradients and the 1.5-second hold/420-ms dissolve are unchanged. Since 0.3.1,
cover opening also requires two accepted motion bursts 250–700 ms apart, matching
the existing inner closing confirmation. Isolated bursts remain invisible; public
angle changes bypass the added confirmation. JVM regressions cover repeated display
updates, panel changes, gaps, and direct-angle onset. Physical sensitivity tuning
still needs user feedback.
