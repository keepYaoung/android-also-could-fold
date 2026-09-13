package dev.tommy.foldshell.system;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/** Private stdio protocol over this app's authenticated local ADB stream. */
public final class LocalFoldDaemon {
    private static long lastRequest;
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("ADB shell required");
        try (RandomAccessFile file = new RandomAccessFile("/data/local/tmp/fold-transition-system.lock", "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) { System.out.println("FOLD ERROR Another engine is running; turn off the old app first."); return; }
            file.setLength(0); file.writeBytes(android.os.Process.myPid() + "\n");
            Looper.prepareMainLooper();
            Handler handler = new Handler(Looper.myLooper());
            FoldShell engine;
            try { engine = FoldShell.persistent(Float.parseFloat(args[0]), FoldShell.Mode.parse(args.length > 1 ? args[1] : "v1")); }
            catch (Exception error) {
                // The app forwards every line to its shareable log; include the origin of the failure.
                Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null ? error.getCause() : error;
                System.out.println("FOLD ERROR " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                StackTraceElement[] frames = cause.getStackTrace();
                for (int i = 0; i < Math.min(14, frames.length); i++) System.out.println("  at " + frames[i]);
                System.out.flush();
                return;
            }
            lastRequest = SystemClock.elapsedRealtime();
            // The main Looper cannot be quit; exit explicitly after cleanup (fixes exit 137).
            Runnable shutdown = () -> { engine.close(); System.out.flush(); System.exit(0); };
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (SystemClock.elapsedRealtime() - lastRequest >= 45000) shutdown.run();
                    else handler.postDelayed(this, 5000);
                }
            }, 5000);
            Thread input = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.length() > 80) break;
                        final String command = line;
                        handler.post(() -> {
                            lastRequest = SystemClock.elapsedRealtime();
                            if (command.equals("STOP")) { System.out.println("FOLD STOPPED"); shutdown.run(); }
                            else if (command.equals("STATUS")) System.out.println("FOLD " + engine.status());
                            else if (command.startsWith("INTENSITY ")) {
                                try { engine.setIntensity(Float.parseFloat(command.substring(10))); }
                                catch (IllegalArgumentException error) { System.out.println("FOLD ERROR Invalid intensity"); }
                            }
                        });
                    }
                } catch (Exception ignored) { }
                finally { handler.post(shutdown); }
            }, "FoldCommands");
            input.setDaemon(true); input.start();
            System.out.println("FOLD RUNNING");
            try { Looper.loop(); } finally { engine.close(); }
        }
        System.exit(0);
    }
}
