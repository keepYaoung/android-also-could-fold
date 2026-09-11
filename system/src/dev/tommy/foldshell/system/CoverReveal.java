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
    public static float snapshot(float progress) { return 1 - ease((progress - .45f) / .55f); }
    /** Perspective size for a front-facing plane receding by 0..0.65 camera distances. */
    public static float depthScale(float progress) {
        float depth = .65f * Math.max(0, Math.min(1, progress));
        return 1f / (1f + depth);
    }
}
