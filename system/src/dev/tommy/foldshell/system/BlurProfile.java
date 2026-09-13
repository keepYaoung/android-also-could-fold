package dev.tommy.foldshell.system;

/** Angle response and Samsung firmware blur-region layout, independent of Android. */
public final class BlurProfile {
    private BlurProfile() {}
    public static float strength(float angle, boolean inner) {
        if (!Float.isFinite(angle)) return 0;
        float phase = Math.max(0, Math.min(1, inner ? (180 - angle) / 90f : angle / 90f));
        return phase * phase * (3 - 2 * phase);
    }

    /** Same angle-response curve, driven by the V2 relative-depth estimate. */
    public static int depthRadius(float progress, boolean inner, float intensity, float visibility) {
        if (!Float.isFinite(progress) || !Float.isFinite(intensity) || !Float.isFinite(visibility)) return 0;
        float phase = Math.max(0, Math.min(1, progress * 2));
        float strength = phase * phase * (3 - 2 * phase);
        return Math.round((inner ? 160 : 180) * strength
                * Math.max(0, Math.min(1.5f, intensity)) * Math.max(0, Math.min(1, visibility)));
    }

    /** Preserve the interior gradient, boost only the projected border and black surround. */
    public static float[][] perspectiveRegions(int width, int height, int radius,
                                               boolean strongRight, float progress) {
        float[][] base = regions(width, height, radius, strongRight);
        if (base.length == 0 || !Float.isFinite(progress) || progress <= 0) return base;
        java.util.ArrayList<float[]> all = new java.util.ArrayList<>(java.util.Arrays.asList(base));
        float scale = CoverReveal.depthScale(progress);
        float inset = (1 - scale) * height / 2f;
        float edge = strongRight ? scale * width : (1 - scale) * width;
        int boosted = Math.min(360, Math.round(radius * 1.8f));
        float band = Math.max(2, Math.min(width * .055f, boosted * .55f));
        // Two soft bands avoid a new hard boundary around the stronger blur.
        for (int pass = 0; pass < 2; pass++) {
            float pad = band * (pass == 0 ? 1 : .5f);
            float alpha = pass == 0 ? .38f : .9f;
            if (strongRight) addRegion(all, boosted, alpha, edge - pad, 0, width, height, width, height);
            else addRegion(all, boosted, alpha, 0, 0, edge + pad, height, width, height);
            for (int i = 0; i < 32; i++) {
                float l = i * width / 32f, r = (i + 1) * width / 32f;
                float x = (l + r) / 2f;
                float fraction = strongRight ? x / (scale * width) : (width - x) / (scale * width);
                float y = inset * Math.max(0, Math.min(1, fraction));
                addRegion(all, boosted, alpha, l, 0, r, y + pad, width, height);
                addRegion(all, boosted, alpha, l, height - y - pad, r, height, width, height);
            }
        }
        return all.toArray(new float[0][]);
    }
    /** V3: blur only the shaded span. Blend and radius both follow the same spatial
     *  profile as the drawn gradient, from nearly nothing at the inner start to their
     *  maximum at the outer edge, and the whole ramp scales with the shade strength. */
    public static final int FLAT_STRIPS = 24;
    public static float[][] flatRegions(int width, int height, int radius,
                                        boolean strongRight, float progress) {
        return flatRegions(width, height, radius, strongRight, progress, false);
    }
    /** wide: V4's broader, darker shade; the blur spans and ramps the same way. */
    public static float[][] flatRegions(int width, int height, int radius,
                                        boolean strongRight, float progress, boolean wide) {
        if (width <= 0 || height <= 0 || radius <= 0 || !Float.isFinite(progress) || progress <= 0)
            return new float[0][];
        float reach = CoverReveal.maskReach(progress, wide), strength = CoverReveal.maskStrength(progress);
        if (strength <= 0) return new float[0][];
        float from = strongRight ? (1 - reach) * width : 0, to = strongRight ? width : reach * width;
        int boosted = Math.min(360, Math.round(radius * 1.8f));
        java.util.ArrayList<float[]> all = new java.util.ArrayList<>();
        for (int i = 0; i < FLAT_STRIPS; i++) {
            float l = from + (to - from) * i / FLAT_STRIPS, r = from + (to - from) * (i + 1) / FLAT_STRIPS;
            float x = (i + .5f) / FLAT_STRIPS;
            float t = CoverReveal.maskProfile(strongRight ? x : 1 - x, wide);   // 0 inner start, 1 outer edge
            addRegion(all, Math.max(1, Math.round(boosted * (.08f + .92f * t) * (.4f + .6f * strength))),
                    Math.min(1, (.03f + .97f * t) * strength), l, 0, r, height, width, height);
        }
        return all.toArray(new float[0][]);
    }
    private static void addRegion(java.util.List<float[]> all, int radius, float alpha,
                                  float l, float t, float r, float b, int width, int height) {
        l = Math.max(0, l); t = Math.max(0, t); r = Math.min(width, r); b = Math.min(height, b);
        if (l >= r || t >= b) return;
        all.add(new float[]{radius, alpha, l, t, r, b, 0, 0, 0, 0, l, t, r, b});
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
