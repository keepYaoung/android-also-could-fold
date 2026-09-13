package dev.tommy.foldshell.system;

/** Model allowlist. Panels are still measured at runtime; this only gates known hardware families. */
public final class DeviceSupport {
    private DeviceSupport() {}
    /** Galaxy Z Fold7 (SM-F966x, verified on SM-F966N) and Galaxy Z Fold8 / Fold8 Ultra (SM-F976x, unverified). */
    public static final String[] FAMILIES = {"SM-F966", "SM-F976"};
    public static boolean supported(String model) {
        if (model == null) return false;
        for (String family : FAMILIES) if (model.startsWith(family)) return true;
        return false;
    }
    public static boolean verified(String model) { return "SM-F966N".equals(model); }
}
