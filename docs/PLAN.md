# Fold Transition — implementation plan

## Product target
Keep Samsung One UI, Samsung launcher, system navigation and every Android app intact. Replace only the perceived fold/unfold transition with an iPhone-Fold-like continuous canvas transition.

This is not a launcher and not an iOS mock shell.

## Architecture
- `FoldTransitionService` — AccessibilityService running above normal One UI/apps.
- `TYPE_HINGE_ANGLE` — Samsung hardware hinge sensor drives a real 0..180-degree progress stream.
- Accessibility screenshot API — captures the actual current screen without a recurring MediaProjection consent dialog.
- `TYPE_ACCESSIBILITY_OVERLAY` — temporarily draws the transition above whichever app/Home screen is visible.
- `TransitionOverlayView` — geometry renderer; current frame expands/contracts continuously with hinge motion.
- Physical display handoff remains Samsung/Android's responsibility. The overlay masks that handoff and then yields back to the real screen.

## Phase 1 — global plumbing (implemented)
- Keep One UI as default Home.
- Accessibility service registration.
- Detect real Galaxy Fold hinge angle globally.
- Capture actual foreground screen.
- Draw a non-touchable accessibility overlay globally.
- Start/finish transitions from physical hinge motion.

## Phase 2 — handoff calibration
- Measure exact angle at which Samsung switches cover ↔ inner display.
- Capture timestamps: hinge event, config change, display switch, first rendered frame.
- Tune pre-switch and post-switch animation windows separately.
- Eliminate black/blank frames and visible overlay popping.

## Phase 3 — iPhone-style geometry
- Replace naive horizontal scaling with anchor-preserving geometry.
- Preserve perceived left/right content positions across display switch.
- Add center-fold occlusion model, perspective, edge shading and spring finish.
- Different geometry profiles for Home, portrait app, landscape/media if useful.

## Phase 4 — target-frame handoff
- Capture a second frame after destination display becomes live.
- Morph source snapshot into destination snapshot rather than source-only stretch.
- Blend only after geometry aligns, minimizing cross-fade perception.

## Phase 5 — system-level escalation only if needed
If Accessibility overlay ordering/timing cannot mask Samsung's display handoff cleanly enough:
- investigate ADB-granted/system-adjacent privileges,
- Shizuku where useful,
- LSPosed/SystemUI hooks only as a last-mile path.

Do not replace One UI or install a custom ROM unless explicitly required.

## Success criterion
While using arbitrary real Android apps or One UI Home, physically open/close the Galaxy Fold. The foreground content should appear to remain one continuous canvas expanding/contracting with the hinge, and immediately become the untouched real app/One UI again after the transition.


## Phase 3b — reference-video two-pane model
- Opening starts by dissolving/blurring the whole cover display.
- After cover→inner handoff, the cover composition maps conceptually to the RIGHT pane.
- The newly available LEFT pane appears first as blurred/skeleton content, then resolves to the real unfolded UI.
- Closing reverses asymmetrically: only LEFT pane dissolves/blurs; RIGHT pane stays sharp and becomes the cover continuity anchor.
- This replaces previous whole-screen stretch/reveal models.
