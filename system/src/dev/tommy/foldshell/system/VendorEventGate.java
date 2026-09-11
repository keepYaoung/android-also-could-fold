package dev.tommy.foldshell.system;

import java.util.List;

/** A burst is evidence of motion, not an angle or a guaranteed physical fold. */
public final class VendorEventGate {
    private double latest = -1, lastRead = -1;
    private int consecutive;
    public boolean accept(List<Double> timestamps, double now) {
        if (timestamps.isEmpty()) { consecutive = 0; return false; }
        double max = timestamps.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
        if (latest < 0 || max < latest) { latest = max; lastRead = now; consecutive = 0; return false; }
        int fresh = 0;
        for (double ts : timestamps) if (ts > latest && now - ts >= 0 && now - ts <= .6) fresh++;
        if (lastRead < 0 || now - lastRead > .65) consecutive = 0;
        latest = max;
        lastRead = now;
        consecutive = fresh >= 4 ? consecutive + 1 : 0;
        if (consecutive >= 2) { consecutive = 0; return true; }
        return false;
    }
    public void reset() { latest = -1; lastRead = -1; consecutive = 0; }
}
