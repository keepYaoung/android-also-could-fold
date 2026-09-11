package dev.tommy.foldshell.system;

/** Angle response and Samsung firmware blur-region layout, independent of Android. */
public final class BlurProfile {
    private BlurProfile() {}
    public static float strength(float angle, boolean inner) {
        if (!Float.isFinite(angle)) return 0;
        float phase = Math.max(0, Math.min(1, inner ? (180 - angle) / 90f : angle / 90f));
        return phase * phase * (3 - 2 * phase);
    }

    public static float[][] regions(int paneWidth, int height, int radius) {
        return regions(paneWidth, height, radius, false);
    }

    public static float[][] regions(int paneWidth, int height, int radius, boolean strongRight) {
        if (paneWidth <= 0 || height <= 0 || radius <= 0) return new float[0][];
        int count = Math.min(32, paneWidth);
        float[][] result = new float[count][14];
        for (int i = 0; i < count; i++) {
            int left = i * paneWidth / count;
            int right = (i + 1) * paneWidth / count;
            float x = count == 1 ? 0 : i / (float) (count - 1);
            float fade = x * x * (3 - 2 * x);
            // Keep the same blur kernel, vary its blend with the original image.
            // Inner: strong left. Cover opening: strong right.
            float alpha = strongRight ? .06f + .94f * fade : 1f - .94f * fade;
            // Verified installed Samsung BackgroundBlurDrawable.BlurRegion format:
            // radius, alpha, rect L/T/R/B, corners TL/TR/BL/BR, clip L/T/R/B.
            result[i] = new float[]{radius, alpha, left, 0, right, height,
                    0, 0, 0, 0, left, 0, right, height};
        }
        return result;
    }
}
