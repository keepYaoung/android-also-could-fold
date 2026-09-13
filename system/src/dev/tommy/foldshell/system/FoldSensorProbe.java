package dev.tommy.foldshell.system;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only sensor diagnostic. Does not create surfaces or alter device state. */
public final class FoldSensorProbe implements SensorEventListener {
    private final Map<Integer, Set<Float>> values = new HashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final Map<Integer, Long> printed = new HashMap<>();
    @Override public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) return;
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_HINGE_ANGLE || type == 65686 || type == 65695)
            values.computeIfAbsent(type, key -> new HashSet<>()).add(event.values[0]);
        counts.merge(type, 1, Integer::sum);
        long now = SystemClock.elapsedRealtime();
        if (now - printed.getOrDefault(type, 0L) >= 100) {
            System.out.println(now + " type=" + type + " sensorNs=" + event.timestamp
                    + " values=" + java.util.Arrays.toString(event.values));
            printed.put(type, now);
        }
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new IllegalStateException("Run as ADB shell");
        int seconds = args.length == 0 ? 30 : Integer.parseInt(args[0]);
        if (seconds < 1 || seconds > 120) throw new IllegalArgumentException("Probe duration 1..120");
        Looper.prepareMainLooper();
        Class<?> cls = Class.forName("android.app.ActivityThread");
        System.out.println("SHARED_MEMORY " + ShellProcess.ensureApplicationSharedMemory());
        Object thread = cls.getMethod("systemMain").invoke(null);
        Context system = (Context) cls.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        SensorManager manager = context.getSystemService(SensorManager.class);
        Handler handler = new Handler(Looper.getMainLooper());
        FoldSensorProbe probe = new FoldSensorProbe();
        android.hardware.input.InputManager input = context.getSystemService(android.hardware.input.InputManager.class);
        Class<?> lidListenerType = Class.forName("android.hardware.input.InputManager$SemOnLidStateChangedListener");
        Object lidListener = java.lang.reflect.Proxy.newProxyInstance(lidListenerType.getClassLoader(),
                new Class<?>[]{lidListenerType}, (proxy, method, arguments) -> {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    if (method.getName().equals("toString")) return "FoldSensorProbeLidListener";
                    System.out.println("LID " + method.getName() + " " + java.util.Arrays.toString(arguments));
                    return null;
                });
        boolean lidSubscribed = false;
        try {
            Object lid = input.getClass().getMethod("semGetLidState").invoke(input);
            System.out.println("LID initial=" + lid);
            input.getClass().getMethod("semRegisterOnLidStateChangedListener", lidListenerType, Handler.class)
                    .invoke(input, lidListener, handler);
            lidSubscribed = true;
            System.out.println("LID registered=true");
        } catch (ReflectiveOperationException error) {
            System.out.println("LID unavailable=" + (error.getCause() == null ? error : error.getCause()));
        }
        final boolean cleanupLid = lidSubscribed;
        for (int type : new int[]{Sensor.TYPE_HINGE_ANGLE, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_GRAVITY, Sensor.TYPE_GAME_ROTATION_VECTOR, 65686, 65695}) {
            Sensor sensor = manager.getDefaultSensor(type);
            if (sensor == null) { System.out.println("type=" + type + " absent"); continue; }
            try {
                boolean accepted = manager.registerListener(probe, sensor, 20000, handler);
                System.out.println("type=" + type + " name=" + sensor.getName() + " registered=" + accepted);
            } catch (SecurityException denied) {
                System.out.println("type=" + type + " DENIED " + denied.getMessage());
            }
        }
        handler.postDelayed(() -> {
            manager.unregisterListener(probe);
            System.out.println("SUMMARY counts=" + probe.counts);
            for (Map.Entry<Integer, Set<Float>> entry : probe.values.entrySet()) {
                System.out.println("SUMMARY type=" + entry.getKey() + " uniqueAngles=" + entry.getValue().size()
                        + " samples=" + entry.getValue());
            }
            Looper.myLooper().quitSafely();
        }, seconds * 1000L);
        System.out.println("SENSOR PROBE READY durationSeconds=" + seconds);
        try { Looper.loop(); } finally {
            manager.unregisterListener(probe);
            if (cleanupLid) input.getClass().getMethod("semUnregisterOnLidStateChangedListener", lidListenerType)
                    .invoke(input, lidListener);
        }
        System.exit(0);
    }
}
