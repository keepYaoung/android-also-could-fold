package dev.tommy.foldshell.system;

/** Right-edge entrance followed by rightward exit; values are normalized to pane width. */
public final class CoverReveal {
    private CoverReveal() {}
    private static float ease(float x) {
        x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x);
    }
    public static float left(float progress) {
        if (progress <= .4f) return 1 - .85f * ease(progress / .4f);
        return .15f + ease((progress - .4f) / .6f);
    }
    public static float opacity(float progress) {
        return ease(progress / .2f) * (1 - ease((progress - .4f) / .6f));
    }
    // Keep the captured plane visible through the first coarse 90-degree sample.
    public static float snapshot(float progress) { return 1 - ease((progress - .8f) / .2f); }
    /** Normalized quadrilateral: left edge fixed, only the right edge recedes. */
    public static void corners(float progress, float[] out) {
        float scale = depthScale(progress);
        float inset = (1 - scale) / 2f;
        out[0] = 0; out[1] = 0;
        out[2] = scale; out[3] = inset;
        out[4] = scale; out[5] = 1 - inset;
        out[6] = 0; out[7] = 1;
    }
    public static void innerCorners(float progress, float[] out) {
        corners(progress, out);
        // Mirror the geometry, retaining TL, TR, BR, BL ordering.
        float x = 1 - out[2], inset = out[3];
        out[0] = x; out[1] = inset;
        out[2] = 1; out[3] = 0;
        out[4] = 1; out[5] = 1;
        out[6] = x; out[7] = 1 - inset;
    }
    /** V5: horizontal stretch of the snapshot, up to 10% wider at full depth. Visual calibration. */
    public static float stretch(float progress) {
        return 1 + .1f * Math.max(0, Math.min(1, progress));
    }
    /** Cover: left edge fixed, right edge pushed past the pane. Ordering TL, TR, BR, BL. */
    public static void stretchCorners(float progress, float[] out) {
        float s = stretch(progress);
        out[0] = 0; out[1] = 0; out[2] = s; out[3] = 0; out[4] = s; out[5] = 1; out[6] = 0; out[7] = 1;
    }
    /** Inner left half: hinge edge fixed, outer edge pushed past the left of the pane. */
    public static void innerStretchCorners(float progress, float[] out) {
        float x = 1 - stretch(progress);
        out[0] = x; out[1] = 0; out[2] = 1; out[3] = 0; out[4] = 1; out[5] = 1; out[6] = x; out[7] = 1;
    }
    /** Amplify measured motion without advancing on elapsed time. */
    public static float motionDepth(float progress) { return motionDepth(progress, 2.5f); }
    public static float motionDepth(float progress, float gain) {
        return Math.max(0, Math.min(1, progress * gain));
    }
    /** V2 plane depth: responds quickly to the first degrees of rotation (ease-out) and
     *  saturates early; the small depthScale keeps the total rotation modest. */
    public static final float SNAPSHOT_GAIN = 1.6f;
    public static float snapshotDepth(float progress) {
        float d = motionDepth(progress, SNAPSHOT_GAIN);
        return 1 - (1 - d) * (1 - d);
    }
    public static float openingDepth(float start, float progress) {
        // Consume a fraction of the incoming depth, not an absolute depth unit.
        return start * (1 - Math.max(0, Math.min(1, progress)));
    }
    public static float innerStart(boolean fullyOpen, float coarseDepth, float carriedDepth) {
        // Preserve the incoming plane even if the endpoint precedes panel/capture readiness.
        if (fullyOpen && !Float.isFinite(carriedDepth)) return 0;
        return Math.max(0, Math.min(1, Float.isFinite(carriedDepth) ? carriedDepth : coarseDepth));
    }
    /** Reach the exact shared plane promptly after the authoritative open endpoint. */
    public static float settleDepth(float from, long elapsedMs) {
        return from * (1 - ease(elapsedMs / 320f));
    }
    /** V3: fraction of the pane, from the outer edge inward, that the darkening reaches.
     *  Wide from the start so the effect reads as a soft shade rather than a moving stripe. */
    public static float maskReach(float progress) { return maskReach(progress, false); }
    /** wide: the V4 shade spans most of the pane from the start and reaches the hinge side. */
    public static float maskReach(float progress, boolean wide) {
        if (!Float.isFinite(progress)) return 0;
        float p = Math.max(0, Math.min(1, progress));
        return wide ? .6f + .4f * p : .25f + .55f * p;
    }
    /** V3: how dark the outer edge is, 0..1. Grows with measured rotation; visual calibration. */
    public static float maskStrength(float progress) {
        if (!Float.isFinite(progress)) return 0;
        float p = Math.max(0, Math.min(1, progress * 1.5f));
        return p * p * (3 - 2 * p);
    }
    /** Spatial profile across the shade, 0 at its inner start, 1 at the outer edge. Gentle ease-in. */
    public static float maskProfile(float u) { return maskProfile(u, false); }
    /** wide: a plain smoothstep, so far more of the span is already dark. */
    public static float maskProfile(float u, boolean wide) {
        u = Math.max(0, Math.min(1, u));
        float s = u * u * (3 - 2 * u);
        return wide ? s : (float) Math.pow(s, 1.6);
    }
    /** Perspective size for a front-facing plane receding by 0..0.18 camera distances. */
    public static float depthScale(float progress) {
        float depth = .18f * Math.max(0, Math.min(1, progress));
        return 1f / (1f + depth);
    }
}
