# Device calibration — SM-F966N

Target: Samsung Galaxy Z Fold, model `SM-F966N`, Android 16 / API 36.

## Signals recorded
Every physical fold cycle logs:
- hinge angle,
- monotonic timestamp,
- current `screenWidthDp`,
- transition begin/end,
- configuration/display width handoff and time since transition start.

## Calibration objective
Find two device-specific values:
1. `openHandoffAngle`: angle at which cover display hands off to inner display.
2. `closeHandoffAngle`: angle at which inner display hands off to cover display.

Those values divide the renderer into pre-handoff and post-handoff segments, avoiding a generic 0..180 linear mapping.

## Automatic learning
The app now stores separate open/close handoff angles. Each observed configuration width switch contributes a 30% weighted sample, so repeated real folds converge on the device's actual transition behavior without hardcoded tuning.
