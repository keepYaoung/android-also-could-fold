package dev.tommy.foldshell.system;

/** Fold-only state, with bounded motion inference and explicit smooth release. */
public final class FoldMotion {
    public enum Direction { OPENING, CLOSING }
    private float previous = Float.NaN, angle;
    private long started, lastMotion, resolving = -1, nextHint;
    private long fadeStart = -1, movementMs;
    private float fadeFrom;
    private Direction direction;
    private boolean inner, provisional;
    private static final long HOLD_MS = 1500, RELEASE_MS = 420;

    public void angle(float next, boolean isInner, long now) {
        if (!Float.isFinite(next)) return;
        next = Math.max(0, Math.min(180, next));
        float from = previous;
        float delta = next - previous;
        previous = next;
        angle = next;
        inner = isInner;
        if (Float.isNaN(delta)) { nextHint = now + 800; return; }
        if (Math.abs(delta) < .15f) return;
        Direction nextDirection = delta > 0 ? Direction.OPENING : Direction.CLOSING;
        // Leaving a completed endpoint is a new fold, even if the previous
        // panel's release never ticked while its display was off.
        if (direction != nextDirection && ((from <= 1 && delta > 0)
                || (from >= 179 && delta < 0))) cancel();
        if (fadeStart >= 0) return;
        if (direction != null && direction != nextDirection) {
            // Clear the existing effect on reversal instead of swapping abruptly.
            release(now);
            return;
        }
        if (direction == null) {
            direction = nextDirection;
            started = lastMotion = now;
            movementMs = 0;
            resolving = -1;
        } else activity(now);
        provisional = false;
        lastMotion = now;
        if (next <= 1 || next >= 179) nextHint = now + 800;
        display(isInner, now);
    }

    /** A sustained timestamp burst indicates continued movement, not its direction. */
    public void activity(long eventTime) {
        if (direction == null || fadeStart >= 0 || eventTime <= lastMotion) return;
        long gap = eventTime - lastMotion;
        if (gap <= 700)
            movementMs += Math.min(gap, 500);
        lastMotion = eventTime;
    }

    public boolean hint(boolean isInner, long now) {
        if (Float.isNaN(previous)) return false;
        Direction candidate;
        if (previous >= 179) candidate = Direction.CLOSING;
        else if (previous <= 1) candidate = Direction.OPENING;
        else return false;
        if (now < nextHint) return false;
        if (direction != null && direction != candidate && !provisional) cancel();
        if (direction != null) return false;
        direction = candidate;
        inner = isInner;
        started = lastMotion = now;
        movementMs = 0;
        resolving = -1;
        provisional = true;
        nextHint = now + 2500;
        return true;
    }

    public void display(boolean isInner, long now) {
        inner = isInner;
        if (direction == null || provisional || fadeStart >= 0) return;
        if (resolving < 0 && ((direction == Direction.CLOSING && !inner)
                || (direction == Direction.OPENING && inner && angle >= 179))) resolving = now;
    }

    private float strength(long now) {
        if (provisional) {
            float ramp = Math.min(1f, Math.max(0, now - started) / 220f);
            // Event duration fills the gaps in the coarse public angle signal;
            // it is a visual estimate, not a measured hinge angle.
            if (direction == Direction.OPENING && !inner)
                return (.65f + .25f * Math.min(1f, movementMs / 1000f)) * ease(ramp);
            return Math.min(.5f, .18f * ease(ramp) + .32f * Math.min(1f, movementMs / 1000f));
        }
        if (resolving >= 0) {
            float p = Math.min(1f, Math.max(0, now - resolving) / 480f);
            return direction == Direction.CLOSING ? 1f - ease(p) : 0;
        }
        float angleStrength = BlurProfile.strength(angle, inner);
        if (direction == Direction.CLOSING && inner)
            return Math.min(1f, .8f * angleStrength + .2f * Math.min(1f, movementMs / 1400f));
        return angleStrength;
    }
    private static float ease(float p) { return p * p * (3 - 2 * p); }
    private void release(long now) {
        if (direction == null || fadeStart >= 0) return;
        // Preserve the last rendered target across a direction change.
        fadeFrom = lastOutput;
        fadeStart = now;
        nextHint = Math.max(nextHint, now + RELEASE_MS + 600);
    }
    private float lastOutput;
    public float amount(long now) {
        if (direction == null) return 0;
        if (fadeStart < 0) {
            if (resolving >= 0 && now - resolving >= 480) release(resolving + 480);
            else if (now - started >= 10000) release(started + 10000);
            else if (resolving < 0 && now - lastMotion >= HOLD_MS) release(lastMotion + HOLD_MS);
        }
        if (fadeStart >= 0) {
            float p = Math.min(1f, Math.max(0, now - fadeStart) / (float) RELEASE_MS);
            if (p >= 1) { cancel(); return 0; }
            lastOutput = fadeFrom * (1f - ease(p));
        } else lastOutput = strength(now);
        return lastOutput;
    }
    public boolean active() { return direction != null; }
    public boolean releasing() { return fadeStart >= 0; }
    public Direction direction() { return direction; }
    public void cancel() {
        direction = null; resolving = -1; fadeStart = -1;
        provisional = false; lastOutput = 0; movementMs = 0;
    }
    public void baseline(float value) {
        cancel();
        previous = Float.isFinite(value) ? Math.max(0, Math.min(180, value)) : Float.NaN;
    }
    public void reset() { cancel(); previous = Float.NaN; }
}
