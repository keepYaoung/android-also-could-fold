package dev.tommy.foldshell.system;

import java.util.ArrayDeque;

/** Short-lived relative Y rotation; never starts a fold or claims a hinge angle. */
public final class CoverRotation {
    private final ArrayDeque<double[]> recent = new ArrayDeque<>();
    private long lastNs, lastReceipt = -1, reverseSince = -1;
    private float degrees, peak;
    private boolean active, advancing;
    private float direction = 1;
    public void sample(float yRadiansPerSecond, long sensorNs, long now) {
        advancing = false;
        if (!Float.isFinite(yRadiansPerSecond)) return;
        long previous = lastNs;
        if (sensorNs <= previous) return;
        lastNs = sensorNs; lastReceipt = now;
        while (!recent.isEmpty() && now - recent.peekFirst()[0] > 500) recent.removeFirst();
        if (previous == 0 || sensorNs - previous > 150_000_000L) {
            reverseSince = -1; recent.clear(); return; // never integrate across suspended or missing samples
        }
        float delta = Math.abs(yRadiansPerSecond) < .025f ? 0
                : (float) (yRadiansPerSecond * (sensorNs - previous) / 1e9 * 180 / Math.PI);
        recent.addLast(new double[]{now, delta});
        if (active) {
            advancing = direction * yRadiansPerSecond >= .08f;
            degrees = Math.max(0, Math.min(90, degrees + direction * delta));
            peak = Math.max(peak, degrees);
            if (peak - degrees >= 1.5f && direction * yRadiansPerSecond < -.08f) {
                if (reverseSince < 0) reverseSince = now;
            } else reverseSince = -1;
        }
    }
    public boolean begin(long now) { return begin(now, true); }
    public boolean begin(long now, boolean opening) { return begin(now, opening, true); }
    public boolean begin(long now, boolean opening, boolean includeHistory) {
        end(); direction = opening ? 1 : -1;
        if (lastReceipt < 0 || now - lastReceipt > 250) return false;
        for (double[] row : recent) if (includeHistory && now - row[0] <= 500) degrees += direction * (float) row[1];
        degrees = Math.max(0, Math.min(90, degrees)); peak = degrees;
        active = true; return true;
    }
    public float progress() { return peak / 90f; }
    public boolean advancing() { return active && advancing; }
    public boolean reversed() { return active && reverseSince >= 0 && lastReceipt - reverseSince >= 120; }
    public void end() { active = false; advancing = false; reverseSince = -1; degrees = peak = 0; }
    public void reset() { end(); recent.clear(); lastNs = 0; lastReceipt = -1; }
}
