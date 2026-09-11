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
