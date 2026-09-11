# V2 verification record — 0.4.1

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
