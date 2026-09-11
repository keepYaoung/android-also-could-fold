# iPhone Fold-like Transition Prototype — Progress Log

## Project

- Folder: `android-also-could-fold`
- Path: `<repository-root>`
- Target device: Samsung Galaxy Z Fold, model `SM-F966N`
- Android: 16 / API 36
- Default launcher must remain Samsung One UI (`com.sec.android.app.launcher/.activities.LauncherActivity`)

## Product Goal

Keep Samsung One UI and all normal Android apps intact, and change only the *perceived fold/unfold transition* so the device behaves more like the reference “iPhone Fold” interaction.

The final intended visual model is intentionally simple:

### Opening

1. Cover screen starts sharp.
2. As the hinge opens, the cover screen progressively `blur-out`s.
3. Around the physical display handoff, the unfolded UI becomes active.
4. The cover composition conceptually maps to the **right pane** of the unfolded layout.
5. The newly introduced **left pane** begins blurred and progressively `blur-in`s to sharp as the fold opens.

### Closing

1. The unfolded screen starts sharp.
2. Only the **left pane** progressively `blur-out`s as the hinge closes.
3. The right pane remains the continuity anchor.
4. Around the mid/late closing motion, the cover screen should appear blurred.
5. As the device reaches closed, the cover screen progressively `blur-in`s to sharp.

The current direction avoids stretch, zoom, synthetic skeleton UI, or full-screen fake morphs.

---

## Early Experiments That Were Rejected

### 1. Custom launcher / fake iOS shell

An early build implemented a custom-drawn launcher shell with widgets, icons, dock, etc.

This was rejected because the actual requirement is:

- keep One UI completely intact
- do not replace HOME/launcher
- only modify fold/unfold transition perception

The custom launcher files were removed and the HOME intent was removed from the manifest.

### 2. Screenshot stretch transition

First transition prototype:

- capture current screen screenshot
- stretch/scale screenshot according to hinge progress
- reveal the destination display after handoff

Problem:

- UI looked rubbery / stretched
- reference video does not scale UI elements that way

This was abandoned.

### 3. Full screenshot reveal model

Second prototype:

- keep source screenshot geometry stable
- after display handoff, capture destination screenshot
- reveal destination from the hinge side

Problem:

- still looked like a fake layer being composited over One UI
- did not match the asymmetric two-pane behavior of the reference video

This was abandoned.

### 4. Skeleton-overlay model

Third prototype attempted:

- blur cover on opening
- place cover composition on right pane
- draw synthetic skeleton bars on left pane
- resolve skeleton to real destination

Problem:

- fake skeleton looked artificial
- blur proxy initially looked like pixel mosaic rather than optical blur

This was simplified.

---

## Reference Video Re-analysis

The reference behavior was reinterpreted as a **two-pane continuity transition**.

Key observations:

- Cover UI maps perceptually to the **right side** of the unfolded layout.
- Opening should not feel like the entire cover screen physically expands.
- The newly exposed left area should resolve from blur to the final UI.
- Closing is asymmetric: only the unfolded **left side** should dissolve/blur.
- The right side should visually survive into the cover transition.

The current mental model is therefore:

```text
OPEN

cover
[      COVER      ]
      blur-out

handoff

[ blurred LEFT ][ RIGHT continuity ]
       ↓
[  sharp LEFT  ][ final RIGHT      ]
```

```text
CLOSE

[ LEFT ][ RIGHT ]
   ↓ blur-out

[blur][ RIGHT continuity ]

handoff / late close

[ blurred COVER ]
       ↓
[  sharp COVER  ]
```

---

## Device / Sensor Findings

### Device state support

`cmd device_state print-states` exposes states such as:

- CLOSED
- TENT
- HALF_OPENED
- OPENED
- CONCURRENT_INNER_DEFAULT
- CONCURRENT_OUTER_DEFAULT

### Public hinge sensor

The device exposes:

- Sensor name: `hinge_angle Wakeup`
- Android type: `android.sensor.hinge_angle`
- Sensor type ID: `36`
- Permission: none

The app subscribes using:

```kotlin
Sensor.TYPE_HINGE_ANGLE
```

### Samsung internal folding sensor

A second sensor exists:

- `Folding Angle Non-wakeup`
- type: `com.samsung.sensor.folding_angle`
- Samsung sensor ID: `65686`
- permission: `com.samsung.permission.SSENSOR`

This sensor appears more suitable for finely sampled fold motion, but ordinary third-party apps cannot access it without Samsung/system privilege.

### Important limitation

The public hinge sensor on this device has often appeared effectively quantized around:

- `0°`
- `90°`
- `180°`

So true continuous, Apple-like angle tracking is limited by the public sensor surface.

---

## Measured Display Handoff Behavior

Observed configurations:

### Cover

- `screenWidthDp ≈ 411`
- physical display: `1080 x 2520`
- density: `420`

### Inner

- `screenWidthDp ≈ 750`
- physical display: `1968 x 2184`

### Opening

Observed example:

```text
hinge=90°
widthDp=411
transition starts

~302 ms later
411dp -> 750dp configuration handoff

hinge reaches 180°
```

Opening handoff is approximately around 90° on the observed cycle.

### Closing

Observed example:

```text
hinge=90°
widthDp=750
transition starts

hinge reaches 0°
widthDp still 750

~0.8–0.9 s later
750dp -> 411dp handoff
```

This is important: Samsung delays the actual cover-display switch until very late in the physical closing sequence.

---

## Accessibility Screenshot Prototype

Before MediaProjection, the app used:

```kotlin
AccessibilityService.takeScreenshot(...)
```

with:

- `TYPE_ACCESSIBILITY_OVERLAY`
- `FLAG_NOT_FOCUSABLE`
- `FLAG_NOT_TOUCHABLE`
- `FLAG_LAYOUT_IN_SCREEN`
- `FLAG_LAYOUT_NO_LIMITS`

### Crash fixed

Initial code tried to use the AccessibilityService context's display directly and crashed on Android 16:

```text
UnsupportedOperationException:
Tried to obtain display from a Context not associated with one
```

This was fixed by using `Display.DEFAULT_DISPLAY` instead.

### Why screenshot mode was dropped

Even after fixing crashes, the result still felt like:

- frozen screenshot
- fake transition overlay
- insufficient continuity with moving content

So the architecture was moved to MediaProjection.

---

## MediaProjection Architecture

Current direction:

```text
MediaProjection
    ↓
VirtualDisplay
    ↓
ImageReader
    ↓
latest live Bitmap frame
    ↓
LiveFrameBus
    ↓
FoldTransitionService
    ↓
Accessibility overlay
    ↓
GPU RenderEffect blur
```

### Main components

#### `LiveProjectionService`

Foreground service using:

- `foregroundServiceType="mediaProjection"`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MEDIA_PROJECTION`

It receives MediaProjection consent from `MainActivity`.

#### `LiveFrameBus`

In-process frame bus that passes the newest capture frame to the accessibility transition service.

#### `FoldTransitionService`

Still owns:

- hinge sensor
- fold direction detection
- display configuration change detection
- accessibility overlay

#### `TransitionOverlayView`

Now uses Android GPU `RenderEffect.createBlurEffect(...)` rather than bitmap downsample blur.

This fixed the earlier “pixel mosaic” look.

---

## Current Angle → Blur Mapping

For now, handoff reference is intentionally fixed to `90°` while stabilizing the prototype.

### Opening

Before handoff:

```text
0° -> 90°
cover blur: 0 -> max
```

After handoff:

```text
90° -> 180°
left pane blur: max -> 0
right pane: continuity anchor
```

### Closing

Before handoff:

```text
180° -> 90°
left pane blur: 0 -> max
right pane: sharp
```

After handoff:

```text
90° -> 0°
cover blur: max -> 0
```

Because the real Samsung cover handoff happens very late, the implementation still needs further work to make the cover appear visually *before* the physical switch.

---

## MediaProjection Stability Work

### Crash: recycled Bitmap

First live implementation crashed with:

```text
RuntimeException:
Canvas: trying to use a recycled bitmap
```

Cause:

- old live frames were recycled immediately after publishing a newer frame
- HWUI/ImageView could still be rendering the old bitmap

Fixes applied:

- keep live frames alive longer before recycle
- freeze transition source into an owned bitmap copy
- lower live capture rate to ~10 fps
- capture at approximately half physical resolution

### Current live capture sizes

Inner example:

```text
984 x 1092
```

Cover example:

```text
540 x 1260
```

### VirtualDisplay recreation failure

On inner -> cover handoff, the first implementation:

1. destroyed the old VirtualDisplay
2. tried to create a new VirtualDisplay using the same MediaProjection token

Android 16 rejected this:

```text
Reusing token: Throw exception due to invalid projection
```

That killed MediaProjection during closing, which is why the cover blur-in did not appear.

### Latest fix

Instead of destroying/recreating the virtual display, the current code now attempts to:

- keep the same MediaProjection session alive
- create a new ImageReader for the new dimensions
- call `VirtualDisplay.resize(...)`
- swap the VirtualDisplay surface
- close the old ImageReader after a short grace period

This latest path has been built and installed, but still needs physical validation after fresh MediaProjection consent.

---

## Current UX Status

### Opening

User feedback:

> “오 펼때는 괜찮은데?”

So the current live GPU blur direction for opening is considered promising.

### Closing

Current problem:

- left-side blur-out works
- cover blur-in did not work because MediaProjection died during the physical display switch

The VirtualDisplay resize/surface-swap fix was added specifically for this.

---

## Current Files of Interest

```text
app/src/main/java/dev/tommy/foldshell/
├── CalibrationStore.kt
├── FoldTransitionService.kt
├── LiveFrameBus.kt
├── LiveProjectionService.kt
├── MainActivity.kt
├── TransitionGeometry.kt        # legacy / earlier geometry experiments
└── TransitionOverlayView.kt
```

Other relevant files:

```text
app/src/main/AndroidManifest.xml
app/src/main/res/xml/accessibility_service_config.xml
docs/PLAN.md
docs/CALIBRATION.md
```

---

## Current Build / Install

Typical build command:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew clean assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Accessibility service component:

```text
dev.tommy.foldshell/dev.tommy.foldshell.FoldTransitionService
```

---

## Next Work

1. Re-authorize `Start Live Capture -> Entire screen` after the latest APK install.
2. Validate that VirtualDisplay resize survives inner -> cover transition.
3. Confirm live frames continue after fold:
   - inner: `984x1092`
   - cover: `540x1260`
4. Tune close animation so cover starts becoming visible before the physical handoff.
5. Revisit angle interpolation because public hinge angle is coarse.
6. If public hinge data proves insufficient, investigate a privileged/system-adjacent route for Samsung folding sensor access.
7. Only after stability, tune:
   - max blur radius
   - alpha/dissolve curve
   - left/right pane split
   - continuity anchor duration
   - exact handoff offset

---

## Current Working Principle

Do not try to recreate iOS or replace One UI.

The prototype should only manipulate **perceived continuity** around Samsung's existing display handoff:

```text
OPEN  = cover blur-out  + left blur-in
CLOSE = left blur-out   + cover blur-in
```

Everything else should remain real One UI / real app UI.


---

## 2026-09-11 — fold-only ADB system compositor trial

User requested keeping One UI and intervening only during folding/unfolding.
Added `system/` Java helper and `tools/fold-system.py`; details and exact device
findings are in `docs/SYSTEM_BACKEND.md`.

This backend uses a temporary SurfaceControl blur effect layer, no screen capture,
no launcher replacement and no forced device-state switch. It is not yet a WM
Shell handler replacement. Shell layer creation and public hinge subscription
work on the locked production SM-F966N. Although `wm disable-blur` reports standard
blur unsupported/disabled, the user confirmed visible blur when closing.

Trial 1: closing visible; opening absent. Logs showed transient panel-off cleanup
reset the angle baseline and the next opening sample became an initial sample.
Preserved the physical-fold state across the brief panel handoff and kept the
closed angle baseline when locked. Trial 2 deployed for physical validation.
Tests cover delayed cover handoff, reversal, coarse samples, idle timeout,
configuration-only no-op and resuming across a brief panel handoff.


Trial 2 user confirmation: both directions now work, but only in certain angle
ranges. Requirement clarified: start immediately when physical folding/unfolding
begins. Public samples are coarse (0/90/180); added a read-only Samsung sensor
probe (types 65686 / 65695). ADB disconnected before vendor access could be tested.
Latest local build also lengthens the no-motion timeout for observed slow folds;
that timeout change is not a solution for missing early sensor events.


Reconnect diagnosis: vendor sensors 65686/65695 both reject ADB-shell subscriptions.
SSENSOR is signature|privileged; LID_STATE is signature. The lid-state getter works
but listener registration throws SecurityException. Digital-hall live diagnostic
reads are denied; sensorservice masks angle values. Input inventory exposes only
a binary flip/lid switch. Immediate movement-start detection in both directions
remains blocked on access to an appropriate signal. No firmware/security settings
changed. See SYSTEM_BACKEND.md for the evidence and remaining capability needed.

Root feasibility checked at user request. Current F966NKSSCBZH3 reports
ro.boot.other.locked=1. Installed SecSettings OemUnlockPreferenceController
explicitly returns unavailable for that value, confirming the normal OEM unlock
path is blocked on this build. Bootloader remains locked and Knox warranty
property remains 0. No unlock/flash attempted; see docs/ROOT_FEASIBILITY.md.

Non-root options 1/2 measured over 120 seconds. Vendor event bursts preceded public
90-degree updates; a two-batch detector leads by ~0.498 s closing / ~0.263 s opening
in the recorded trial. Sparse noise also exists. No flip input events were captured.
Implemented option 1 behind --early: two dense fresh-event batches start a capped,
unconfirmed blur, then public angle confirms it; unconfirmed candidates fade within
1 second. Built/tested and deployed for user comparison; no root or firmware changes.

User requested angle-based blur intensity and strong-left/weak-right falloff inside
the INNER LEFT pane. Added BlurProfile smoothstep angle response, ~160 ms temporal
smoothing and 32 non-overlapping native blur regions with 100% -> 6% blend alpha
across that pane. Right pane remains unblurred; cover uses uniform blur. Verified
Samsung's 14-float region layout from installed framework. Built, tested and
started a 10-minute --early trial; spatial appearance awaiting user confirmation.

User approved the inner left-pane gradient. Increased cover peak blur (140 px),
added a stronger early opening envelope, and credited sustained vendor event bursts
toward closing strength. One-second no-motion now starts a 420 ms smooth release;
reported reverse angle changes also release instead of switching abruptly.
Small reversals invisible to the coarse public sensor remain unobservable; no
accelerometer workaround claimed. Updated tests passed and device trial started.


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


Shizuku app implementation: shared engine now runs indefinitely in a dedicated
Looper owned by an AIDL UserService. UI offers enable/disable and 50–150% intensity;
settings persist, Binder arrival restores the daemon, and a visible foreground
guardian performs bounded recovery. Legacy capture sources moved outside the build.
MIT license and installation/limitations README added. User confirmed the new app
preserves the effect on the actual device. Killing the app process left daemon PID
10553 running; the foreground guardian returned as a new app process.

Forced Shizuku-server restart now verified: old engine PID 12033 exited on server
Binder death; exactly one replacement engine (23715) started under the new server.
No second persistent engine remained. Android builds, lint (no errors), JVM tests,
and GitHub build/test steps passed. Wireless pairing troubleshooting added to README.


0.2.1: user reported over-sensitive closing on the inner panel. Added an inner-only
confirmation to early hints: two accepted motion bursts 250–700 ms apart are
required. A lone burst stays invisible; expired/duplicate hints do not confirm.
Public hinge changes bypass the extra wait; cover opening keeps its previous
single-burst behavior. This adds about one 500 ms burst interval to inferred inner
closing and does not provide true direction discrimination. Regression tests pass
for noise, sustained motion, baseline resets, direct angle and unchanged cover onset.
Device sensitivity improvement remains subject to physical user feedback.
