package dev.tommy.foldshell.system;

import java.lang.reflect.Method;

/** Explicit redaction for both the legacy booleans and newer policy-based API. */
public final class CapturePolicy {
    private CapturePolicy() {}
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
