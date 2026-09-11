package dev.tommy.foldshell.system;

import android.os.Handler;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded diagnostic polling: V1 4Hz, V2 up to 10Hz with one 300ms confirmation. */
final class VendorMotionMonitor implements AutoCloseable {
    private final Handler main;
    private final BooleanSupplier enabled;
    private final LongConsumer motion;
    private final VendorEventGate gate;
    private final int intervalMs;
    private volatile boolean running = true;
    private volatile Process process;
    private final Thread worker;
    private static final Pattern SAMPLE = Pattern.compile("\\s*\\d+ \\(ts=([0-9.]+),.*");
    VendorMotionMonitor(Handler main, BooleanSupplier enabled, boolean fastStart, LongConsumer motion) {
        this.main = main; this.enabled = enabled; this.motion = motion;
        intervalMs = fastStart ? 100 : 250; gate = new VendorEventGate(fastStart ? 300 : 0);
        worker = new Thread(this::loop, "FoldEventMonitor");
        worker.setDaemon(true);
        worker.start();
    }
    private void loop() {
        int failures = 0;
        while (running) {
            long start = SystemClock.elapsedRealtime();
            try {
                if (!enabled.getAsBoolean()) { gate.reset(); Thread.sleep(250); continue; }
                Process current = new ProcessBuilder("/system/bin/dumpsys", "sensorservice")
                        .redirectErrorStream(true).start();
                process = current;
                Runnable timeout = current::destroy;
                main.postDelayed(timeout, 1000);
                List<Double> timestamps = new ArrayList<>();
                boolean inSensor = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Folding Angle") && line.contains(": last ")) inSensor = true;
                        else if (inSensor) {
                            Matcher sample = SAMPLE.matcher(line);
                            if (sample.matches()) timestamps.add(Double.parseDouble(sample.group(1)));
                            else if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) inSensor = false;
                        }
                    }
                    int exit = current.waitFor();
                    if (exit != 0 || timestamps.isEmpty()) throw new IllegalStateException("Vendor timestamps unavailable");
                } finally {
                    main.removeCallbacks(timeout);
                    current.destroy(); process = null;
                }
                failures = 0;
                if (gate.accept(timestamps, SystemClock.elapsedRealtime() / 1000.0) && running) {
                    long eventMs = (long) (timestamps.stream().mapToDouble(Double::doubleValue).max().getAsDouble() * 1000);
                    main.post(() -> { if (running) motion.accept(eventMs); });
                }
            } catch (InterruptedException stopped) { return; }
            catch (Exception failure) {
                gate.reset();
                if (++failures >= 3) {
                    System.out.println("EARLY_MONITOR_DISABLED " + failure);
                    return; // retain public-sensor behavior when diagnostics fail
                }
            }
            long wait = Math.max(1, intervalMs - (SystemClock.elapsedRealtime() - start));
            try { Thread.sleep(wait); } catch (InterruptedException stopped) { return; }
        }
    }
    @Override public void close() {
        running = false;
        Process current = process;
        if (current != null) current.destroy();
        worker.interrupt();
    }
}
