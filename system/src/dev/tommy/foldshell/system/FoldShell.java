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

/** ADB shell prototype: compositor blur only during physical fold motion.
 * No launcher replacement, screen capture, device-state override, or input window.
 * Hidden APIs are resolved once and fail closed if the firmware changes.
 */
public final class FoldShell implements SensorEventListener, DisplayManager.DisplayListener {
    private static final String NAME = "FoldTransition-SystemBlur";
    private final FoldMotion motion = new FoldMotion();
    private final Handler handler = new Handler(Looper.myLooper());
    private final SensorManager sensors;
    private final DisplayManager displays;
    private final PowerManager power;
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
    private boolean scheduled;
    private boolean closed;
    private float intensity = 1f;
    private String failureMessage;
    private boolean failed;
    private float rendered;
    private long lastTick;
    private int lastRadius = -1, lastWidth, lastHeight, lastStack = -1;
    private String lastDisplay = "";
    private boolean lastInner, lastStrongRight;
    private final Runnable frame = this::tick;

    private static Method method(String name, Class<?>... types) throws Exception {
        return SurfaceControl.Transaction.class.getMethod(name, types);
    }
    private static void log(String message) {
        System.out.println(SystemClock.elapsedRealtime() + " " + message);
    }
    private FoldShell(Context context) throws Exception {
        sensors = context.getSystemService(SensorManager.class);
        displays = context.getSystemService(DisplayManager.class);
        power = context.getSystemService(PowerManager.class);
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
        Object info = displayInfo();
        log("display=" + value(info, "logicalWidth") + "x" + value(info, "logicalHeight")
                + " inner=" + inner(info));
        Sensor hinge = sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hinge == null) throw new IllegalStateException("No hinge angle sensor");
        if (!sensors.registerListener(this, hinge, SensorManager.SENSOR_DELAY_GAME, handler))
            throw new IllegalStateException("Hinge subscription refused");
        displays.registerDisplayListener(this, handler);
        if (early) earlyMonitor = new VendorMotionMonitor(handler, this::allowed, eventMs -> {
            try {
                if (closed || !allowed()) return;
                long now = SystemClock.elapsedRealtime();
                // Expire the old panel's state before deciding whether this is
                // a new motion. Do not refresh an old closing with opening data.
                motion.amount(now);
                boolean began = motion.hint(inner(displayInfo()), now);
                motion.activity(eventMs);
                log("EARLY_BURST ageMs=" + (now - eventMs) + " direction="
                        + motion.direction() + " began=" + began);
                if (began) {
                    log("EARLY_BEGIN " + motion.direction());
                    if (!scheduled) { scheduled = true; handler.post(frame); }
                }
            } catch (Throwable error) { fail(error); }
        });
        if (duration > 0) handler.postDelayed(() -> { close(); Looper.myLooper().quitSafely(); }, duration);
        log("READY sensor=" + hinge.getName() + " durationMs=" + duration
                + " effects=physical-fold-only backend=experimental-compositor-blur");
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) return;
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
        try {
            // A panel handoff can briefly turn the display off. Keep only the
            // recent hinge-triggered state; display callbacks cannot create it.
            if (!power.isInteractive()) { destroySurface(); return; }
            long now = SystemClock.elapsedRealtime();
            Object info = displayInfo();
            if (value(info, "state") != Display.STATE_ON) { destroySurface(); return; }
            motion.display(inner(info), now);
            float target = motion.amount(now);
            if (!motion.active()) { destroySurface(); log("END"); return; }
            float dt = lastTick == 0 ? 16 : Math.min(64, now - lastTick);
            rendered += (target - rendered) * Math.min(1f, dt / (motion.releasing() ? 55f : 120f));
            lastTick = now;
            int radius = Math.round(rendered * (inner(info) ? 160 : 180) * intensity);
            if (radius > 0) render(info, radius);
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
        boolean strongRight = !isInner && motion.direction() == FoldMotion.Direction.OPENING;
        if (isInner) width /= 2;
        if (surface == null) {
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName(NAME);
            effectLayer.invoke(builder);
            surface = builder.build();
            log("LAYER created valid=" + surface.isValid());
        }
        if (radius == lastRadius && width == lastWidth && height == lastHeight && stack == lastStack && isInner == lastInner && strongRight == lastStrongRight) return;
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            layerStack.invoke(transaction, surface, stack);
            transaction.setLayer(surface, 2000000);
            crop.invoke(transaction, surface, new Rect(0, 0, width, height));
            if (isInner || strongRight) {
                // A global blur would flatten the spatial gradient, so clear it.
                blur.invoke(transaction, surface, 0);
                blurRegions.invoke(transaction, surface, BlurProfile.regions(width, height, radius, strongRight));
            } else {
                // Closing cover keeps its uniform resolving effect.
                blurRegions.invoke(transaction, surface, new float[0][]);
                blur.invoke(transaction, surface, radius);
            }
            show.invoke(transaction, surface);
            transaction.apply();
        }
        lastRadius = radius; lastWidth = width; lastHeight = height; lastStack = stack; lastInner = isInner; lastStrongRight = strongRight;
    }
    private void destroySurface() {
        if (surface != null) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                hide.invoke(transaction, surface);
                remove.invoke(transaction, surface);
                transaction.apply();
            } catch (Throwable error) { log("cleanup=" + error); }
            finally { surface.release(); surface = null; log("LAYER removed"); }
        }
        rendered = 0; lastTick = 0; lastRadius = -1;
    }
    private void fail(Throwable error) {
        failed = true;
        failureMessage = error.toString();
        log("ERROR " + error); error.printStackTrace(System.out);
        close();
    }
    public void close() {
        if (closed) return;
        closed = true;
        if (earlyMonitor != null) earlyMonitor.close();
        handler.removeCallbacksAndMessages(null);
        sensors.unregisterListener(this);
        displays.unregisterDisplayListener(this);
        motion.reset(); destroySurface();
        log("STOPPED");
    }
    /** Called on a dedicated Looper in the Shizuku shell process. */
    public static FoldShell persistent(float intensity) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Shizuku must run as ADB shell");
        if (!android.os.Build.MODEL.equals("SM-F966N")) throw new IllegalStateException("Unverified device: " + android.os.Build.MODEL);
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("currentActivityThread").invoke(null);
        if (thread == null) thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        FoldShell shell = new FoldShell(system.createPackageContext("com.android.shell", 0));
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
            FoldShell shell = new FoldShell(context);
            try { shell.start(seconds * 1000, args.length > 1 && args[1].equals("early")); Looper.loop(); }
            finally { shell.close(); }
            if (shell.failed) exitCode = 1;
        }
        // app_process otherwise terminates the runtime with SIGKILL on return.
        System.exit(exitCode);
    }
}
