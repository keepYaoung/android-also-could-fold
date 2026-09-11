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
    public static void main(String[] args) {
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
        check(v2.visibility(6710) == .5f, "retained screenshot dissolves with the shadow");
        check(v2.amount(6920) == 0 && !v2.active(), "v2 idle releases all visuals");
        testProfile(); testGate();
        System.out.println("FoldMotionTest: PASS");
    }
}
