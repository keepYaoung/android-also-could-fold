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
    /** Amplify measured motion without advancing on elapsed time. */
    public static float motionDepth(float progress) {
        return Math.max(0, Math.min(1, progress * 2.5f));
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
        return from * (1 - ease(elapsedMs / 140f));
    }
    /** Perspective size for a front-facing plane receding by 0..0.65 camera distances. */
    public static float depthScale(float progress) {
        float depth = .65f * Math.max(0, Math.min(1, progress));
        return 1f / (1f + depth);
    }
}
