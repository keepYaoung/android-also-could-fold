> Current app: see [Shizuku app](SHIZUKU_APP.md) and [README](../README.md).
> The dated notes below describe successive ADB prototypes; later entries supersede earlier behavior.

# Fold-only system compositor experiment

## Scope

Keep Samsung One UI as HOME and preserve normal app layout, navigation and input.
Only physical hinge motion can begin an effect. Rotation, app launches, display
configuration changes and startup alone must never begin an effect.

This is an ADB-shell compositor prototype, **not an OEM firmware patch or a
replacement for Samsung's WindowManager transition handler**. It does not control
panel power or prepare the destination app layout. The user has confirmed visible closing blur on the current Samsung firmware.
The user has also confirmed opening after the panel-off fix. Start latency
remains limited by the coarse public hinge sensor; continuous-angle access is
being investigated.

## Device inspection — 2026-09-11

- Model: SM-F966N (`q7q`).
- Fingerprint: `samsung/q7qksx/q7q:16/BP4A.251205.006/F966NKSSCBZH3_OKRCBZH3:user/release-keys`.
- One UI property: `80500`.
- Build: `user`, `ro.debuggable=0`.
- Verified boot: `green`; bootloader and vbmeta state: locked.
- ADB runs as UID 2000, SELinux `shell` domain. `su` was not found in PATH.
- HOME resolves to `com.sec.android.app.launcher/.activities.LauncherActivity`.
- Shell has `INTERNAL_SYSTEM_WINDOW`, `MANAGE_ACTIVITY_TASKS`,
  `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, `ACCESS_SURFACE_FLINGER`,
  `READ_FRAME_BUFFER`, `DEVICE_POWER`, `CONTROL_DEVICE_STATE`.
  These grants do not prove every Binder operation is allowed by this firmware.
- Public `hinge_angle Wakeup` sensor is available. ADB helper successfully
  subscribed and received a 180-degree sample without displaying an effect.
- Samsung `Folding Angle Non-wakeup` sensor (65686) requires
  `com.samsung.permission.SSENSOR`. Sensorservice lists a system-server subscriber;
  access from shell has not been established.
- `wm disable-blur` (query with no arguments):
  `Blur supported on device: false`, `Blur enabled: false`.
- A hidden SurfaceControl effect layer can be created and removed as shell.
  Initial creation alone did not establish rendering. A subsequent physical
  closing trial produced visible blur, confirmed by the user.
- Samsung methods are present: `setBackgroundBlurColorCurve`,
  `setBackgroundBlurScale`, `setBlurClamp`, `setBlurRegionsEx`.
  The experimental runner currently tests `setBackgroundBlurRadius`.
- Initial inspection found no enabled accessibility service or active
  MediaProjection session, so the earlier capture prototype was not interfering.

Read-only firmware copies and extracted symbol inventories are in ignored
`build/system-inspection/`; do not commit or redistribute the OEM binaries.

## Implementation

`system/src/dev/tommy/foldshell/system/FoldShell.java` runs through `app_process`
as ADB shell. It creates a bufferless SurfaceControl effect layer only when fold
motion needs visible blur, and removes it when finished. It does not capture
screens, register an input window, override device state, change HOME, hook
SystemUI, write global settings, or install a boot service.

`FoldMotion.java` is the platform-independent state machine:

- Startup sensor sample only establishes a baseline.
- Opening: cover blurs; on inner display the left half resolves.
- Closing: inner left half blurs; after actual cover handoff a 320 ms timed
  resolve works even when the last angle is already zero.
- Mid-motion direction reversal is supported.
- Stationary half-fold: cancel after 4.5 s with no meaningful angle change.
- Absolute transition timeout: 10 s.
- Lock: cancel and remove layer, retaining an angle baseline.
- Brief panel-off handoff: remove layer and pause rendering; retain the recent
  physical-fold state so the display-on callback can resume it. Display changes
  alone still cannot create a transition.
- Rotation/configuration callbacks cannot start effects.
- No screenshot continuity anchor: the real underlying content stays in place.

The SM-F966N profile uses shortest-side width of 600 dp to distinguish inner from
cover, based on observed 750 dp and 411 dp configurations. The helper refuses
other models. Different display scaling/multi-display modes are not validated.

## Build and run

Requires Python 3, JDK 17+, Android SDK platform 36 and build-tools 36.0.0.
`ANDROID_SDK_ROOT` and `JAVA_HOME` override macOS defaults.

```sh
python3 tools/fold-system.py build
python3 tools/fold-system.py run --seconds 600
python3 tools/fold-system.py stop
```

The build runs the pure-Java state-machine regression tests and produces
`build/system-backend/dex/classes.dex`. `run` pushes only this DEX into
`/data/local/tmp` and runs a temporary foreground ADB command. Use `--serial`
when more than one device is attached. Duration is bounded to 1–3600 seconds;
a second helper cannot start while the file lock is held.

Unlock the device and fold/unfold physically. Check logs for `BEGIN`, `DISPLAY`,
`LAYER created`, `LAYER removed`, and `END`. API success does not establish visible
blur: also verify on the device. Do not force device states to simulate a physical
fold. The old Accessibility/MediaProjection prototype should not run simultaneously.

## Validation status

- Java compilation and DEX build: passed.
- Pure-Java regressions: passed (late closing handoff, reversal, stationary
  timeout, initial sample, invalid sample, no configuration-only start).
- Real sensor subscription and idle behavior: passed.
- Initial 15-second run cleaned up but returned exit 137 when app_process returned;
  explicit `System.exit(0)` was attempted, but the later probe still returned
  137 after normal cleanup on this firmware. This exit-status issue remains open.
- Physical closing callback: passed; layer created and removed in device logs.
- User confirmed closing blur is visible, but opening blur was absent in trial 1.
- SurfaceFlinger layer list after the fold contained no FoldTransition layer.
- Opening fault: resetting the angle baseline during the transient panel-off
  interval discarded the closed posture; next 90-degree event became a baseline
  rather than an opening event. Fixed by preserving hinge state across panel-off
  and resuming only a recent physical-fold transition when the display is on.
- Trial 2: user confirmed both closing and opening effects are visible, but
  begin only in particular angle ranges. Logs show 0/90/180-degree samples. A 3 s gap between 90 and 180 degree samples
  also showed the original 1.8 s timeout was too short for slow physical folds;
  extended no-motion timeout to 4.5 s with a 10 s absolute cap.
- Framework `Blur supported: false` did NOT rule out direct SurfaceControl blur
  on this Samsung firmware. User observation contradicts that broad inference.
  Do not change global blur settings to enable this path.
- This is still not a completed OEM transition integration.

## Next decision

If the fold trial produces no blur, inspect the Samsung `SemBlurInfo` /
`SemBlurRegionData` path and the native renderer capability before expanding
this backend. Do not globally enable blur or replace the launcher to hide this
limitation. Firmware has the following relevant symbols (presence alone does
not prove they are active or externally callable):

- SystemUI: `com.android.systemui.unfold.*`,
  `com.android.systemui.blur.domain.interactor.SecPanelWindowBlurInteractor`,
  `com.android.systemui.blur.data.repository.SecCapturedBlurRepositoryImpl`.
- Framework: `android.view.SemBlurInfo`, `android.view.SemBlurRegionData`.
- Services: `com.android.server.wm.UnfoldingPolicy`.

For true transition ownership, inspect Samsung's WM Shell/SystemUI handler and
UnfoldingPolicy implementation and test whether a narrowly filtered remote
transition registration can coexist with Samsung's existing handler. A granted
shell permission is insufficient evidence. Root/framework hooking would require
a separately established installation route on this locked production device.

## References

- [AOSP SurfaceFlinger / WindowManager](https://source.android.com/docs/core/graphics/surfaceflinger-windowmanager)
- [AOSP unfold handler](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/libs/WindowManager/Shell/src/com/android/wm/shell/unfold/UnfoldTransitionHandler.java)
- [AOSP window blur capabilities](https://source.android.com/docs/core/display/window-blurs)
- [AOSP SurfaceControl source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/SurfaceControl.java)


## Early motion detection follow-up

The user requires effects to begin as soon as physical movement begins. Public
sensor samples currently arrive around 0/90/180 degrees; interpolation cannot
recover the unobserved start time. Do not claim that timeout/curve tuning solves
this. The retained firmware's InputManagerService subscribes to the Samsung
Folding Angle sensor and applies angle thresholds/hysteresis internally.

Added a read-only probe for sensor types 36, 65686 and 65695:

```sh
python3 tools/fold-system.py probe --seconds 30
```

It reports subscription denial explicitly and records distinct angle values. It
never starts visual effects. Vendor-sensor access from shell is not yet validated:
ADB disconnected before this probe could run. Once reconnected, test subscription
first, then check readable sensor nodes / early lid signals as needed.


## Reconnection diagnosis — 2026-09-11

Actual ADB-shell probe results after reconnecting:

- Type 36, `hinge_angle Wakeup`: subscription accepted.
- Type 65686, `Folding Angle Non-wakeup`: `registerListener` returned false.
- Type 65695, `lid_angle_fusion Wakeup`: `registerListener` returned false.
- `com.samsung.permission.SSENSOR`: `signature|privileged`; not granted to shell.
- `semGetLidState()` is readable (returned 0 in the open posture).
- `semRegisterOnLidStateChangedListener`: SecurityException, requires LID_STATE.
- `com.samsung.android.permission.LID_STATE`: `signature`; not granted to shell.
- `/sys/class/sensors`, digital_hall_info and digital_hall_dbg reads denied.
- digital_hall_thd is readable but contains thresholds, not live angle samples;
  it was not modified.
- Sensorservice exposes vendor event timestamps but masks the values.
- Input device inventory includes `flip` exposing only binary `SW_LID`;
  no continuous hinge-angle input device appeared in the inspected inventory.
  No touch/key input event recording was performed.

These results establish that the investigated shell-accessible routes do not
provide continuous motion-start detection in both directions. A binary lid
signal could potentially improve opening timing, but is not a substitute for
continuous closing detection from a fully open position. This is a remaining
requirement, not an animation-curve bug that has been fixed.

The required next capability is an authorized system/privileged component with
SSENSOR access (and appropriate firmware policy), or an established root/hook
route. Availability on this locked device remains unverified. No bootloader,
SELinux, sensor thresholds, permission grants or firmware settings were changed.

## Timestamp-start experiment (option 1)

After the user selected the non-root paths, a 120-second read-only comparison
was collected with `tools/probe-fold-start.py`. The public sensor was subscribed
concurrently using `tools/fold-system.py probe --seconds 120`.

- 471 sensorservice dumps: median 95.7 ms, maximum 152.0 ms including ADB overhead.
- 168 new vendor events, 5 public events (including subscription baseline).
- The expected 180 -> 90 -> 0 -> 90 -> 180 sequence was recorded.
- Dense vendor-event batches preceded public 90-degree reports in both directions.
- Requiring two consecutive polls with at least four fresh vendor events would
  trigger about 0.498 s before closing's public 90-degree report and 0.263 s before
  opening's report in this single trial (host observation times).
- This does **not** measure latency from the true physical start; no independent
  ground-truth angle/physical-start timestamp was collected.
- Sparse vendor events occurred outside the dense fold sequence as well, so a
  single timestamp change must not be treated as proof of a fold.
- The dedicated flip/SW_LID reader collected no events in this trial. This does
  not establish that the switch never reports events; buffering/access/driver
  behavior needs further investigation. Option 2 has not been integrated.

An opt-in experimental implementation is now available:

```sh
python3 tools/fold-system.py run --seconds 600 --early
```

`VendorMotionMonitor` runs the sensorservice query on a background thread at 4 Hz
only while interactive/unlocked. It reads only timestamp metadata, establishes an
initial baseline and disables itself after repeated failures. `VendorEventGate`
requires two consecutive batches of >=4 distinct fresh events; repeated/stale
records and isolated bursts do not count. This polling has a power/CPU cost and
is an experimental, bounded session, not a production always-on service.

A candidate may start only from a known fully open/closed public-sensor baseline.
It starts a weak effect (maximum 35% blur), confirms on a real public angle change,
or fades out within one second without confirmation. Startup and repeated-hint
cooldowns reduce false positives. Metadata still cannot prove physical movement
or provide a continuous angle; the user is testing both improved onset and
whole-device-tilt false positives.

Java compilation, DEX build and pure-Java tests passed, including candidate expiry,
confirmation, unknown-posture rejection, history/jitter/repeated-event rejection.
The --early version is running on the device for a 10-minute trial; visual/onset
validation is pending. Default behavior without --early remains public-sensor only.

## Angle response and left-pane spatial gradient

User requested angle-dependent intensity and a left-to-right decrease **within the
inner display's left half**. Implemented in `BlurProfile` and `FoldShell`:

- Confirmed angle-to-strength mapping uses smoothstep rather than linear phase.
  Inner: 180 degrees -> zero, 90 degrees -> maximum. Cover: 0 -> zero, 90 -> maximum.
- Rendered radius approaches the target over a ~160 ms response time rather than
  jumping between coarse samples. This interpolates the effect, not measured angle.
- Early candidates retain their separately bounded <=35% strength; metadata does
  not become a fabricated angle. Delayed closing-cover handoff retains its timed
  resolve because the physical angle is already zero at handoff.
- Inner left pane has 32 non-overlapping vertical regions. Each uses the current
  radius with smoothly decreasing blend alpha: 100% at outer left down to 6% near
  the central hinge. The right pane has no blur region.
- A single effect surface carries all regions. Uniform background blur is cleared
  in inner mode; regions are cleared on return to uniform cover blur.
- Region float-array layout (14 values including clip rectangle) was verified
  against this firmware's BackgroundBlurDrawable.BlurRegion.toFloatArray().
  This remains a device-specific hidden API backend.

Build/DEX generation and regression tests passed: angle response, monotonic spatial
falloff, exact gap-free coverage for odd-width panes, no spill into the right pane,
and zero-blur empty regions. Deployed with --early for a 10-minute trial. Physical
appearance and possible banding are awaiting user feedback. Existing public-angle
coarseness still prevents true continuously measured angle-driven animation.

## Latest motion envelope and graceful cancellation

User approved the spatial gradient, then requested stronger cover transitions,
progressively stronger sustained closing, and natural clearing on reversal or a
one-second hold. This revision supersedes the earlier 4.5-second hold timeout and
35% universal provisional cap:

- Cover radius ceiling: 140 px; inner: 100 px. Inner spatial falloff is retained.
- Early cover opening ramps to 65% target over 220 ms. This remains an inference,
  not a measured angle. Unconfirmed candidates are bounded to 1.6 s before release.
- A confirmed inner closing target combines 65% angle strength with up to 35%
  additional strength from sustained movement (1.4 s of credited event time).
- Vendor burst callbacks now carry the actual newest sensor timestamp. Dense
  bursts refresh movement activity even before another coarse public angle arrives.
  Long gaps do not accumulate as continuous closing.
- One second with no qualifying movement starts an explicit 420 ms smoothstep
  fade, preserving the prior output instead of removing the surface immediately.
- A reported angle delta of >=0.15 degrees opposite to the active direction starts
  the same release, rather than abruptly switching the direction of the effect.
- Closing-cover handoff resolves over 480 ms with a smoothstep curve.
- Lock/screen-off still hides the layer immediately. This is independent of the
  aesthetic release for movement holds/reversals.

Limits: diagnostic event timestamps do not reveal direction. Therefore a small
reversal inside the public sensor's coarse angle bucket cannot be detected
reliably. An accelerometer alone cannot distinguish device tilt from relative
hinge rotation. No claim is made that this implements sub-degree real feedback.
Very slow movement producing sparse events may also be treated as a hold.

Validation: updated JVM regression tests and DEX build passed, covering strength
increase during sustained closing, exact hold threshold, continuous fade start,
fade completion, a delivered 0.2-degree reversal, late closing handoff, stronger
cover onset, provisional expiration, jitter gating, and gradient bounds.
Deployed for a bounded 10-minute physical trial; user feedback pending.


Cover-opening regression follow-up: previous trial logged no EARLY_BEGIN and only
an opening at public 180 degrees, so the complete on-device cause is not yet
confirmed. Fixed a reproducible state-machine defect: movement away from a closed
or fully open endpoint starts a new cycle even if the prior cycle is still fading
or suspended during panel-off. Intermediate-angle reversals still fade normally.
A fresh early hint can replace the opposite completed cycle after its endpoint
cooldown. Vendor callbacks expire stale state and attempt a new hint before
crediting activity, so opening bursts do not first refresh stale closing state.
Added EARLY_BURST diagnostics (event age, direction, acceptance; no sensor values).
JVM tests and DEX build pass, including early opening after suspended closing and
public opening during closing fade. New 600-second early trial started; actual
cover onset still requires user confirmation.


Cover opening now uses a mirrored native blur-region gradient: 6% blend at the
left edge to 100% at the right edge, across the full cover. Inner left-pane
strong-left gradient and closing-cover uniform resolve remain unchanged.
The rendering cache includes gradient direction so equal-radius direction changes
still update the compositor. Build and regression tests passed, including mirrored
intensity and region coverage. Started a 600-second device trial; appearance
requires physical user verification.


Lock-screen and stronger motion profile (user requested): removed the keyguard
suppression from sensor handling, early detection, and rendering. Interactive
lock screens now use the same physical-fold effect; screen-off/AOD remains hidden
by the existing interactive + display-ON checks. No unlock or security settings
are changed. Cover peak radius increased 140 -> 180 px; inner peak 100 -> 160 px.
Inner closing angle contribution increased .65 -> .8. Early cover opening now
rises from .65 toward .9 strength with sustained fresh motion activity, then the
public angle profile takes over. This is event-duration-based visual estimation,
not continuous measured angle. Spatial gradients and one-second hold / reversal
release are preserved. Build and JVM regressions passed including progressive
cover opening and hold release. A 600-second trial was started; lock-screen
visibility and subjective intensity await physical verification.


User approved current visual effect; changed inactivity hold from 1000 to 1500 ms.
After the last detected movement, blur holds for 1.5 seconds then dissolves using
the existing 420 ms smooth release. Removed the earlier provisional-only 1600 ms
lifetime cutoff so it cannot preempt the inactivity hold during continued early
motion; the overall 10-second safety limit remains. Endpoint resolve and reversal
release are unchanged. Updated boundary/fade tests and DEX build passed, and a
600-second device trial started.
