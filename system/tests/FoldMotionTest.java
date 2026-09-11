import dev.tommy.foldshell.system.FoldMotion;
public class FoldMotionTest {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static java.util.List<Double> times(double... values) {
        java.util.List<Double> result = new java.util.ArrayList<>();
        for (double value : values) result.add(value);
        return result;
    }
    static void testProfile() {
        check(dev.tommy.foldshell.system.BlurProfile.strength(180, true) == 0, "open is sharp");
        check(dev.tommy.foldshell.system.BlurProfile.strength(90, true) == 1, "inner handoff maximum");
        check(dev.tommy.foldshell.system.BlurProfile.strength(135, true) == .5f, "angle changes strength");
        check(dev.tommy.foldshell.system.BlurProfile.strength(170, true) < .1f, "gentle near-open curve");
        check(dev.tommy.foldshell.system.BlurProfile.strength(0, false) == 0, "closed cover is sharp");
        float[][] bands = dev.tommy.foldshell.system.BlurProfile.regions(985, 2184, 90);
        check(bands[0][1] == 1 && bands[31][1] < .07f, "left strong, hinge edge weak");
        float[][] cover = dev.tommy.foldshell.system.BlurProfile.regions(985, 2184, 90, true);
        check(cover[0][1] < .07f && cover[31][1] == 1, "cover opening is strongest on right");
        for (int i = 0; i < cover.length; i++) {
            check(Math.abs(cover[i][1] - bands[31-i][1]) < .00001f,
                    "cover gradient mirrors inner gradient");
            check(cover[i][2] == bands[i][2] && cover[i][4] == bands[i][4],
                    "mirroring intensity preserves region coverage");
        }
        int boundary = 0;
        for (int i = 0; i < bands.length; i++) {
            check(bands[i][2] == boundary && bands[i][4] > boundary, "gap-free, nonoverlapping bands");
            boundary = (int) bands[i][4];
            check(bands[i][12] == boundary, "clip matches each band");
            if (i > 0) check(bands[i][1] <= bands[i-1][1], "monotonic gradient");
        }
        check(boundary == 985, "gradient never enters right pane");
        check(dev.tommy.foldshell.system.BlurProfile.regions(985, 2184, 0).length == 0, "zero blur no bands");
    }
    static void testGate() {
        dev.tommy.foldshell.system.VendorEventGate gate = new dev.tommy.foldshell.system.VendorEventGate();
        check(!gate.accept(times(9.5), 10), "history is not new motion");
        check(!gate.accept(times(10.01, 10.06, 10.12, 10.20), 10.25), "one burst insufficient");
        check(gate.accept(times(10.26, 10.31, 10.36, 10.44), 10.5), "sustained burst triggers");
        check(!gate.accept(times(10.26, 10.31, 10.36, 10.44), 10.75), "duplicate dump is ignored");
        check(!gate.accept(times(10.8, 10.9, 10.95), 11), "isolated jitter ignored");
        check(!gate.accept(times(11.1), 11.25), "isolated jitter cannot accumulate forever");
        check(!gate.accept(times(11.3, 11.35, 11.4, 11.45), 15), "stale events ignored");
    }
    public static class LegacyCaptureBuilder {
        boolean secure = true, protectedContent = true;
        public void setCaptureSecureLayers(boolean value) { secure = value; }
        public void setAllowProtected(boolean value) { protectedContent = value; }
    }
    public static class ModernCaptureBuilder {
        int secure = -1, protectedContent = -1;
        public void setSecureContentPolicy(int value) { secure = value; }
        public void setProtectedContentPolicy(int value) { protectedContent = value; }
    }
    public static class PolicyConstants {
        public static final int SECURE_CONTENT_POLICY_REDACT = 7;
        public static final int PROTECTED_CONTENT_POLICY_REDACT = 9;
    }
    static void testCapturePolicy() throws Exception {
        LegacyCaptureBuilder legacy = new LegacyCaptureBuilder();
        dev.tommy.foldshell.system.CapturePolicy.redact(legacy, null);
        check(!legacy.secure && !legacy.protectedContent, "legacy capture excludes protected content");
        ModernCaptureBuilder modern = new ModernCaptureBuilder();
        dev.tommy.foldshell.system.CapturePolicy.redact(modern, PolicyConstants.class);
        check(modern.secure == 7 && modern.protectedContent == 9,
                "modern capture uses named redaction policies, not assumed enum values");
        try {
            dev.tommy.foldshell.system.CapturePolicy.redact(new Object(), PolicyConstants.class);
            throw new AssertionError("unknown capture policy must fail closed");
        } catch (NoSuchMethodException expected) { }
        ModernCaptureBuilder missing = new ModernCaptureBuilder();
        try {
            dev.tommy.foldshell.system.CapturePolicy.redact(missing, Object.class);
            throw new AssertionError("missing policy constants must fail closed");
        } catch (NoSuchFieldException expected) { }
        check(missing.secure == -1 && missing.protectedContent == -1,
                "partial policy resolution never starts configuring a capture");
    }
    public static void main(String[] args) throws Exception {
        FoldMotion m = new FoldMotion();
        m.display(true, 0);
        m.angle(180, true, 0);
        check(!m.active(), "display changes and first sample cannot start effect");
        m.angle(90, true, 100);
        float initial = m.amount(100);
        m.activity(550);
        float stronger = m.amount(550);
        m.activity(1000);
        check(stronger > initial && m.amount(1000) > stronger,
                "continued closing gradually increases strength");
        float peak = m.amount(2499);
        check(!m.releasing(), "do not clear before 1.5 seconds without motion");
        check(m.amount(2500) == peak && m.releasing(), "1.5 second hold begins continuous fade");
        float mid = m.amount(2710);
        check(mid > 0 && mid < peak, "hold release fades instead of disappearing");
        check(m.amount(2920) == 0 && !m.active(), "release completes");

        m.reset(); m.angle(180, true, 3000); m.angle(120, true, 3100);
        float beforeReverse = m.amount(3200);
        m.angle(120.2f, true, 3300);
        check(m.releasing() && m.amount(3300) == beforeReverse,
                "small delivered reverse angle starts fade with no jump");
        check(m.amount(3510) < beforeReverse && m.amount(3720) == 0,
                "reverse removes the existing effect smoothly");

        m.reset(); m.angle(180, true, 4000); m.angle(90, true, 4100);
        m.amount(4100); m.angle(0, true, 4200);
        check(m.amount(4900) > 0, "wait for late Samsung closing handoff");
        m.display(false, 5000);
        check(m.amount(5000) == 1, "cover starts blurred even after angle zero");
        check(Math.abs(m.amount(5240) - .5f) < .01, "cover resolves with smooth curve");
        m.amount(5479); m.amount(5480);
        check(m.amount(5900) == 0 && !m.active(), "cover cleanup is bounded");

        FoldMotion early = new FoldMotion();
        early.angle(0, false, 0);
        check(!early.hint(false, 100), "startup burst ignored");
        check(!early.hint(false, 1000) && !early.active(), "single cover burst stays invisible");
        early.display(false, 1200);
        check(early.hint(false, 1500), "continued cover opening starts despite same-panel updates");
        check(early.amount(1720) >= .6f, "cover opening is more dramatic");
        check(early.amount(2999) >= .6f, "unconfirmed effect awaits motion until timeout");
        early.amount(3000);
        check(early.amount(3210) > 0 && early.amount(3420) == 0,
                "unconfirmed cover start also fades smoothly");
        check(!early.hint(false, 3500), "noise retrigger cooldown");
        early.reset();
        check(!early.hint(true, 4000), "unknown posture never guesses direction");
        early.angle(Float.NaN, true, 4100); early.angle(90, true, 4200);
        check(!early.active() && !early.hint(true, 5100), "invalid/half-open baseline cannot guess direction");
        early.baseline(0); early.angle(90, false, 6000);
        check(early.direction() == FoldMotion.Direction.OPENING, "closed baseline survives panel-off");
        early.display(true, 6200);
        check(early.amount(6200) == 1, "resume on inner panel after physical motion");
        early.cancel(); early.display(false, 6300);
        check(!early.active(), "configuration cannot restart effect");
        // A completed close must not consume the next opening as a reversal.
        m.reset(); m.angle(180, true, 0); m.angle(90, true, 100);
        m.amount(100); m.angle(0, true, 200);
        check(!m.hint(false, 1100), "cover confirms movement after panel-off");
        check(m.hint(false, 1600), "early opening replaces closing left behind during panel-off");
        check(m.direction() == FoldMotion.Direction.OPENING && m.amount(1820) >= .6f,
                "new cover opening has visible early envelope");
        m.angle(90, false, 1900);
        check(!m.releasing() && m.amount(1900) == 1, "public opening confirms new cycle");

        m.reset(); m.angle(180, true, 0); m.angle(90, true, 100);
        m.amount(100); m.angle(0, false, 200); m.amount(679); m.amount(680);
        check(m.releasing(), "old cover effect is fading");
        m.angle(90, false, 700);
        check(m.direction() == FoldMotion.Direction.OPENING && !m.releasing()
                && m.amount(700) == 1, "opening angle is not lost during previous close fade");
        FoldMotion progressing = new FoldMotion();
        progressing.angle(0, false, 0);
        check(!progressing.hint(false, 1000), "cover waits for confirmation");
        check(progressing.hint(false, 1500), "opening starts from closed cover");
        float onset = progressing.amount(1720);
        progressing.activity(1950);
        float continued = progressing.amount(1950);
        progressing.activity(2400);
        check(continued > onset && progressing.amount(2400) > continued,
                "continued opening strengthens early cover blur");
        progressing.angle(90, false, 2500);
        check(progressing.amount(2500) == 1, "observed angle takes over at cover peak");
        progressing.amount(4000);
        check(progressing.releasing() && progressing.amount(4420) == 0,
                "stronger opening still releases after 1.5 second hold");
        FoldMotion guarded = new FoldMotion();
        guarded.angle(180, true, 0);
        check(!guarded.hint(true, 1000) && !guarded.active(), "one inner burst must not show blur");
        check(!guarded.hint(true, 1100), "duplicate callbacks cannot confirm closing");
        check(!guarded.hint(true, 1800), "isolated bursts cannot accumulate across a gap");
        check(guarded.hint(true, 2300), "sustained inner closing confirms on the next burst");
        check(guarded.direction() == FoldMotion.Direction.CLOSING && guarded.amount(2520) > 0,
                "confirmed inner motion still produces blur");
        guarded.reset(); guarded.angle(180, true, 3000);
        guarded.hint(true, 4000);
        guarded.angle(90, true, 4100);
        check(guarded.active() && guarded.amount(4100) > 0,
                "public angle starts immediately without waiting for another hint");
        guarded.reset(); guarded.angle(180, true, 5000); guarded.hint(true, 6000);
        guarded.baseline(180);
        check(!guarded.hint(true, 6500), "baseline clears pending noise confirmation");
        guarded.reset(); guarded.angle(0, false, 7000);
        check(!guarded.hint(false, 8000) && guarded.amount(8220) == 0, "brief cover handling stays sharp");
        check(!guarded.hint(false, 8100), "cover duplicate callback cannot confirm");
        check(!guarded.hint(false, 8800), "isolated cover bursts do not accumulate");
        check(guarded.hint(false, 9300), "sustained cover movement confirms");
        guarded.reset(); guarded.angle(0, false, 10000); guarded.hint(false, 11000);
        guarded.angle(90, false, 11100);
        check(guarded.active() && guarded.amount(11100) == 1, "real cover angle bypasses hint delay");
        guarded.reset(); guarded.angle(0, false, 12000); guarded.hint(false, 13000);
        guarded.display(true, 13100); guarded.display(false, 13200);
        check(!guarded.hint(false, 13500), "panel changes discard pending cover evidence");
        FoldMotion v2 = new FoldMotion(true);
        v2.angle(0, false, 0); v2.angle(90, true, 100);
        check(v2.amount(100) == 1, "v2 inner arrival starts dark at handoff");
        v2.angle(135, true, 200);
        check(v2.amount(200) == .5f, "v2 inner shadow retreats with opening angle");
        v2.angle(180, true, 300);
        check(v2.amount(300) == .5f, "endpoint resolve never jumps darker");
        check(v2.amount(540) < .5f && v2.amount(540) > 0, "v2 inner shadow resolves smoothly");
        v2.amount(780); check(v2.amount(1200) == 0, "v2 opening cleanup completes");
        v2.reset(); v2.angle(0, false, 2000); v2.angle(180, true, 2100);
        check(v2.amount(2100) == 1 && v2.amount(2340) == .5f,
                "coarse angle jump still gives inner reveal instead of disappearing instantly");
        v2.reset(); v2.angle(0, false, 3000);
        check(!v2.hint(false, 4000) && v2.hint(false, 4500), "v2 preserves cover noise confirmation");
        check(v2.amount(4720) <= .21f, "v2 cover begins with a gentle shadow");
        v2.activity(5000); check(v2.amount(5000) > .2f, "v2 continued opening darkens cover");
        v2.amount(6500);
        check(v2.visibility(6810) == .5f, "retained screenshot dissolves with the shadow");
        check(v2.amount(7120) == 0 && !v2.active(), "v2 idle releases all visuals");
        FoldMotion cycles = new FoldMotion(true);
        cycles.angle(180, true, 0); cycles.angle(90, true, 100);
        long closingCycle = cycles.sequence();
        cycles.angle(0, false, 200); cycles.amount(200);
        check(cycles.sequence() == closingCycle, "panel handoff remains the same physical cycle");
        cycles.angle(90, false, 300);
        check(cycles.sequence() > closingCycle, "new opening invalidates previous cover snapshot");
        long openingCycle = cycles.sequence();
        cycles.angle(100, true, 400);
        check(cycles.sequence() == openingCycle, "continued angle updates do not recapture");
        cycles.reset(); cycles.angle(0, false, 5000); cycles.hint(false, 6000);
        cycles.angle(0, true, 6100); cycles.angle(0, false, 6200);
        check(!cycles.hint(false, 6500), "same-angle panel changes clear pending evidence");
        check(cycles.hint(false, 7000) && cycles.sequence() > openingCycle,
                "new inferred motion also invalidates a prior snapshot");
        // Sweep delivered angles through both directions and release with a skipped frame.
        for (boolean v2Mode : new boolean[]{false, true}) {
            FoldMotion sweep = new FoldMotion(v2Mode);
            sweep.angle(0, false, 0);
            for (int a = 1; a <= 180; a++) {
                sweep.angle(a, a >= 90, a * 20L);
                float value = sweep.amount(a * 20L);
                check(Float.isFinite(value) && value >= 0 && value <= 1, "opening strength is bounded");
            }
            sweep.amount(6000);
            check(!sweep.active(), "missed frames cannot retain completed opening forever");
            for (int a = 179; a >= 0; a--) {
                long time = 7000 + (179-a)*20L;
                sweep.angle(a, a > 90, time);
                float value = sweep.amount(time);
                check(Float.isFinite(value) && value >= 0 && value <= 1, "closing strength is bounded");
            }
            sweep.amount(14000);
            check(!sweep.active(), "missed frames cannot retain completed closing forever");
        }
        check(dev.tommy.foldshell.system.CoverReveal.left(0) == 1, "cover shadow starts outside right edge");
        check(dev.tommy.foldshell.system.CoverReveal.left(.4f) < .2f, "cover shadow enters from right");
        check(dev.tommy.foldshell.system.CoverReveal.left(1) > 1, "cover shadow exits beyond right edge");
        check(dev.tommy.foldshell.system.CoverReveal.opacity(0) == 0
                && dev.tommy.foldshell.system.CoverReveal.opacity(1) == 0, "cover shadow dissolves at both ends");
        float lastSnapshot = 1, lastExit = 1;
        for (int i = 0; i <= 100; i++) {
            float p = i / 100f;
            float snapshot = dev.tommy.foldshell.system.CoverReveal.snapshot(p);
            check(snapshot <= lastSnapshot && snapshot >= 0, "snapshot only dissolves forward");
            lastSnapshot = snapshot;
            if (p >= .4f) {
                float opacity = dev.tommy.foldshell.system.CoverReveal.opacity(p);
                check(opacity <= lastExit + .00001f, "outgoing gradient never darkens again");
                lastExit = opacity;
            }
        }
        FoldMotion cover = new FoldMotion(true);
        cover.angle(0, false, 0);
        cover.angle(90, false, 1000);
        check(cover.coverProgress(1000) == .5f, "coarse 90-degree event must not finish cover reveal");
        check(dev.tommy.foldshell.system.CoverReveal.snapshot(cover.coverProgress(1000)) > .9f,
                "cover plane remains visible at first coarse opening sample");
        check(dev.tommy.foldshell.system.CoverReveal.opacity(cover.coverProgress(1000)) > .8f,
                "cover shadow remains visible at first coarse opening sample");
        float previousScale = 1;
        float[] corners = new float[8];
        for (int i = 0; i <= 100; i++) {
            float scale = dev.tommy.foldshell.system.CoverReveal.depthScale(i / 100f);
            check(Float.isFinite(scale) && scale > .6f && scale <= previousScale,
                    "opening moves the plane farther away without enlargement or inversion");
            dev.tommy.foldshell.system.CoverReveal.corners(i / 100f, corners);
            check(corners[0] == 0 && corners[1] == 0 && corners[6] == 0 && corners[7] == 1,
                    "left edge stays pinned throughout retreat");
            check(corners[2] == corners[4] && corners[3] >= 0 && corners[5] <= 1
                    && corners[3] < corners[5] && corners[2] > 0,
                    "right edge retreats without folding or reversing the quad");
            previousScale = scale;
        }
        check(dev.tommy.foldshell.system.CoverReveal.depthScale(0) == 1,
                "closed cover snapshot starts at native size");
        check(dev.tommy.foldshell.system.CoverReveal.depthScale(.5f) < .8f,
                "first coarse opening sample produces visible depth retreat");
        dev.tommy.foldshell.system.CoverRotation rotation = new dev.tommy.foldshell.system.CoverRotation();
        check(!rotation.begin(0), "missing gyro cannot claim angle tracking");
        rotation.sample(0, 1_000_000_000L, 1000);
        rotation.sample(0, 1_020_000_000L, 1020);
        check(rotation.begin(1020), "fresh gyro can track a confirmed fold");
        for (int i = 1; i <= 50; i++) rotation.sample((float) Math.PI / 2,
                1_020_000_000L + i * 20_000_000L, 1020 + i * 20);
        check(rotation.progress() > .99f, "90 degrees of measured rotation maps to full progress");
        check(rotation.advancing(), "forward gyro reports continued confirmed movement");
        FoldMotion sustained = new FoldMotion(true);
        sustained.angle(0, false, 1000);
        sustained.angle(90, false, 1100);
        for (int t = 1120; t <= 4100; t += 20) {
            if (rotation.advancing()) sustained.activity(t);
            sustained.amount(t);
            check(sustained.active() && !sustained.releasing(),
                    "continued gyro movement must survive gaps between coarse hinge events");
        }
        sustained.display(true, 4200);
        sustained.amount(4200);
        check(sustained.active() && !sustained.releasing(), "slow opening survives inner handoff");
        sustained.amount(5600);
        check(sustained.releasing(), "actual 1.5s stop still starts dissolve");
        sustained.amount(6220);
        check(!sustained.active(), "stationary dissolve still completes");
        check(dev.tommy.foldshell.system.CoverReveal.motionDepth(.08f) - .2f < .00001f && dev.tommy.foldshell.system.CoverReveal.motionDepth(.08f) > .19999f,
                "small cover motion has a visible calibrated depth");
        check(Math.abs(dev.tommy.foldshell.system.CoverReveal.openingDepth(.2f, .1f) - .18f) < .00001f,
                "small inner motion does not consume the entire incoming plane");
        check(dev.tommy.foldshell.system.CoverReveal.openingDepth(.2f, 1) == 0,
                "full inner rotation finishes flat");
        check(dev.tommy.foldshell.system.CoverReveal.snapshot(.7f) == 1,
                "cover snapshot remains visible through substantial retreat");
        float held = rotation.progress();
        for (int i = 1; i <= 50; i++) rotation.sample(.001f,
                2_020_000_000L + i * 20_000_000L, 2020 + i * 20);
        check(rotation.progress() == held, "elapsed time and stationary noise cannot advance retreat");
        check(!rotation.advancing(), "stationary gyro noise cannot refresh idle hold");
        rotation.sample(-2, 3_040_000_000L, 3040);
        check(!rotation.advancing(), "reverse movement cannot prolong opening");
        check(!rotation.reversed(), "one reverse sample cannot end a fold");
        for (int i = 1; i <= 6; i++) rotation.sample(-2, 3_040_000_000L + i * 20_000_000L, 3040 + i * 20);
        check(rotation.reversed(), "sustained reverse rotation requests dissolve");
        rotation.reset();
        rotation.sample(1, 4_000_000_000L, 4000);
        check(!rotation.begin(4500), "stale gyro must not drive a new fold");
        rotation.reset();
        rotation.sample(0, 5_000_000_000L, 5000);
        check(rotation.begin(5000, false), "closing uses reversed gyro direction");
        for (int i = 1; i <= 25; i++) rotation.sample(-1,
                5_000_000_000L + i * 20_000_000L, 5000 + i * 20);
        check(rotation.progress() > .3f, "negative Y rotation drives closing retreat");
        rotation.sample(2, 5_520_000_000L, 5520);
        for (int i = 1; i <= 6; i++) rotation.sample(2, 5_520_000_000L + i * 20_000_000L, 5520 + i * 20);
        check(rotation.reversed(), "sustained opening during closing requests release");
        for (int i = 0; i <= 100; i++) {
            dev.tommy.foldshell.system.CoverReveal.innerCorners(i / 100f, corners);
            check(corners[2] == 1 && corners[3] == 0 && corners[4] == 1 && corners[5] == 1,
                    "inner hinge edge stays fixed");
            check(corners[0] == corners[6] && corners[0] >= 0 && corners[0] < 1
                    && corners[1] >= 0 && corners[7] <= 1 && corners[1] < corners[7],
                    "inner outer edge retreats within left half only");
        }
        int previousBlur = 0;
        for (int i = 0; i <= 100; i++) {
            int r = dev.tommy.foldshell.system.BlurProfile.depthRadius(i / 100f, false, 1, 1);
            check(r >= previousBlur && r <= 180, "cover depth blur increases monotonically and stays bounded");
            previousBlur = r;
        }
        check(dev.tommy.foldshell.system.BlurProfile.depthRadius(0, false, 1, 1) == 0,
                "flat plane has no depth blur");
        check(dev.tommy.foldshell.system.BlurProfile.depthRadius(.5f, true, 1, 1) == 160,
                "inner uses its own blur range");
        check(dev.tommy.foldshell.system.BlurProfile.depthRadius(.5f, false, 1, 0) == 0,
                "release removes depth blur completely");
        check(dev.tommy.foldshell.system.BlurProfile.depthRadius(.5f, false, 1, .5f) == 90,
                "blur fades together with snapshot visibility");
        check(dev.tommy.foldshell.system.CoverReveal.innerStart(false, .5f, .12f) == .12f,
                "inner handoff preserves the cover plane instead of resetting to coarse depth");
        check(dev.tommy.foldshell.system.CoverReveal.innerStart(true, .5f, .12f) == .12f,
                "endpoint before inner visibility preserves the incoming cover plane");
        check(dev.tommy.foldshell.system.CoverReveal.innerStart(false, .3f, Float.NaN) == .3f,
                "standalone inner start retains coarse fallback");
        check(dev.tommy.foldshell.system.CoverReveal.innerStart(true, .5f, Float.NaN) == 0,
                "fully open without a cover handoff must not invent a tilted entry");
        float entry = dev.tommy.foldshell.system.CoverReveal.innerStart(true, 0, .4f);
        check(dev.tommy.foldshell.system.CoverReveal.settleDepth(entry, 0) == .4f,
                "first visible frame retains entry even after capture waiting");
        check(dev.tommy.foldshell.system.CoverReveal.settleDepth(entry, 140) == 0,
                "entry aligns within 140ms of first visible frame");
        float settling = .5f;
        for (int ms = 0; ms <= 200; ms++) {
            float depth = dev.tommy.foldshell.system.CoverReveal.settleDepth(.5f, ms);
            check(depth >= 0 && depth <= settling, "open endpoint settles without overshoot");
            settling = depth;
        }
        check(settling == 0, "fully open plane reaches exact zero depth");
        dev.tommy.foldshell.system.CoverReveal.innerCorners(settling, corners);
        check(corners[0] == 0 && corners[1] == 0 && corners[2] == 1 && corners[3] == 0
                && corners[4] == 1 && corners[5] == 1 && corners[6] == 0 && corners[7] == 1,
                "fully open left pane matches the right pane without perspective");
        rotation.reset(); rotation.sample(1, 6_000_000_000L, 6000);
        rotation.sample(1, 6_020_000_000L, 6020);
        check(rotation.begin(6020, true, false) && rotation.progress() == 0,
                "panel handoff cannot integrate old rotation twice");
        check(dev.tommy.foldshell.system.CapturePolicy.canFallback(
                new dev.tommy.foldshell.system.CapturePolicy.Unavailable("secure"), false),
                "secure layer during unlock must not kill the engine");
        check(dev.tommy.foldshell.system.CapturePolicy.canFallback(
                new java.lang.reflect.InvocationTargetException(new SecurityException()), false),
                "wrapped platform capture denial is recoverable after unlock");
        check(!dev.tommy.foldshell.system.CapturePolicy.canFallback(new NoSuchMethodException(), false),
                "unknown capture API is not silently treated as transient screen state");
        check(!dev.tommy.foldshell.system.CapturePolicy.visiblePixel(0xff000000)
                && !dev.tommy.foldshell.system.CapturePolicy.visiblePixel(0x00ffffff)
                && dev.tommy.foldshell.system.CapturePolicy.visiblePixel(0xff0000ff),
                "blank/transparent snapshots fall back while visible colored pixels are accepted");
        for (boolean right : new boolean[]{false, true}) {
            float[][] base = dev.tommy.foldshell.system.BlurProfile.regions(1000, 2200, 70, right);
            float[][] edges = dev.tommy.foldshell.system.BlurProfile.perspectiveRegions(1000, 2200, 70, right, .3f);
            check(edges.length > base.length && edges.length <= 162, "edge boost stays bounded in region count");
            for (int i = 0; i < base.length; i++) check(java.util.Arrays.equals(base[i], edges[i]),
                    "existing interior blur gradient is preserved");
            float centerX = right ? 400 : 600;
            for (int i = base.length; i < edges.length; i++) {
                float[] e = edges[i];
                check(e[0] > 70 && e[0] <= 360 && e[2] >= 0 && e[3] >= 0 && e[4] <= 1000 && e[5] <= 2200,
                        "strong edge blur stays inside the selected pane");
                check(!(e[2] <= centerX && centerX < e[4] && e[3] <= 1100 && 1100 < e[5]),
                        "extra blur does not cover the image center");
            }
        }
        check(dev.tommy.foldshell.system.BlurProfile.perspectiveRegions(1000, 2200, 0, true, .5f).length == 0,
                "edge boost disappears when base blur releases");
        dev.tommy.foldshell.system.VendorEventGate quick = new dev.tommy.foldshell.system.VendorEventGate(300);
        check(!quick.accept(times(1), 1), "fast gate starts from a baseline");
        for (int i = 0; i < 3; i++) {
            double now = 1.1 + i * .1;
            check(!quick.accept(times(now-.04, now-.03, now-.02, now-.01), now),
                    "fast onset still rejects bursts shorter than 300ms");
        }
        check(quick.accept(times(1.36, 1.37, 1.38, 1.39), 1.4), "fast onset qualifies at 300ms");
        FoldMotion quickMotion = new FoldMotion(true);
        quickMotion.angle(0, false, 0);
        check(quickMotion.confirmedHint(false, 1000), "qualified burst does not wait for a second confirmation");
        quickMotion.amount(2499);
        check(!quickMotion.releasing(), "300ms onset does not shorten the 1.5s idle hold");
        quickMotion.amount(2500);
        check(quickMotion.releasing(), "original idle release still applies");
        FoldMotion idleDissolve = new FoldMotion(true);
        idleDissolve.angle(0, false, 0); idleDissolve.angle(90, false, 1000);
        idleDissolve.amount(2499);
        check(idleDissolve.visibility(2499) == 1, "idle screen stays fully visible through 1.5s hold");
        idleDissolve.amount(2500);
        float lastVisible = 1;
        for (int time = 2500; time < 3120; time += 10) {
            idleDissolve.amount(time);
            float visible = idleDissolve.visibility(time);
            check(visible <= lastVisible && visible >= 0, "idle dissolve never flashes brighter");
            lastVisible = visible;
        }
        check(idleDissolve.active(), "V2 idle dissolve lasts beyond old 420ms fade");
        idleDissolve.amount(3120);
        check(!idleDissolve.active(), "idle dissolve finishes after 620ms");
        testProfile(); testGate(); testCapturePolicy();
        System.out.println("FoldMotionTest: PASS");
    }
}
