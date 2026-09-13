package dev.tommy.foldshell.system;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.SurfaceControl;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;

/** ADB shell compositor effects only during physical fold motion.
 * V1 uses live blur; V2 retains one cover snapshot in memory and draws a black gradient;
 * V3 keeps the live screen flat and slides a black mask over the folding edge.
 * No launcher replacement, device-state override, or input window.
 * Hidden API failures stop the engine if the firmware changes.
 */
public final class FoldShell implements SensorEventListener, DisplayManager.DisplayListener {
    /** Rendering backend. BLUR=V1, SNAPSHOT=V2, MASK=V3, HYBRID=V4 (V2 plane + V3 shade). */
    public enum Mode {
        BLUR, SNAPSHOT, MASK, HYBRID;
        public static Mode parse(String value) {
            if ("v2".equals(value)) return SNAPSHOT;
            if ("v3".equals(value)) return MASK;
            if ("v4".equals(value)) return HYBRID;
            return BLUR;
        }
        public String label() {
            switch (this) {
                case SNAPSHOT: return "v2-snapshot-gradient";
                case MASK: return "v3-flat-mask";
                case HYBRID: return "v4-snapshot-shade";
                default: return "compositor-blur";
            }
        }
        boolean shade() { return this == MASK || this == HYBRID; }
        boolean snapshot() { return this == SNAPSHOT || this == HYBRID; }
    }
    private static final String NAME = "FoldTransition-SystemBlur";
    /** The blur must sit above everything the effect draws (snapshot, shade, mask). */
    static final int BLUR_Z = 2001000, CANVAS_Z = 2000000;
    private final Mode mode;
    private final FoldMotion motion;
    private final BlackGradientRenderer blackRenderer;
    private final Handler handler = new Handler(Looper.myLooper());
    private final SensorManager sensors;
    private final DisplayManager displays;
    private final PowerManager power;
    private final android.app.KeyguardManager keyguard;
    private final Object displayGlobal;
    private final Method getDisplayInfo;
    private final Method effectLayer = SurfaceControl.Builder.class.getMethod("setEffectLayer");
    private final Method blur = method("setBackgroundBlurRadius", SurfaceControl.class, int.class);
    private final Method blurRegions = method("setBlurRegions", SurfaceControl.class, float[][].class);
    private final Method layerStack = method("setLayerStack", SurfaceControl.class, int.class);
    private final Method show = method("show", SurfaceControl.class);
    private final Method hide = method("hide", SurfaceControl.class);
    private final Method remove = method("remove", SurfaceControl.class);
    private final Method crop = method("setWindowCrop", SurfaceControl.class, Rect.class);
    private SurfaceControl surface;
    private VendorMotionMonitor earlyMonitor;
    private final CoverRotation coverRotation = new CoverRotation();
    private long rotationSequence = -1;
    private boolean gyroDriving, rotationInner;
    private float depthStart, renderedDepth, handoffDepth;
    private long handoffSequence = -1, handoffAt;
    private boolean scheduled;
    private boolean closed;
    private float intensity = 1f;
    private String failureMessage;
    private boolean failed, standalone;
    private boolean extraEdgeBlur = true;
    private float rendered;
    private long lastTick;
    private int lastRadius = -1, lastWidth, lastHeight, lastStack = -1, lastEdgeKey;
    private String lastDisplay = "";
    private String lastV2Identity = "";
    private boolean lastInner, lastStrongRight;
    private final Runnable frame = this::tick;

    private static Method method(String name, Class<?>... types) throws Exception {
        return SurfaceControl.Transaction.class.getMethod(name, types);
    }
    private static void log(String message) {
        System.out.println(SystemClock.elapsedRealtime() + " " + message);
    }
    private FoldShell(Context context, Mode mode) throws Exception {
        this.mode = mode;
        motion = new FoldMotion(mode != Mode.BLUR, mode == Mode.MASK);
        blackRenderer = mode == Mode.BLUR ? null : new BlackGradientRenderer(handler, this::fail, mode == Mode.MASK, mode.shade());
        sensors = context.getSystemService(SensorManager.class);
        displays = context.getSystemService(DisplayManager.class);
        power = context.getSystemService(PowerManager.class);
        keyguard = context.getSystemService(android.app.KeyguardManager.class);
        Class<?> global = Class.forName("android.hardware.display.DisplayManagerGlobal");
        displayGlobal = global.getMethod("getInstance").invoke(null);
        getDisplayInfo = global.getMethod("getDisplayInfo", int.class);
    }
    private Object displayInfo() throws Exception {
        Object info = getDisplayInfo.invoke(displayGlobal, Display.DEFAULT_DISPLAY);
        if (info == null) throw new IllegalStateException("Default display unavailable");
        return info;
    }
    private static int value(Object object, String field) throws Exception {
        return object.getClass().getField(field).getInt(object);
    }
    private static boolean inner(Object info) throws Exception {
        // Device-specific profile verified against SM-F966N: 411dp cover, 750dp inner.
        // Use shortest side so rotating the cover cannot turn it into an inner display.
        return Math.min(value(info, "logicalWidth"), value(info, "logicalHeight")) * 160f
                / value(info, "logicalDensityDpi") >= 600f;
    }
    private boolean allowed() { return power.isInteractive(); }

    private void start(long duration, boolean early) throws Exception {
        standalone = duration > 0;
        Object info = displayInfo();
        log("display=" + value(info, "logicalWidth") + "x" + value(info, "logicalHeight")
                + " inner=" + inner(info));
        Sensor hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge == null) throw new IllegalStateException("No hinge angle sensor");
        if (!sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_GAME, handler))
            throw new IllegalStateException("Hinge subscription refused");
        if (blackRenderer != null) {
            Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            boolean registered = gyro != null && sensors.registerListener(this, gyro, 20000, handler);
            log("COVER_GYRO registered=" + registered);
        }
        displays.registerDisplayListener(this, handler);
        if (early) earlyMonitor = new VendorMotionMonitor(handler, this::allowed, blackRenderer != null, eventMs -> {
            try {
                if (closed || !allowed()) return;
                long now = SystemClock.elapsedRealtime();
                // Expire the old panel's state before deciding whether this is
                // a new motion. Do not refresh an old closing with opening data.
                motion.amount(now);
                boolean began = blackRenderer != null ? motion.confirmedHint(inner(displayInfo()), now)
                        : motion.hint(inner(displayInfo()), now);
                motion.activity(eventMs);
                log("EARLY_BURST ageMs=" + (now - eventMs) + " direction="
                        + motion.direction() + " began=" + began);
                if (began) {
                    log("EARLY_BEGIN " + motion.direction());
                    if (!scheduled) { scheduled = true; handler.post(frame); }
                }
            } catch (Throwable error) { fail(error); }
        });
        // The main Looper refuses quitSafely(), which previously crashed the process
        // at expiry (exit 137). Close, flush, and exit explicitly instead.
        if (duration > 0) handler.postDelayed(() -> exit(0), duration);
        log("READY sensor=" + hinge.getName() + " durationMs=" + duration
                + " effects=physical-fold-only backend=" + mode.label());
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) return;
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (!allowed()) { coverRotation.reset(); gyroDriving = false; rotationSequence = -1; return; }
            if (event.values.length >= 3) {
                long now = SystemClock.elapsedRealtime();
                coverRotation.sample(event.values[1], event.timestamp, now);
                if (gyroDriving && motion.active() && !motion.releasing()) {
                    // Only sustain an already hinge/vendor-confirmed fold. Gyro
                    // cannot start an effect, and stationary noise cannot prolong it.
                    if (coverRotation.reversed()) {
                        log("V2_RELEASE reason=gyro-reversal panel=" + (rotationInner ? "inner" : "cover"));
                        motion.reverse(now);
                    } else if (coverRotation.advancing()) motion.activity(now);
                }
            }
            return;
        }
        try {
            Object info = displayInfo();
            FoldMotion.Direction before = motion.direction();
            motion.angle(event.values[0], inner(info), SystemClock.elapsedRealtime());
            log("hinge=" + event.values[0] + " direction=" + motion.direction());
            if (before != motion.direction() && motion.active()) log("BEGIN " + motion.direction());
            if (motion.active() && allowed() && !scheduled) { scheduled = true; handler.post(frame); }
        } catch (Throwable error) { fail(error); }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void onDisplayAdded(int id) {}
    @Override public void onDisplayRemoved(int id) {
        if (id == Display.DEFAULT_DISPLAY) { motion.reset(); destroySurface(); }
    }
    @Override public void onDisplayChanged(int id) {
        if (id != Display.DEFAULT_DISPLAY || !motion.active()) return;
        try {
            Object info = displayInfo();
            if (allowed() && value(info, "state") == Display.STATE_ON) {
                motion.display(inner(info), SystemClock.elapsedRealtime());
                motion.amount(SystemClock.elapsedRealtime());
                if (motion.active() && !scheduled) { scheduled = true; handler.post(frame); }
            }
        }
        catch (Throwable error) { fail(error); }
    }
    private void tick() {
        scheduled = false;
        // V2's snapshot plane over-rotated at the V3 gain; V2 uses its own front-loaded curve.
        final boolean snapshotMode = mode.snapshot();
        try {
            // A panel handoff can briefly turn the display off. Keep only the
            // recent hinge-triggered state; display callbacks cannot create it.
            if (!power.isInteractive()) { destroySurface(); return; }
            long now = SystemClock.elapsedRealtime();
            Object info = displayInfo();
            if (value(info, "state") != Display.STATE_ON) { destroySurface(); return; }
            motion.display(inner(info), now);
            float target = motion.amount(now);
            if (!motion.active()) { coverRotation.end(); gyroDriving = false;
                rotationSequence = -1; destroySurface(); log("END"); return; }
            float dt = lastTick == 0 ? 16 : Math.min(64, now - lastTick);
            rendered += (target - rendered) * Math.min(1f, dt / (motion.releasing() ? 55f : 120f));
            lastTick = now;
            int radius = Math.round(rendered * (inner(info) ? 160 : 180) * intensity);
            if (blackRenderer != null) {
                boolean isInner = inner(info);
                boolean opening = motion.direction() == FoldMotion.Direction.OPENING;
                if (rotationSequence != motion.sequence() || rotationInner != isInner) {
                    boolean carry = isInner && opening && handoffSequence == motion.sequence()
                            && now - handoffAt <= 1500;
                    rotationSequence = motion.sequence(); rotationInner = isInner;
                    // Carry the rendered plane, but do not count pre-handoff gyro history twice.
                    gyroDriving = coverRotation.begin(now, opening, !carry);
                    depthStart = isInner && opening ? CoverReveal.innerStart(motion.fullyOpen(),
                            motion.innerProgress(), carry ? handoffDepth : Float.NaN) : 0;
                    renderedDepth = depthStart;
                    log("V2_PROGRESS panel=" + (isInner ? "inner" : "cover") + " source="
                            + (gyroDriving ? "relative-gyro" : "coarse-hinge"));
                }
                if (!motion.releasing()) {
                    renderedDepth = gyroDriving
                            ? isInner ? opening
                                    ? CoverReveal.openingDepth(depthStart, coverRotation.progress())
                                    : snapshotMode ? CoverReveal.snapshotDepth(coverRotation.progress())
                                    : CoverReveal.motionDepth(coverRotation.progress())
                                    : snapshotMode ? CoverReveal.snapshotDepth(coverRotation.progress())
                                    : CoverReveal.motionDepth(coverRotation.progress())
                            : isInner ? motion.innerProgress() : motion.coverProgress(now);
                } else coverRotation.end();
                if (isInner && opening && motion.fullyOpen()) {
                    if (renderedDepth > 0) log("V2_ALIGN fully-open target=0");
                    renderedDepth = 0; coverRotation.end(); gyroDriving = false;
                }
                boolean locked = keyguard.isKeyguardLocked();
                String v2Identity = String.valueOf(info.getClass().getField("uniqueId").get(info))
                        + "/" + motion.sequence() + "/rotation=" + value(info, "rotation") + "/locked=" + locked;
                if (!v2Identity.equals(lastV2Identity)) {
                    // Remove the prior blur before a new panel/lock snapshot starts.
                    destroyBlurSurface(); lastV2Identity = v2Identity;
                }
                blackRenderer.render(v2Identity,
                        info.getClass().getField("address").get(info), value(info, "logicalWidth"),
                        value(info, "logicalHeight"), value(info, "layerStack"), isInner, locked,
                        rendered * intensity, motion.visibility(now),
                        opening, renderedDepth, depthStart, intensity);
                if (!isInner && opening) {
                    // A pending capture has no rendered depth yet; retain the sensed plane.
                    handoffDepth = Math.max(blackRenderer.depthProgress(), renderedDepth);
                    handoffSequence = motion.sequence(); handoffAt = now;
                }
                render(info, BlurProfile.depthRadius(blackRenderer.depthProgress(), isInner,
                        intensity, motion.visibility(now)));
            } else if (radius > 0) render(info, radius);
            else destroySurface();
            scheduled = true;
            handler.postDelayed(frame, 16);
        } catch (Throwable error) { fail(error); }
    }
    private void render(Object info, int radius) throws Exception {
        int width = value(info, "logicalWidth"), height = value(info, "logicalHeight");
        int stack = value(info, "layerStack");
        String identity = String.valueOf(info.getClass().getField("uniqueId").get(info));
        if (!identity.equals(lastDisplay)) {
            log("DISPLAY " + identity + " " + width + "x" + height);
            lastDisplay = identity;
        }
        boolean isInner = inner(info);
        boolean strongRight = !isInner && (blackRenderer != null || motion.direction() == FoldMotion.Direction.OPENING);
        if (isInner) width /= 2;
        if (surface == null) {
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName(NAME);
            effectLayer.invoke(builder);
            surface = builder.build();
            log("LAYER created valid=" + surface.isValid());
        }
        // Only V3 shapes its blur to the shade. V2 keeps the plain per-panel gradient blur
        // without the earlier boosted border, which read as a hard edge around the plane.
        boolean edgeBlur = extraEdgeBlur && mode.shade() && (isInner || motion.direction() == FoldMotion.Direction.OPENING);
        float edgeDepth = edgeBlur ? blackRenderer.depthProgress() : 0;
        int edgeKey = Math.round(edgeDepth * 1000);
        if (edgeKey == lastEdgeKey && radius == lastRadius && width == lastWidth && height == lastHeight && stack == lastStack && isInner == lastInner && strongRight == lastStrongRight) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            layerStack.invoke(transaction, surface, stack);
            transaction.setLayer(surface, BLUR_Z);
            crop.invoke(transaction, surface, new Rect(0, 0, width, height));
            if (isInner || strongRight) {
                // A global blur would flatten the spatial gradient, so clear it.
                blur.invoke(transaction, surface, 0);
                blurRegions.invoke(transaction, surface, !edgeBlur
                        ? BlurProfile.regions(width, height, radius, strongRight)
                        : mode.shade()
                        ? BlurProfile.flatRegions(width, height, radius, strongRight, edgeDepth, mode == Mode.HYBRID)
                        : BlurProfile.perspectiveRegions(width, height, radius, strongRight, edgeDepth));
            } else {
                // Closing cover keeps its uniform resolving effect.
                blurRegions.invoke(transaction, surface, new float[0][]);
                blur.invoke(transaction, surface, radius);
            }
            show.invoke(transaction, surface);
            transaction.apply();
        }
        lastEdgeKey = edgeKey; lastRadius = radius; lastWidth = width; lastHeight = height; lastStack = stack; lastInner = isInner; lastStrongRight = strongRight;
    }
    private void destroySurface() {
        if (blackRenderer != null) blackRenderer.clear();
        destroyBlurSurface(); lastV2Identity = "";
        rendered = 0; lastTick = 0;
    }
    private void destroyBlurSurface() {
        if (surface != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                hide.invoke(transaction, surface);
                remove.invoke(transaction, surface);
                transaction.apply();
            } catch (Throwable error) { log("cleanup=" + error); }
            finally { surface.release(); surface = null; log("LAYER removed"); }
        }
        lastRadius = -1;
    }
    private void fail(Throwable error) {
        failed = true;
        failureMessage = error.toString();
        log("ERROR " + error); error.printStackTrace(System.out);
        close();
        // close() removes the expiry callback. A failed standalone loop must
        // exit now rather than keep the shared process lock forever.
        if (standalone) exit(1);
    }
    /** Standalone runs exit here; the main Looper cannot be quit from a callback. */
    private void exit(int code) {
        close();
        System.out.flush();
        System.exit(code);
    }
    public void close() {
        if (closed) return;
        closed = true;
        if (earlyMonitor != null) earlyMonitor.close();
        handler.removeCallbacksAndMessages(null);
        sensors.unregisterListener(this);
        displays.unregisterDisplayListener(this);
        motion.reset(); destroySurface();
        if (blackRenderer != null) blackRenderer.close();
        log("STOPPED");
    }
    /** Called on the owning Looper in an ADB shell process. */
    public static FoldShell persistent(float intensity) throws Exception { return persistent(intensity, Mode.BLUR); }
    public static FoldShell persistent(float intensity, boolean v2) throws Exception {
        return persistent(intensity, v2 ? Mode.SNAPSHOT : Mode.BLUR);
    }
    public static FoldShell persistent(float intensity, Mode mode) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Engine must run as ADB shell");
        if (!android.os.Build.MODEL.equals("SM-F966N")) throw new IllegalStateException("Unverified device: " + android.os.Build.MODEL);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        FoldShell shell = new FoldShell(system.createPackageContext("com.android.shell", 0), mode);
        shell.setIntensity(intensity);
        try { shell.start(0, true); } catch (Exception error) { shell.close(); throw error; }
        return shell;
    }
    public void setIntensity(float value) {
        if (!Float.isFinite(value) || value < .5f || value > 1.5f)
            throw new IllegalArgumentException("Intensity must be 0.5..1.5");
        intensity = value;
    }
    public String status() { return failureMessage != null ? "ERROR: " + failureMessage : closed ? "STOPPED" : "RUNNING"; }

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Run as ADB shell");
        if (!android.os.Build.MODEL.equals("SM-F966N")) throw new IllegalStateException("Unverified device profile");
        long seconds = args.length == 0 ? 600 : Long.parseLong(args[0]);
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("duration must be 1..3600 seconds");
        int exitCode = 0;
        try (RandomAccessFile file = new RandomAccessFile("/data/local/tmp/fold-transition-system.lock", "rw");
             FileLock lock = file.getChannel().tryLock()) {
            if (lock == null) throw new IllegalStateException("Already running");
            file.setLength(0);
            file.writeBytes(Integer.toString(android.os.Process.myPid()) + "\n");
            Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            Context context = system.createPackageContext("com.android.shell", 0);
            java.util.List<String> options = java.util.Arrays.asList(args);
            Mode mode = options.contains("v4") ? Mode.HYBRID : options.contains("v3") ? Mode.MASK
                    : options.contains("v2") ? Mode.SNAPSHOT : Mode.BLUR;
            FoldShell shell = new FoldShell(context, mode);
            shell.extraEdgeBlur = !options.contains("no-edge-blur");
            try { shell.start(seconds * 1000, java.util.Arrays.asList(args).contains("early")); Looper.loop(); }
            finally { shell.close(); }
            if (shell.failed) exitCode = 1;
        }
        // app_process otherwise terminates the runtime with SIGKILL on return.
        System.exit(exitCode);
    }
}
