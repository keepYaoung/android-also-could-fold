package dev.tommy.foldshell;

import android.os.Handler;
import android.os.IBinder;
import android.os.HandlerThread;
import dev.tommy.foldshell.system.FoldShell;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;

/** Only Shizuku instantiates this Binder; it is not an exported Android service. */
public final class FoldUserService extends IFoldEngine.Stub {
    private final HandlerThread thread = new HandlerThread("FoldCompositor");
    private final Handler handler;
    private FoldShell engine;
    private IBinder owner;
    private final IBinder.DeathRecipient ownerDied = () -> {
        // The app may die while the daemon lives; the Shizuku server may not.
        handlerForDeath();
    };
    private void handlerForDeath() {
        handler.post(() -> { cleanup(); System.exit(0); });
    }
    private RandomAccessFile lockFile;
    private FileLock lock;
    public FoldUserService() { thread.start(); handler = new Handler(thread.getLooper()); }
    private <T> T call(Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(work);
        handler.post(task);
        try { return task.get(5, TimeUnit.SECONDS); }
        catch (Exception error) { task.cancel(false); throw new IllegalStateException("Engine unavailable", error); }
    }
    @Override public String start(IBinder server, float intensity) {
        return call(() -> {
            try {
                if (server == null || !server.isBinderAlive()) throw new IllegalStateException("Shizuku disconnected");
                if (owner != server) {
                    if (owner != null) owner.unlinkToDeath(ownerDied, 0);
                    owner = server;
                    owner.linkToDeath(ownerDied, 0);
                }
                if (engine != null && engine.status().equals("RUNNING")) {
                    engine.setIntensity(intensity);
                    return engine.status();
                }
                cleanup();
                lockFile = new RandomAccessFile("/data/local/tmp/fold-transition-system.lock", "rw");
                lock = lockFile.getChannel().tryLock();
                if (lock == null) throw new IllegalStateException("Another fold helper is running. Stop the ADB trial first.");
                lockFile.setLength(0);
                lockFile.writeBytes(android.os.Process.myPid() + "\n");
                engine = FoldShell.persistent(intensity);
                return engine.status();
            } catch (Exception error) { cleanup(); return "ERROR: " + error.getMessage(); }
        });
    }
    @Override public String status() { return call(() -> engine == null ? "STOPPED" : engine.status()); }
    @Override public void stop() { call(() -> { cleanup(); return null; }); }
    private void cleanup() {
        if (engine != null) { engine.close(); engine = null; }
        try { if (lock != null) lock.release(); } catch (Exception ignored) {}
        try { if (lockFile != null) lockFile.close(); } catch (Exception ignored) {}
        lock = null; lockFile = null;
    }
    @Override public void destroy() {
        try { stop(); } finally { thread.quitSafely(); System.exit(0); }
    }
}
