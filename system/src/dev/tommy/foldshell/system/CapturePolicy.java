package dev.tommy.foldshell.system;

import java.lang.reflect.Method;

/** Explicit redaction for both the legacy booleans and newer policy-based API. */
public final class CapturePolicy {
    private CapturePolicy() {}
    /** Expected absence of usable screen pixels, not an engine failure. */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) { super(message); }
    }
    public static boolean canFallback(Throwable error, boolean locked) {
        if (locked) return true;
        // Reflection wraps a platform SecurityException during lock transitions.
        for (int i = 0; error != null && i < 8; i++, error = error.getCause())
            if (error instanceof Unavailable || error instanceof SecurityException) return true;
        return false;
    }
    public static boolean visiblePixel(int argb) {
        return (argb >>> 24) > 8 && (((argb >> 16) & 255) > 8
                || ((argb >> 8) & 255) > 8 || (argb & 255) > 8);
    }
    public static void redact(Object builder, Class<?> constants) throws Exception {
        Class<?> type = builder.getClass();
        Method secure, protectedContent;
        try {
            secure = type.getMethod("setCaptureSecureLayers", boolean.class);
            protectedContent = type.getMethod("setAllowProtected", boolean.class);
        } catch (NoSuchMethodException modernApi) {
            // Resolve every method/constant before changing the builder. Missing
            // policy support aborts capture instead of relying on unknown defaults.
            secure = type.getMethod("setSecureContentPolicy", int.class);
            protectedContent = type.getMethod("setProtectedContentPolicy", int.class);
            int redactSecure = constants.getField("SECURE_CONTENT_POLICY_REDACT").getInt(null);
            int redactProtected = constants.getField("PROTECTED_CONTENT_POLICY_REDACT").getInt(null);
            secure.invoke(builder, redactSecure);
            protectedContent.invoke(builder, redactProtected);
            return;
        }
        secure.invoke(builder, false);
        protectedContent.invoke(builder, false);
    }
}
