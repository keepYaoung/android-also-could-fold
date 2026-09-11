# Integrated local ADB app (0.3.2)

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
tamper rejection. Run this before setting up everyday use: the Gradle device-test
run removed the target APK in our test environment, requiring reinstallation.
Do not assume this command preserves an existing installation or pairing identity.

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

## V2 snapshot and black gradient (0.4.0, device validation pending)

The app defaults to V2, with a V1 switch to compare with the existing blur. Mode
changes restart the owned engine. The standalone tool still defaults to V1; add
`--v2` explicitly to its `run` command.

`BlackGradientRenderer` captures the active physical cover display once per motion
using the firmware-compatible ScreenCapture API on a worker thread. The bitmap stays in process memory and is
released on completion, panel/size change, lock-state change, screen-off or stop.
A generation check discards asynchronous results from an earlier transition. The
inner display uses only a transparent left-half buffer over the live screen.
The layer has no input window. Its secure flag prevents re-capturing the snapshot.
Capture requests explicitly exclude secure and protected content. On a locked
screen the renderer skips the snapshot and shows only the gradient. Capture failure
stops the engine with an error; V1 remains selectable.

Both modes share motion confirmation and the 1.5-second idle/420-ms release. V2
cover opacity grows with reported angle and inferred movement; inner opacity falls
with opening angle. Coarse jumps directly to fully open use a 480-ms reveal from
the prior rendered strength, or full strength if no prior frame was displayed.
This timed reveal is a visual fallback, not a measured continuous angle.

Implementation references: [AOSP ScreenCapture](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/window/ScreenCapture.java),
[AOSP Surface](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/view/Surface.java).

Build/JVM checks do not verify Samsung capture permissions or rendering. Test cover
snapshot onset, inner reveal, reverse closing, idle cleanup, rotation, screen-off,
locking mid-transition, and capture-failure cleanup before claiming V2 support.

The 0.4.1 review and outstanding device checks are recorded in [V2_VALIDATION.md](V2_VALIDATION.md).
