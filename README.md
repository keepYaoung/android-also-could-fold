# Android Also Could Fold

**English** | [한국어](README.ko.md)

**Keep One UI. Make folding and unfolding feel smoother.**

An experimental Android app that applies system blur during physical fold and
unfold transitions on Galaxy Z Fold. It controls the compositor through Shizuku's
ADB privileges, working over the home screen, apps, and lock screen without screen
capture or replacing the launcher.

## Features

- **Cover opening:** a blur gradient that grows stronger toward the right edge.
- **Inner display:** blur on the left half only, strongest at the outer edge and weakest near the hinge.
- Blur strength responds to angle and motion signals, with an overall **50–150%** intensity control in the app.
- **1.5 seconds without detected movement → a smooth 420 ms release.**
- Smooth release when the sensor reports a reversal in direction.
- No visible effect while the display is off or showing AOD.
- A Shizuku daemon that survives closing the app, saved settings, reconnection recovery, and a notification stop button.

## Compatibility and limitations

Currently tested on **Galaxy Z Fold7 SM-F966N / Android 16**. Execution is blocked
on other models. The engine depends on Samsung's private SurfaceControl APIs, so
compatibility needs to be checked after One UI updates.

On this device, the public hinge sensor mainly reports **0 / 90 / 180 degrees**.
Early closing detection on the inner display requires an additional sustained
motion signal to reduce reactions to brief handling noise. It can respond about
0.5 seconds later than cover opening; reported angle changes start the effect
without this extra wait.

Early motion is inferred from vendor event timestamps in `dumpsys sensorservice`.
The engine does not read hidden continuous angle values. Very slow movement and
small reversals may be missed. Diagnostic polling runs at 4 Hz while the screen
is interactive; long-term battery impact has not been measured.

## Try it without installing an app

**You can try the same blur effect without installing either the Fold Transition
APK or Shizuku.** A computer starts a temporary engine over ADB, which automatically
stops after **10 minutes** by default. Root and Wi-Fi pairing are not required.

Prepare Python 3, JDK 17 or newer, Android SDK Platform 36, Build Tools 36.0.0,
and Platform Tools on your computer. Enable **Developer options → USB debugging**
on the phone, connect a USB data cable, and approve debugging access for a computer
you trust.

Run these commands from the repository root. If `adb` is not on your PATH, use
its path under the SDK's `platform-tools` directory. The Python tool reads
`JAVA_HOME` and `ANDROID_SDK_ROOT`; on macOS it defaults to the Android Studio JDK
and the standard Android SDK location. Set both variables explicitly on other
systems. Windows execution has not been verified.

```sh
adb devices -l
adb shell getprop ro.product.model
python3 tools/fold-system.py run --seconds 600 --early
```

The `run` command builds and tests the engine, transfers its DEX file, and starts
it. No APK is installed, but an executable file and a lock file are created under
`/data/local/tmp` on the device. The supported model is **SM-F966N**, as noted above.
If multiple devices are connected, add `-s SERIAL` to ADB commands and
`--serial SERIAL` to the Python command.

After `READY` appears, slowly fold and unfold the device using the cover, inner
display, and awake lock screen. The blur should fade after 1.5 seconds without
movement. `--early` enables inferred motion onset; omitting it uses only the public
angle sensor and may delay the effect.

- Keep the terminal session and USB connection open during the trial. Continued operation after disconnection is not guaranteed.
- **If the app is already enabled, turn the effect off in the app or its notification first.** Do not run both modes at once.
- Set `--seconds` to a value from 1–3600 to change the duration. This mode does not start automatically after a reboot.
- No screen content is captured, saved, or uploaded. See the limitations above for angle estimation and battery impact.

To stop before the timer expires, run this in another terminal:

```sh
python3 tools/fold-system.py stop
```

This command stops **only the temporary ADB engine**. Stop the Shizuku app engine
from the app or its notification. Do not assume Ctrl+C or unplugging USB has stopped
the engine; reconnect and use the command above if needed. Transferred DEX files
may remain after automatic shutdown, but they do not run automatically.

For ongoing use and an intensity control UI, use the app setup below.
[AGENTS.md](AGENTS.md) contains the agent workflow and user communication guidance
(in Korean).

## App setup

1. Install [official Shizuku](https://shizuku.rikka.app/download/), preferably version 13.6 or newer.
2. Follow Shizuku's instructions to **pair and start through wireless debugging**, or start it from a computer over ADB.
3. Build and install the APK as described below, then open **Fold Transition**.
4. Tap **Enable effect** (`효과 켜기`) and allow Shizuku access and status notifications.
5. Fold and unfold to check the effect. Use **Disable effect** (`효과 끄기`) or the notification's **Stop** (`끄기`) action to stop it.

The current app UI is in Korean. Root is not required; Shizuku must run as
**shell UID 2000**. If an older ADB trial is running, stop it first with
`python3 tools/fold-system.py stop`. Stopping Shizuku also stops the blur engine.

## Recovery after reboot

The app saves its enabled state and intensity. On boot or Shizuku Binder
reconnection, it reconnects the engine if permission is still granted. A foreground
service periodically checks its status. Repeated errors are shown in the app
instead of triggering endless restarts.

**Shizuku itself must be running first.** Shizuku 13.6 supports starting without
root on Android 13 and newer when connected to a trusted Wi-Fi network. After
wireless debugging pairing, it requires the following permission:

```sh
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

This permission lets Shizuku's boot handler enable USB/wireless debugging and
change the ADB authorization timeout setting. This is Shizuku's own feature,
not a custom boot bypass. To revoke the permission, replace `grant` with `revoke`
in the command above.

Automatic recovery is not guaranteed without Wi-Fi, after Shizuku permission is
revoked, or after the app is force-stopped. Reopen the app and Shizuku to check
their status. Fully automatic recovery across a device reboot still requires
separate on-device verification.

If you see `CERTIFICATE_UNKNOWN`, start **Pairing** in Shizuku, open **Pair device
with pairing code** in Android settings, and enter the six-digit code in the
Shizuku notification while keeping the pairing dialog open. `Searching for pairing`
means Shizuku is waiting to find that dialog. Tap **Start** after pairing succeeds.
Connecting your computer through ADB does not also register Shizuku's wireless
identity.

References: [Shizuku 13.6 release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0),
[official boot handler](https://github.com/RikkaApps/Shizuku/blob/v13.6.0/manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt).

## Build and verification

Requires JDK 17 or newer, Android SDK Platform 36, and Build Tools 36.0.0.
You can use the JDK bundled with Android Studio. Set the SDK path through
`sdk.dir` in `local.properties` or the `ANDROID_HOME` environment variable.

```sh
./gradlew :app:assembleDebug :app:lintDebug
python3 tools/fold-system.py build
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Python tool supports `JAVA_HOME` and `ANDROID_SDK_ROOT`. On macOS, it defaults
to the Android Studio JDK and standard Android SDK path. The debug APK uses a
development signing key; release APKs require separate signing.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/` | Settings UI, Shizuku permissions and connection, foreground guardian, boot recovery |
| `system/` | Shared compositor engine, fold state machine, gradients, JVM tests |
| `tools/` | Standalone ADB trials and sensor diagnostics |
| `legacy/` | Earlier screen capture prototype, excluded from the build |
| `docs/` | Device findings and implementation notes |

The app has no analytics SDK and no runtime flow requiring internet, screen capture,
or accessibility permissions. Sensor diagnostics are processed locally on the
device; screen content is never stored or uploaded.

## License

[MIT](LICENSE) · Copyright © 2026 keepYaoung.
Shizuku and Android/Gradle dependencies remain subject to their respective licenses.
