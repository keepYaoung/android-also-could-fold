# Android Also Could Fold

**English** | [한국어](README.ko.md)

**Keep One UI. Make folding and unfolding feel smoother.**

An experimental Android app for physical fold and unfold transitions on Galaxy Z
Fold. It keeps One UI and controls the compositor through a built-in local ADB
client. Version 0.5.0 offers three modes in one APK: **V1 live blur**, **V2 cover
snapshot + perspective black gradient**, and the new **V3 flat gradient blur**.

The folding animation in this project was inspired by [this post on r/GalaxyFold](https://www.reddit.com/r/GalaxyFold/comments/1wcacld/tried_to_recreate_the_iphone_duo_animation_on_my/). Thanks to the original creator for the inspiration.

## Effects

| Mode | Cover display | Inner display |
| --- | --- | --- |
| **V2 · default, experimental** | Takes one in-memory snapshot at motion onset; a black gradient fades in from the right, then moves out to the right and dissolves as opening progresses; the snapshot dissolves into the live screen | The left half is captured; its center/hinge edge stays fixed while the outer left edge recedes when closing and returns when opening |
| **V1 · live blur** | Right-heavy blur during opening | Left-half blur, strongest at the outside edge |
| **V4 · snapshot + gradient blur, experimental** | The V2 snapshot plane with the V3 shade on top instead of V2’s linear gradient: the folding-away side darkens with a much wider, darker gradient than V3 (60–100% of the pane, fully black at the edge) and matching blur | Same on the inner left half |
| **V3 · flat gradient blur, experimental** | The live screen stays flat and is never captured; the region folding away, from the right edge inward in proportion to measured gyro rotation, darkens with a black gradient toward the edge and is blurred throughout | Same on the left half, from the outer left edge toward the hinge |

Pick **V1 / V2 / V3 / V4** under **Effect mode** (`효과 방식`) in the app (`run --v4` for a USB trial). Mode changes
restart the engine if enabled. V3 never captures the screen, so lock-screen capture
limits do not apply; it shares V2's onset detection, gyro depth and idle dissolve.
There is no solid black block: the darkening region is a gradient, transparent at its
boundary and darkest at the outer edge, with blur that strengthens the same way. It
reaches at most 60% of the pane, a visual calibration value, not a measured hinge
angle. For a temporary USB trial use `run --v3`. A first USB trial on SM-F966N
started, drew and released the V3 layer during a physical closing; the visual
tuning is still being adjusted from user feedback.

- Overall effect intensity: **50–150%**.
- For a temporary USB comparison of V3 with plain gradient blur instead of the shade-matched blur, add `--no-edge-blur` to the `run --v3` command. This does not change app settings.
- Perspective snapshots use anti-aliased polygon edges with filtered texture sampling (0.4.12).
- V2 no longer boosts blur around the projected edges (introduced in 0.4.11, removed
  in 0.5.0 because it read as a hard border). It keeps the per-panel gradient blur.
  Its perspective retreat is also gentler: the plane recedes by at most 0.18 camera
  distances on a front-loaded curve, so the first degrees of rotation show at once
  while the total rotation stays modest.
- Blank captures and protected-layer/permission denials use the live mask plus blur
  on either panel, including unlock transitions. They do not stop the engine (0.4.10).
- Cover-to-inner handoff carries the recent rendered depth instead of resetting it.
  The fully open hinge signal settles the inner left plane to exactly flat within
  320 ms (140 ms before 0.5.0), removing residual perspective and its depth blur.
- V2 also applies system gradient blur above the perspective layer: strongest on the
  cover’s right side and the inner left half’s outer edge. Radius follows the same
  relative-depth estimate; it decreases as the inner plane opens and returns flat.
  Lock-screen snapshot and mask paths both receive this blur. It dissolves with the
  effect after the existing idle hold. The combined rendering needs physical tuning.
- **1.5 seconds without detected movement → a dissolve back to the live screen**: 700 ms for V1, 1 s for V2, 1.8 s for V3. Snapshot, black backing and gradient share one opacity envelope while geometry holds steady. Reversal and endpoint releases take 700 ms (V3: 1.4 s), and the closed-cover resolve takes 800 ms. All releases were lengthened in 0.5.0.
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

The engine has been tested on **Galaxy Z Fold7 SM-F966N / Android 16**. Since 0.5.0
the allowlist covers the whole **Fold7 family (SM-F966x)** and the **Galaxy Z Fold8 /
Fold8 Ultra family (SM-F976x)**; those other models are allowed but **unverified**.
Panel sizes are measured at runtime rather than assumed, but the engine depends on
Samsung's private SurfaceControl and capture APIs, which can differ per firmware, so
Fold8 needs a physical check before it can be called supported. Other models are
blocked.

On this device, the public hinge sensor mainly reports **0 / 90 / 180 degrees**.
V2 and V3 use one 200ms sustained-signal confirmation window for early opening/closing.
V1 retains the older second-burst confirmation. Reported hinge changes and an active
cover-to-inner handoff do not add a new confirmation wait. Sensor delivery, polling
and capture add latency; this is not a guaranteed 200ms physical-onset-to-pixel time.
V2 waits at most 450ms for a capture before using the live mask and discarding late
results for that cycle. The 1.5-second idle hold remains unchanged.

Early motion is inferred from vendor event timestamps in `dumpsys sensorservice`.
The engine does not read hidden continuous angle values. Very slow movement and
small reversals may be missed. Diagnostic polling targets 20 Hz for V2/V3 and 4 Hz for V1 while the screen
is interactive; long-term battery impact has not been measured and the 20 Hz rate doubles it relative to 0.4.x.

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

See [the 0.4.14 verification record](docs/V2_VALIDATION.md) for fixes and remaining
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
`/data/local/tmp` on the device. Allowed models are the **SM-F966x** and **SM-F976x**
families, as noted above.
If multiple devices are connected, add `-s SERIAL` to ADB commands and
`--serial SERIAL` to the Python command.

After `READY` appears, slowly fold and unfold the device using the cover, inner
display, and awake lock screen. The blur should fade after 1.5 seconds without
movement. `--early` enables inferred motion onset; omitting it uses only the public
angle sensor and may delay the effect.

- Keep the terminal session and USB connection open during the trial. Continued operation after disconnection is not guaranteed.
- **If the app is already enabled, turn the effect off in the app or its notification first.** Do not run both modes at once.
- Set `--seconds` to a value from 1–3600 to change the duration. This mode does not start automatically after a reboot.
- The default V1 trial does not capture the screen. Add `--v2` to test the experimental snapshot/black-gradient effect; its snapshots stay in memory. Add `--v3` for the flat black mask, which never captures. No mode saves or uploads screen content.

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

## Single-app setup (0.5.0)

**Shizuku is no longer required.** This APK includes local wireless ADB pairing,
engine startup, and reconnection. One UI is preserved, and the V1 blur remains
available alongside the new V2 effect. The new connection path remains experimental; see the verification
status above before relying on it for everyday use.

1. If upgrading from the Shizuku version, **turn off its effect before installing**
   the new APK. Stop any standalone trial too. The engines share a lock.
2. [Build the debug APK](#build-and-verification) and install it, then open
   **Fold Transition** and allow notifications.
3. Follow the **Connection setup** (`연결 준비`) checklist in the app. It shows live
   status for developer options, wireless debugging and pairing. **Turn on wireless
   debugging** (`무선 디버깅 켜기`) opens Developer options scrolled to the highlighted
   **Wireless debugging** switch (verified on One UI 8); Android has no dedicated page
   or intent, and the app cannot flip the switch itself, so enable it there on a
   trusted Wi-Fi network.
4. Tap **Initial connection setup** (`최초 연결 설정`) in this app. Open Android's
   **Pair device with pairing code**, keep that dialog open, then enter its six-digit
   code in the **Fold Transition notification**.
5. Once pairing succeeds, return to the app, pick V1, V2 or V3 and press **Save**,
   then turn on the effect switch at the top. Turn it off with the same switch or
   the notification's stop action.

The pairing notification stays available for ten minutes. Expand **Fold Transition
initial setup** to reveal **Enter code**. If it has expired or an APK update
interrupted setup, reopen the pairing step from **Connection → Pairing**.

On first launch the app walks through four pages, one requirement each:
notifications, developer options, wireless debugging and pairing. Each page
re-checks itself when you return from Settings. The UI defaults to English; a
button at the top right switches between English, Korean and Japanese, and the
app always uses its light theme. If pairing-port discovery or notifications are unavailable, use
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
| `system/` | V1 blur / V2 snapshot / V3 mask renderer, fold state machine, gradients, JVM tests |
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

One concrete request: **open up the hinge angle data.** On this device the public
`TYPE_HINGE_ANGLE` sensor mostly reports 0, 90 and 180 degrees, while the continuous
"Folding Angle" sensor is locked behind `com.samsung.permission.SSENSOR`. Every
effect in this project has to infer motion from gyroscope rotation and diagnostic
event timestamps instead. A public, continuous hinge angle, ideally with low latency
and a documented API, would let third-party developers build fold transitions that
are accurate rather than estimated.

## License

[MIT](LICENSE) · Copyright © 2026 keepYaoung.
Bundled dependencies retain their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Cover-to-inner opening retains the incoming cover depth until the first drawable inner frame, including when the fully-open signal arrives during capture. It then aligns to the right pane within 320ms if already fully open. Blank captures right after a handoff are retried for up to about 350ms before the live mask is used.

During an already confirmed fold, forward gyro movement also refreshes the 1.5-second stationary timer so slow unfolding can continue across sparse hinge events. Gyro alone does not start an effect.

Motion tuning: cover and inner closing depth use 2.5× gyro response; inner opening consumes a fraction of its incoming depth. The cover snapshot stays opaque through 80% visual progress. Gyro reversal requires 120ms of sustained reverse movement beyond 1.5°. These are visual calibration values, not measured hinge angles.
