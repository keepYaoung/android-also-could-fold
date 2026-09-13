package dev.tommy.foldshell.system;

import java.lang.reflect.InvocationTargetException;

/** Framework state that a real app gets from bindApplication but a shell app_process never does. */
public final class ShellProcess {
    private ShellProcess() {}
    /**
     * Android 16 added com.android.internal.os.ApplicationSharedMemory (nonce store for
     * PropertyInvalidatedCache, network time, animator scale). Only apps and system_server
     * initialize it; on Android 17 a framework path used at engine startup calls
     * getInstance() and throws "ApplicationSharedMemory not initialized" in this process
     * (reported on SM-F971B). Create a process-local block so those readers see an empty,
     * valid store instead of throwing. Older releases have no such class and are skipped.
     */
    public static String ensureApplicationSharedMemory() {
        Class<?> type;
        try { type = Class.forName("com.android.internal.os.ApplicationSharedMemory"); }
        catch (ClassNotFoundException older) { return "absent"; }
        try {
            try { type.getMethod("getInstance").invoke(null); return "present"; }
            catch (InvocationTargetException notInitialized) { /* expected in a shell process */ }
            Object instance = type.getMethod("create").invoke(null);
            type.getMethod("setInstance", type).invoke(null, instance);
            return "created";
        } catch (Throwable error) {
            return "failed " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }
}
