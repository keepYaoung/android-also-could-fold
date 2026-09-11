# V2 verification record — 0.4.5

## Completed locally

- JVM motion regressions and DEX build: passed (`python3 tools/fold-system.py build`).
- APK and instrumentation APK compilation, Android lint: passed. Compilation of the
  instrumentation APK is not recorded as an on-device test run.
- Regressions cover inferred-motion confirmation, same-angle panel changes, new
  transition identities, opening/closing strength bounds, missed-frame cleanup,
  inner reveal and screenshot dissolve.
- Capture-policy tests cover both legacy booleans and newer named redaction
  constants. Unknown methods or missing constants abort capture configuration.
- The previously pulled SM-F966N framework was inspected. It exposes
  `android.window.ScreenCaptureInternal$DisplayCaptureArgs` and synchronous
  `ScreenCaptureInternal.captureDisplay(DisplayCaptureArgs)`. Its builder uses
  `setSecureContentPolicy(int)` and `setProtectedContentPolicy(int)`, with named
  REDACT constants in `ScreenCapture$ScreenCaptureParams`. The original V2 path
  used classes/methods absent from this firmware; 0.4.1 supports the inspected API
  and retains the legacy path. Method existence does not prove runtime permission.
- Physical display token lookup, physical address ID, secure layer creation,
  layer stack assignment, show and remove methods exist in the recorded framework.

## Fixes from this review

1. Identify each new physical motion so an earlier cover snapshot cannot survive
   into a new opening on the same display.
2. Invalidate snapshots on rotation, including 180-degree rotations with unchanged
   dimensions.
3. Clear pending motion confirmation on a same-angle panel change.
4. Track queued capture bitmaps explicitly so removing Handler callbacks during
   shutdown also releases their pixel memory.
5. Resolve the firmware's renamed capture API and explicitly redact protected and
   secure content. No capture policy is weakened to make the API call succeed.

## First connected USB trial

After the initial offline review, SM-F966N was connected and APK 0.4.1 was installed
successfully. Existing paired/enabled preferences were present. Wireless debugging
was off and remained off, so app-owned wireless execution was not exercised.
The app effect was disabled through its UI to avoid overlapping engines.

A 600-second standalone USB trial (`--early --v2`) reached READY and received opening
and closing hinge events. Observed cycles returned END with no reported engine error.
The user confirmed that an effect is visible but needs adjustment; detailed visual
feedback is pending. This does not establish that both panels, capture orientation,
idle timing, or lock-screen behavior passed. A later layer-list observation found no
V2 layer while idle; it did not sample a visible transition.

## Remaining device checks

The following items are **not passed** and must not be inferred from build, partial
USB observations or source inspection:

- Verify retained settings after installation (0.4.1 installation itself succeeded).
- Complete app-owned pairing and confirm `실행 중 · 앱 자체 연결`.
- Cover snapshot capture permission, correct orientation and right-side gradient.
- Inner left-half gradient reveal, closing direction and panel handoff.
- 1.5-second idle dissolve, reversal, rapid consecutive folds and actual sensitivity.
- Stop, screen-off, locking mid-transition and mode-switch cleanup; no stale snapshot.
- Protected content handling and capture failure behavior.
- V1 fallback, foreground/background survival and automatic reconnect.
- USB removal after wireless startup and during an active session.
- Reboot recovery once Wi-Fi/wireless debugging are available.
- Capture latency, memory use and sustained battery impact.

For installation and first pairing, follow the README. Do not run the standalone
trial beside an enabled app engine. Test physical movements and report the observed
result separately from logs; a RUNNING message alone does not prove visual output.

## 0.4.2 cover motion tuning

User feedback requested a right-edge entrance followed by move-out and dissolve.
The cover shadow now enters from the right, then travels back out to the right while
fading. The retained snapshot also dissolves into the live screen. Spatial progress
is monotonic during opening so idle release does not rewind the shadow. Progress
still uses coarse hinge samples and provisional motion timing, not continuous angle
measurement. Inner rendering is unchanged.

JVM profile/state tests and DEX build passed; APK assembly and Android lint passed.
The 0.4.2 APK installed successfully on the connected Fold7. A new 600-second
USB trial reached READY with the V2 backend; physical confirmation of the revised
timing remains pending. App-owned wireless operation was not exercised.

## 0.4.3 cover visibility and estimated perspective

The user reported that the 0.4.2 cover effect did not render as intended. Code review
found that the first coarse 90-degree sample completed the reveal, while multiplying
angle strength and reveal opacity weakened its entrance. The cover now uses a separate
visibility/intensity envelope, maps 90 degrees to mid-progress and keeps the snapshot
visible longer. The snapshot receives bounded inverse yaw (up to 24 visual degrees)
around its center, suggesting a plane that stays facing forward. This is an estimated
perspective effect, not measured world-space stabilization or viewer tracking.

Regression tests cover the coarse 90-degree sample and bounded transform. Snapshot
and layer-ready logs report only dimensions/panel, never captured content. Physical
confirmation of visibility, correction direction and timing remains pending.

The 0.4.3 JVM/DEX build, APK assembly and Android lint passed. APK installation
succeeded on the connected Fold7. A 600-second USB V2 trial reached READY; this
confirms engine startup only. Visual feedback is pending.

## 0.4.4 front-facing depth retreat

The user clarified that the cover image should move backward as the hinge opens;
the 0.4.3 inverse-yaw correction did not produce the intended effect. It is replaced
by centered, uniform perspective scaling: 1 / (1 + 0.65 * progress). The plane stays
front-facing and retreats rather than counter-rotating. A black backing and snapshot
fade as one group to avoid showing a full-size duplicate around the smaller image.
Coarse hinge samples and provisional timing still limit angle fidelity. Inner and
lock-screen gradient paths remain unchanged; depth applies only to a cover snapshot.
Physical confirmation of retreat, timing and idle dissolve is pending.

For 0.4.4, JVM/DEX tests, APK assembly and Android lint passed. Installation on the
connected Fold7 succeeded and a fresh 600-second USB trial reached READY. This is
startup verification; the revised depth effect still awaits physical confirmation.

### 0.4.4 follow-up: retreat not observed

The user reported no visible retreat. The trial logged a cover layer but no preceding
snapshot-ready message. In the current renderer a cover layer without a completed
snapshot uses the capture-disabled path; this is consistent with the keyguard gate
at that moment, but the previous log did not record the gate directly. A later policy
query showed the device unlocked and cannot establish its state during the fold.
Added content-free diagnostics for snapshotAllowed, snapshot presence and scale.
A fresh USB trial reached READY; an unlocked-cover repeat was requested. Do not mark
the depth effect visually confirmed until that repeat is observed.

## 0.4.5 left-anchored cover perspective

Two diagnostic opening cycles explicitly reported snapshotAllowed=false and
snapshot=false. The keyguard-gated path therefore produced no image retreat; this
was not evidence that the bitmap transform ran and failed. The user clarified the
geometry: pin the left edge, recede/shrink only the right edge, fill behind with black.
A four-point perspective transform now replaces uniform centered scaling. The black
backing remains until release rather than fading with opening progress. JVM checks
cover fixed left corners, bounded right-edge motion and a non-inverted quadrilateral.
Unlocked-cover visual confirmation is still required. Capture on lock remains disabled.

Read-only sensor inventory confirmed accelerometer, gyroscope, gravity, linear
acceleration, rotation vector and game rotation vector entries. Existing early
motion detection uses Folding Angle timestamps. These auxiliary sensors are not yet
fused into an angle estimator; presence does not establish independent measurements
from both panels or the ability to separate whole-device rotation from hinge motion.

For 0.4.5, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded on the connected Fold7. A new 600-second USB trial reached READY;
unlocked-cover perspective rendering has not yet been physically confirmed.
