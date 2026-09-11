# Android Also Could Fold

**English** | [한국어](README.ko.md)

**Keep One UI. Make folding and unfolding feel smoother.**

An experimental Android app for physical fold and unfold transitions on Galaxy Z
Fold. It keeps One UI and controls the compositor through a built-in local ADB
client. Version 0.4.0 adds **V2: cover snapshot + black gradient**, with the earlier
**V1 live blur** available through the in-app switch.

## Effects

| Mode | Cover display | Inner display |
| --- | --- | --- |
| **V2 · default, experimental** | Takes one in-memory snapshot at motion onset; a black gradient fades in from the right, then moves out to the right and dissolves as opening progresses; the snapshot dissolves into the live screen | The left half is captured; its center/hinge edge stays fixed while the outer left edge recedes when closing and returns when opening |
| **V1 · live blur** | Right-heavy blur during opening | Left-half blur, strongest at the outside edge |

Turn off **V2 · capture + black gradient** (`V2 · 캡처 + 블랙 그라디언트`) in the
app to return to V1. Mode changes restart the engine if enabled.

- Overall effect intensity: **50–150%**.
- Perspective snapshots use anti-aliased polygon edges with filtered texture sampling (0.4.12).
- V2 adds stronger blur around the projected edges and black surround (0.4.11),
  preserving the existing interior gradient. The border follows the moving shape
  and fades with the effect.
- Blank captures and protected-layer/permission denials use the live mask plus blur
  on either panel, including unlock transitions. They do not stop the engine (0.4.10).
- Cover-to-inner handoff carries the recent rendered depth instead of resetting it.
  The fully open hinge signal settles the inner left plane to exactly flat within
  140 ms, removing residual perspective and its depth blur (0.4.9).
- V2 also applies system gradient blur above the perspective layer: strongest on the
  cover’s right side and the inner left half’s outer edge. Radius follows the same
  relative-depth estimate; it decreases as the inner plane opens and returns flat.
  Lock-screen snapshot and mask paths both receive this blur. It dissolves with the
  effect after the existing idle hold. The combined rendering needs physical tuning.
- **1.5 seconds without detected movement → a smooth 420 ms release**, including the retained V2 snapshot.
- Reported reversals release the current effect smoothly.
- No effect on an off display or AOD. On the lock screen, V2 attempts a redacted snapshot; blocked or blank captures use a live perspective mask.
- Settings, pairing identity, foreground connection monitoring and a notification stop action are retained.

Version 0.4.7 pins the cover snapshot’s left edge and makes only its right
edge retreat, forming a perspective trapezoid over black. The black backing remains
until release so the live screen does not show around the image. Inner rendering
mirrors this on the left half, keeping its hinge edge fixed. Lock-screen captures
continue to exclude secure/protected content. If unavailable or blank, a moving black
mask covers the area outside the projected shape; it does not warp protected content. This is an experimental visual estimate, not world-space
stabilization: there is no viewer tracking or continuous measured hinge angle.
V2 drives cover and inner-left retreat from relative gyroscope Y rotation after a fold
has been detected, instead of elapsed-motion timing. It samples at approximately
50 Hz and requests a dissolve after 1.5° of estimated reverse rotation. This is
**not a measured hinge angle**: moving the whole device can affect the estimate.
Without fresh gyro data, it falls back to coarse hinge steps and a fixed early onset.
Stationary time alone does not advance retreat. Physical tuning remains pending.

V2 snapshots are transient memory buffers, never files or uploads. Protected content
is excluded from capture; excluded regions may appear blank. The snapshot freezes
visible content briefly while touch still reaches the underlying app. V2 capture,
rotation, panel handoff and physical appearance still need device verification.

## Compatibility and limitations

The earlier blur engine has been tested on **Galaxy Z Fold7 SM-F966N / Android 16**.
Execution is blocked on other models; other Fold7 variants are not yet supported. The engine depends on Samsung's private SurfaceControl APIs, so
compatibility needs to be checked after One UI updates.

On this device, the public hinge sensor mainly reports **0 / 90 / 180 degrees**.
Early opening on the cover and early closing on the inner display both require
a second sustained motion signal to reduce reactions to brief handling noise.
This adds roughly 0.5 seconds to inferred onset compared with a single accepted
burst; reported angle changes start the effect without this extra wait.

Early motion is inferred from vendor event timestamps in `dumpsys sensorservice`.
The engine does not read hidden continuous angle values. Very slow movement and
small reversals may be missed. Diagnostic polling runs at 4 Hz while the screen
is interactive; long-term battery impact has not been measured.

## Verification status

| Area | Status |
| --- | --- |
| Blur on cover, inner display and awake lock screen | Physically confirmed with the earlier engine setup |
| Build, lint and JVM motion regressions | Passed, including V2 angle response and release |
| V2 visuals | 0.4.6 cover effect accepted by the user; 0.4.7 inner-left and lock-screen extension awaits physical confirmation |
| Samsung capture API compatibility | Matched to previously pulled framework; runtime permission pending |
| Encrypted identity storage, reload and tamper rejection | Passed on Fold7 |
| Dedicated pairing notification in 0.3.2 | Registration confirmed on-device; completed code entry not yet confirmed |
| Cover sensitivity adjustment in 0.3.1 | JVM tests passed; physical feedback pending |
| App-owned wireless pairing, USB independence and reboot recovery | Not yet verified end to end |

See [the 0.4.12 verification record](docs/V2_VALIDATION.md) for fixes and remaining
physical checks.

Choose the **temporary USB trial** below for a ten-minute test, or the
[single-app setup](#single-app-setup-041) for saved settings and wireless connection
attempts. Neither mode requires root.

## Try it without installing an app

**You can try the V1 blur effect without installing either the Fold Transition
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
- The default V1 trial does not capture the screen. Add `--v2` to test the experimental snapshot/black-gradient effect; its snapshots stay in memory. Neither mode saves or uploads screen content.

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

## Single-app setup (0.4.12)

**Shizuku is no longer required.** This APK includes local wireless ADB pairing,
engine startup, and reconnection. One UI is preserved, and the V1 blur remains
available alongside the new V2 effect. The new connection path remains experimental; see the verification
status above before relying on it for everyday use.

1. If upgrading from the Shizuku version, **turn off its effect before installing**
   the new APK. Stop any standalone trial too. The engines share a lock.
2. [Build the debug APK](#build-and-verification) and install it, then open
   **Fold Transition** and allow notifications.
3. Connect to a trusted Wi-Fi network and enable **Developer options → Wireless debugging**.
4. Tap **Initial connection setup** (`최초 연결 설정`) in this app. Open Android's
   **Pair device with pairing code**, keep that dialog open, then enter its six-digit
   code in the **Fold Transition notification**.
5. Once pairing succeeds, return to the app, choose V2 or V1, and tap
   **Enable effect** (`효과 켜기`).
   Stop using **Disable effect** (`효과 끄기`) or the notification's stop action.

The pairing notification stays available for ten minutes. Expand **Fold Transition
initial connection** (`Fold Transition 최초 연결`) to reveal **Enter code**
(`코드 입력`). If it has expired or an APK update
interrupted setup, tap **Initial connection setup** again.

The UI is currently Korean. If pairing-port discovery or notifications are unavailable, use
split screen to keep Android's code dialog open while entering its pairing port
and code through **Enter port and code manually** (`포트와 코드 직접 입력`). The
pairing port differs from the connection port on the main Wireless debugging page.
Manual entry covers **pairing only**: engine startup still needs automatic discovery
of this phone's connection port. There is no manual connection-port field yet.

### If setup gets stuck

- **No code-entry notification:** tap **Initial connection setup** in Fold Transition
  before opening Android settings. Check that both app notifications and its
  **Initial connection · Enter code** (`최초 연결 · 코드 입력`) notification category
  are allowed. Expand the pairing notification, rather than the ongoing effect notification.
- **Invalid port/code or pairing failure:** keep the Android code dialog open and use
  its current six-digit code and pairing port. Reopening that dialog can change both.
- **Paired, but waiting for wireless debugging:** check Wi-Fi and wireless debugging.
  Pairing success alone does not mean the blur engine is running. The app must show
  **Running · app connection** (`실행 중 · 앱 자체 연결`).
- **Another engine is running:** stop the previous app effect or standalone trial,
  then disable and re-enable this app's effect. Do not delete its lock file.

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

The app checks the engine on an approximately five-second schedule. Connection
failures use a retry delay capped at 30 seconds; discovery and connection attempts
can add time, so this is not a maximum recovery time. Engine errors remain visible until you
turn the effect off and on. Closing the activity leaves the service running. If the
connection ends, the shell engine is designed to clean up; a watchdog checks for
45 seconds without requests at five-second intervals as a fallback.

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
development signing key; release APKs require separate signing. To update an existing
installation with `adb install -r`, keep the same signing key. A differently signed
APK cannot update it in place; uninstalling would remove app data and the saved
pairing identity.

The device identity test is separate from the local build and lint checks; see
[the validation notes](docs/LOCAL_ADB_APP.md#validation) before running it.

## Repository layout

| Path | Purpose |
| --- | --- |
| `app/` | Settings UI, local ADB pairing and connection, foreground service, recovery |
| `system/` | V1 blur / V2 snapshot renderer, fold state machine, gradients, JVM tests |
| `tools/` | Standalone ADB trials and sensor diagnostics |
| `third_party/` | Vendored local ADB library, provenance and license texts |
| `legacy/` | Earlier screen capture and Shizuku implementations, excluded from the build |
| `docs/` | Device findings and implementation notes |

The app requests Android's `INTERNET` permission for local ADB sockets and uses
mDNS to discover this device's debugging ports. Actual ADB connections target only
`127.0.0.1`; the app does not connect to discovered remote devices. It has no analytics
or accessibility service. V2 captures the active panel through the shell compositor API, without
MediaProjection, including the awake lock screen with secure/protected content
excluded. For the inner display, it immediately retains only the left half. Sensor diagnostics and transient snapshots stay on the device.
The ADB private key is encrypted using Android Keystore and excluded from backup.

See [the integrated connection design](docs/LOCAL_ADB_APP.md) for implementation
and verification details.

## To Samsung and Android device manufacturers

We respect the manufacturers who move quickly and bring advanced technology to
the world. Yet even with everything you do well, there is still much to improve
in how these devices feel to use.

Give designers and engineers more authority to shape the experience. Make room
for a more emotional approach to design: the motion, transitions, and small
details that make everyday interactions feel natural and satisfying. We hope
the care put into the experience will match the ambition of the technology.

## License

[MIT](LICENSE) · Copyright © 2026 keepYaoung.
Bundled dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
