# V2 verification record — 0.4.14

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

## 0.4.6 relative-rotation cover driver

The user confirmed the 0.4.5 shape renders, but its progress jumps instead of following
opening angle. A 120-second read-only probe registered public accelerometer, gyro,
gravity and game rotation vector sensors (about 5,760 events each), plus 13 hinge
events with only 0/90/180 values. Vendor angle subscriptions still returned false.
Gyro Y changed sign with opening/closing in the instructed trial. Whole-device
rotation remains a confounder; this is not validated continuous hinge sensing.
The probe printed its timed summary, then exited with status 137 (Killed), which is
recorded as abnormal termination rather than a clean pass. Raw data stays in ignored
build output and is not committed.

Cover retreat now integrates relative gyro Y rotation only after a fold is activated.
A bounded 500ms sample history compensates some detection delay. Gyro alone cannot
activate the effect. Time-based provisional retreat was removed; missing gyro uses
coarse hinge progress with a fixed initial onset. Deadband, stale sample rejection,
90° integration cap and 1.5° estimated reversal release limit drift. Inner/V1 strength
is unchanged. Gyro is requested at 50Hz for V2; long-term power cost is unverified.
Unit checks cover integration, stationary hold, stale data and reversal. Actual
visual synchronization and whole-device false motion require physical feedback.

For 0.4.6, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded and the fresh 600-second USB trial reported COVER_GYRO registered=true
and READY. Visual synchronization feedback remains pending.

## 0.4.7 inner-left and lock-screen extension

The user accepted the 0.4.6 gyro effect and requested it on the lock screen and inner
left display. Inner captures immediately retain only the left half. The hinge edge
is pinned, the outer left edge recedes during closing and returns during opening.
Gyro direction is reversed for closing; tests cover both motion signs and the inner
quadrilateral's fixed edge. Existing V1 rendering is unchanged.

Awake lock screens now attempt normal redacted capture without changing secure or
protected capture policies. Denied/blank results use a live black perspective mask,
which clips the apparent shape but does not transform protected screen content.
Lock/unlock is part of renderer identity, so transitions discard captures and stale
results. No files or uploads are produced. Physical confirmation is pending.

For 0.4.7, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded on the connected Fold7 and the 600-second USB trial reached READY
with gyro registered. Lock capture/fallback and inner perspective remain pending
physical verification; startup alone is not recorded as visual success.

## 0.4.8 perspective plus per-panel system gradient blur

The requested earlier blur is now composed above V2 at a higher layer order, so it
can blur the visible snapshot as well as live lock-mask content. It reuses the
verified 32-region layout: cover strongest right, inner left half strongest at its
outer edge. Radius uses the previous smooth angle curve driven by the rendered V2
depth estimate (maximum 180px cover / 160px inner at 100% intensity). Release opacity
fades both effects. V1 remains the independent legacy mode.

A new motion/panel/rotation/lock identity removes the previous blur before starting
a fresh snapshot. Stop, idle completion and screen-off clear both layers. JVM tests
cover radius bounds, monotonic response, panel ranges and complete release. Physical
confirmation of the combined blur and perspective is pending.

For 0.4.8, JVM/DEX tests, APK assembly and Android lint passed. The APK installed
successfully and a fresh 600-second USB trial reached READY with gyro registered.
This confirms startup only; combined visual behavior awaits user feedback.

## 0.4.9 cover-to-inner plane alignment

The user reported that the inner left plane did not align with the right near full
opening. Inner initialization previously imposed a minimum depth, and gyro residuals
were not overridden by the public fully-open endpoint. The new path carries the
recent cover depth across the same motion sequence (up to 1.5s), excludes old gyro
history on that handoff, and treats a >=179-degree public reading as zero depth.
The renderer reaches exact zero in 140ms, restoring the identity quadrilateral and
zero depth blur. This does not improve continuous hinge-angle access or predict the
endpoint before the sensor reports it. Tests cover carried depth, endpoint priority,
finite settling, identity geometry and avoiding double integration at handoff.
Physical alignment confirmation remains pending.

For 0.4.9, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded and a new 600-second USB trial reached READY with gyro registered.
Physical handoff/alignment confirmation remains pending.

## 0.4.10 capture-transition recovery

A 0.4.9 device trace showed a successful fully-open alignment, followed by a locked
inner fallback, then an unlock capture throwing `V2 refuses secure capture content`.
The engine logged ERROR and STOPPED about 54 seconds after startup: this was not the
600-second expiry. Its process remained alive holding the lock because close removed
the expiry callback without quitting the standalone Looper. It was explicitly stopped
before replacement. The user also reported a black inner-left frame; its exact cause
was not captured, but blank snapshots were previously checked only while locked.

Capture-unavailable and platform permission/secure-content errors now fall back on
both locked and unlocked panels. Blank/transparent snapshot checks apply on both
panels, with bitmaps recycled before falling back. Secure/protected capture remains
disabled. Unexpected unlocked API failures remain fatal, and a fatal standalone
failure now quits its Looper after cleanup rather than retaining the process lock.
Tests cover unlock secure-layer rejection, wrapped permission denial, unexpected API
failure classification and visible/blank pixels. Device recurrence checks are pending.

For 0.4.10, JVM/DEX tests, APK assembly and Android lint passed. The failed
standalone process was explicitly stopped; the APK installed successfully and
a fresh 600-second USB trial reached READY. Lock/unlock recurrence verification
and the reported black-frame reproduction remain pending.

### Connected 0.4.10 recovery observation

The new trace exercised capture fallback on an unlocked inner display and a locked
inner display, then changed locked=true to locked=false with another Unavailable
capture. It continued to V2_ALIGN and END without ERROR/STOPPED. Later folds produced
fresh inner snapshots and layers, confirming that fallback did not latch the engine
off. This verifies recovery in the observed sequence, not that every black-frame
cause has been eliminated; visual recurrence feedback remains pending.

## 0.4.11 stronger projected-edge blur

The user accepted the recovery version and requested stronger blur only at the
projected edges and black surround. The original 32 interior gradient regions remain
unchanged. Two soft border bands follow the slanted top/bottom and receding outer
edge, with 1.8x blur radius capped at 360px. All regions are clipped to the selected
pane, including only the inner left half. Region count is bounded at 162, and edge
geometry participates in the render cache key. Zero radius removes all edge regions.
Tests cover unchanged base regions, pane bounds, stronger edge radius, excluded image
center, and full release. Physical appearance/performance confirmation is pending.

For 0.4.11, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded and a new 600-second USB trial reached READY. Edge appearance and
performance still need physical confirmation.

## 0.4.12 anti-aliased perspective outline

The perspective snapshot now draws as an anti-aliased Path filled with a filtered
BitmapShader, using the same homography for texture mapping. This explicitly
rasterizes edge coverage rather than relying on transformed drawBitmap filtering
for the polygon boundary. The shader is reused per snapshot and detached before
bitmap recycling. Existing live-mask path AA, geometry, blur strength and capture
policy remain unchanged. Device visual confirmation is pending.

For 0.4.12, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded and a fresh 600-second USB trial reached READY. Rasterized edge quality
has not yet been visually confirmed on the device.

## 0.4.13 shorter onset and bounded capture wait

The user requested approximately 300ms start waiting, especially cover onset and
cover-to-inner handoff. V2 now polls at up to 10Hz and uses a single 300ms sustained
burst window, bypassing the second confirmation in FoldMotion. V1 keeps its prior
4Hz/two-stage path. V2 rearm delays are reduced to 300ms; idle hold remains 1.5s.
Active panel handoff does not require new motion confirmation. A capture pending
for 300ms falls back to a live mask and invalidates late results for that cycle.
This bounds application capture waiting, not end-to-end physical sensing latency.

Added --no-edge-blur for repeatable temporary USB comparisons; the user's current
comparison keeps additional border blur disabled, while preserving regular gradient
blur and AA. This option does not change installed app settings. Tests cover the
300ms gate, no double confirmation, and unchanged idle hold. Physical timing and
higher polling cost remain unverified.

For 0.4.13, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded and the 600-second USB trial reached READY with --no-edge-blur. Physical
onset/handoff timing feedback remains pending.

## 0.4.14 idle dissolve back to the live screen

The user requested a natural dissolve revealing the original screen after the
1.5-second stationary hold. V2 idle release now uses a 620ms smootherstep envelope;
V1 and non-idle releases keep 420ms. A single compositor layer alpha fades snapshot,
black backing, live mask and gradient together, while the blur radius follows the
same visibility. Perspective geometry holds during release rather than returning
abruptly to flat. The existing midpoint/end regression was updated for the new
idle duration; tests also check monotonic visibility and complete release.
Device visual confirmation is pending. Additional border blur remains disabled in
the temporary comparison, and 300ms onset confirmation is unchanged.

For 0.4.14, JVM/DEX tests, APK assembly and Android lint passed. Installation
succeeded and a new 600-second USB comparison reached READY with extra edge blur
disabled. Physical idle-dissolve appearance remains pending.

## 0.4.15 preserve the first inner handoff frame

The user reported missing inner-left opening geometry. The endpoint could zero
both the incoming depth and target before the inner capture became drawable.
Preserve carried cover depth separately from the live target, seed the renderer
only after capture/fallback readiness, then use the existing 140ms alignment.
A pending cover capture now supplies sensed depth instead of overwriting handoff
with renderer zero. Without a recent same-sequence cover handoff, full-open entry
remains flat. JVM regressions cover endpoint-before-entry and exact final alignment.
Physical confirmation is pending; extra edge blur remains off in the USB trial.

For 0.4.15, JVM/DEX tests, APK assembly and lint passed. APK installation
succeeded and the 600-second USB trial reached READY. Physical handoff appearance
still requires user confirmation.

## 0.4.16 sustain confirmed folds with gyro motion

0.4.15 device logs showed a cover opening ending about 2.12s after the 90-degree
sample, before the subsequent 180-degree sample started a fresh, flat inner cycle.
Gyro drove perspective but never refreshed FoldMotion activity; no vendor bursts
were logged during this trial. Forward gyro above 0.08 rad/s now sustains only an
already active, non-releasing confirmed fold. Stationary noise, missing samples,
reverse motion and inactive state do not refresh the hold. The 1.5s stationary
hold and 620ms idle dissolve remain. Gyro reversal releases now log their reason
and panel to distinguish them from idle expiry. Tests exercise a slow opening
across a multi-second coarse-sensor gap and subsequent stationary release.
Physical validation of this fix remains pending.

JVM/DEX tests, APK assembly and Android lint passed for 0.4.16. Installation
succeeded; the 600-second USB trial reached READY on the cover display with
additional edge blur disabled. Physical confirmation remains pending.

## 0.4.17 stronger travel and slower inner depth consumption

User feedback: both panels barely change angle and finish too early. The 0.4.16
trace included a gyro-reversal release before inner capture could draw, and another
inner entry at depth 0.0774 (scale 0.952). Cover/inner-closing gyro depth now has
2.5x gain. Inner opening reduces incoming depth proportionally across gyro progress,
so a small incoming depth no longer disappears after equally small rotation.
The cover snapshot fade starts at 80% visual progress instead of 45%. Reversal
still requires 1.5 degrees but must continue for 120ms above the motion deadband;
a single reverse sample no longer releases. Full-open alignment and the 1.5s idle
hold/620ms dissolve are unchanged. These are visual estimates, not hinge calibration.
Physical appearance remains pending; extra edge blur stays off in USB comparison.

0.4.17 passed JVM/DEX tests, APK assembly and lint. APK installation succeeded
and the 600-second USB trial reached READY with extra edge blur disabled.
Physical visibility and timing feedback are pending.
