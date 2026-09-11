> Historical: this describes the 0.2.x Shizuku app. Current setup: [local ADB app](LOCAL_ADB_APP.md).

# Persistent Shizuku app

The app uses Shizuku API/provider 13.1.5. FoldUserService is an AIDL Binder in a
shell-UID daemon process. A dedicated HandlerThread owns FoldShell and its
SurfaceControl objects. The app never receives pixels or executes arbitrary shell
commands. Commands are restricted to start/intensity, status, stop and destroy.

The original ADB runner and the daemon share a file lock to prevent duplicate
layers. Settings updates reuse the engine; they do not reset an ongoing fold.
Destroy removes native layers, unregisters sensors and closes the vendor monitor.

FoldApplication persists opt-in and intensity. Shizuku Binder delivery restores
an enabled daemon even when a provider starts the app process. KeepAliveService
provides a visible stop action and keeps a process for 15-second health checks.
Three failed connection attempts require explicit retry or a new Shizuku Binder.
Firmware failures are shown rather than repeatedly restarting the renderer.

Boot and package-replaced broadcasts attempt restoration. Background foreground-
service restrictions can prevent the guardian from starting, but Shizuku Binder
delivery can independently reconnect the daemon. Force-stop, permission revocation,
missing Wi-Fi, and Samsung background restrictions can still require user action.
This is a user-space daemon, not a firmware patch or unconditional boot service.

The original finite ADB runner remains available for diagnostics. Its duration
limits do not apply to the Shizuku-owned engine. See README for setup and limits.


## Validation on SM-F966N

- Debug APK build, Android lint (no errors), and fold state/gradient JVM tests pass.
- User confirmed original fold visuals work through the app, including lock screen.
- Killing the UI/guardian process leaves the daemon alive; Android restarts the guardian.
- Killing the daemon itself is recovered by the enabled guardian with a new daemon PID.
- A forced Shizuku restart exposed orphaned old daemons in the first revision.
  The service now links to the Shizuku server Binder and exits on its death, while
  deliberately surviving death of the UI process. A subsequent forced server restart removed the old daemon and restored exactly one new engine.
- Reboot auto-start requires Shizuku wireless pairing; USB startup alone is insufficient.
