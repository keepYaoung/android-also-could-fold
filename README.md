# Android Also Could Fold

**English** | [한국어](README.ko.md)

**Keep One UI. Make folding and unfolding feel smoother.**

An experimental Android app that applies system blur during physical fold and
unfold transitions on Galaxy Z Fold. It controls the compositor through the app's built-in local
ADB client, working over the home screen, apps, and lock screen without screen
capture or replacing the launcher.

## Features

- **Cover opening:** a blur gradient that grows stronger toward the right edge.
- **Inner display:** blur on the left half only, strongest at the outer edge and weakest near the hinge.
- Blur strength responds to angle and motion signals, with an overall **50–150%** intensity control in the app.
- **1.5 seconds without detected movement → a smooth 420 ms release.**
- Smooth release when the sensor reports a reversal in direction.
- No visible effect while the display is off or showing AOD.
- A foreground connection service, saved pairing identity, automatic reconnection attempts, and a notification stop button.

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

This command stops **only the temporary ADB engine**. Stop the app engine
from the app or its notification. Do not assume Ctrl+C or unplugging USB has stopped
the engine; reconnect and use the command above if needed. Transferred DEX files
may remain after automatic shutdown, but they do not run automatically.

For ongoing use and an intensity control UI, use the app setup below.
[AGENTS.md](AGENTS.md) contains the agent workflow and user communication guidance
(in Korean).

## Single-app setup (0.3.0)

**Shizuku is no longer required.** This APK includes local wireless ADB pairing,
engine startup, and reconnection. The existing blur engine and One UI behavior
are preserved. The new connection path is experimental: build and lint pass,
and encrypted-identity tests pass on the Fold7, but app-owned wireless pairing,
USB removal, and reboot recovery are not yet verified.

1. If upgrading from the Shizuku version, **turn off its effect before installing**
   the new APK. Stop any standalone trial too. The engines share a lock.
2. Install the APK, open **Fold Transition**, and allow notifications.
3. Connect to a trusted Wi-Fi network and enable **Developer options → Wireless debugging**.
4. Tap **Initial connection setup** (`최초 연결 설정`) in this app. Open Android's
   **Pair device with pairing code**, keep that dialog open, then enter its six-digit
   code in the **Fold Transition notification**.
5. Once pairing succeeds, return to the app and tap **Enable effect** (`효과 켜기`).
   Stop using **Disable effect** (`효과 끄기`) or the notification's stop action.

The UI is currently Korean. If discovery or notifications are unavailable, use
split screen to keep Android's code dialog open while entering its pairing port
and code through **Enter port and code manually** (`포트와 코드 직접 입력`). The
pairing port differs from the connection port on the main Wireless debugging page.

The first system approval remains necessary; packaging cannot grant shell privileges
by itself. This app stores its own encrypted ADB identity and reuses it on reconnect.
Your computer's or Shizuku's pairing does not authorize this identity. Clearing app
data, reinstalling without retaining data, or revoking/expiring Android's ADB
authorization can require pairing again. See [Android's ADB documentation](https://developer.android.com/tools/adb#wireless-android11-command-line).

## USB removal and recovery

Unplug USB **before** enabling the new connection and test a physical fold on the
cover, inner display, and awake lock screen. Also test unplugging during an active
effect. A Samsung ADB restart can interrupt the engine; the app attempts to reconnect
with its saved identity while wireless debugging remains available. This is not a
promise of uninterrupted animation or permanent system installation.

The foreground service checks the engine every five seconds and retries connection
failures with a delay of up to 30 seconds. Engine errors remain visible until you
turn the effect off and on. Closing the activity leaves the service running. If the
connection ends, the shell engine cleans up; a 45-second watchdog is a fallback.

Enabled state and intensity survive restarts. Boot and app-update receivers attempt
to restore the service, but **this version does not turn wireless debugging on**.
After a reboot, Wi-Fi and wireless debugging must be available and Android must allow
the background service to run. If you force-stop the app, open it again. Battery
restrictions, lost authorization, disabled debugging, or missing Wi-Fi can prevent
recovery. Check the status in Fold Transition; repeated pairing is unnecessary when
the authorization is still valid.

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
| `app/` | Settings UI, local ADB pairing and connection, foreground service, recovery |
| `system/` | Shared compositor engine, fold state machine, gradients, JVM tests |
| `tools/` | Standalone ADB trials and sensor diagnostics |
| `legacy/` | Earlier screen capture and Shizuku implementations, excluded from the build |
| `docs/` | Device findings and implementation notes |

The app requests Android's `INTERNET` permission for local ADB sockets and uses
mDNS to discover this device's debugging ports. Actual ADB connections target only
`127.0.0.1`; the app does not connect to discovered remote devices. It has no analytics,
screen capture, or accessibility service. Sensor diagnostics stay on the device.
The ADB private key is encrypted using Android Keystore and excluded from backup.

See [the integrated connection design](docs/LOCAL_ADB_APP.md) for implementation
and verification details.

## License

[MIT](LICENSE) · Copyright © 2026 keepYaoung.
Bundled dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
