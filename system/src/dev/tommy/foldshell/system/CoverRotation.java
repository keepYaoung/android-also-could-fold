package dev.tommy.foldshell.system;

import java.util.ArrayDeque;

/** Short-lived relative Y rotation; never starts a fold or claims a hinge angle. */
public final class CoverRotation {
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();
    private long lastNs, lastReceipt = -1;
    private float degrees, peak;
    private boolean active;
    public void sample(float yRadiansPerSecond, long sensorNs, long now) {
        if (!Float.isFinite(yRadiansPerSecond)) return;
        long previous = lastNs;
        if (sensorNs <= previous) return;
        lastNs = sensorNs; lastReceipt = now;
        while (!recent.isEmpty() && now - recent.peekFirst()[0] > 500) recent.removeFirst();
        if (previous == 0 || sensorNs - previous > 150_000_000L) {
            recent.clear(); return; // never integrate across suspended or missing samples
        }
        float delta = Math.abs(yRadiansPerSecond) < .025f ? 0
                : (float) (yRadiansPerSecond * (sensorNs - previous) / 1e9 * 180 / Math.PI);
        recent.addLast(new double[]{now, delta});
        if (active) {
            degrees = Math.max(0, Math.min(90, degrees + delta));
            peak = Math.max(peak, degrees);
        }
    }
    public boolean begin(long now) {
        end();
        if (lastReceipt < 0 || now - lastReceipt > 250) return false;
        for (double[] row : recent) if (now - row[0] <= 500) degrees += (float) row[1];
        degrees = Math.max(0, Math.min(90, degrees)); peak = degrees;
        active = true; return true;
    }
    public float progress() { return peak / 90f; }
    public boolean reversed() { return active && peak - degrees >= 1.5f; }
    public void end() { active = false; degrees = peak = 0; }
    public void reset() { end(); recent.clear(); lastNs = 0; lastReceipt = -1; }
}
