package dev.tommy.foldshell.system;

import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.IBinder;
import android.view.Surface;
import android.view.SurfaceControl;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** V2: one in-memory cover snapshot per motion; live inner display with a left shadow. */
final class BlackGradientRenderer {
    private final Handler handler;
    private final Consumer<Throwable> failure;
    private final ExecutorService captureThread = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FoldSnapshot"); thread.setDaemon(true); return thread;
    });
    private volatile int generation;
    private volatile boolean closed;
    private SurfaceControl layer;
    private Surface canvasSurface;
    private Bitmap snapshot;
    private final java.util.Set<Bitmap> pendingBitmaps = new java.util.HashSet<>();
    private String identity = "";
    private int width, height, stack, lastAlpha = -1, lastVisibility = -1;
    private boolean inner, requested, snapshotAllowed;
    private float coverProgress;
    private long progressTick;
    private int lastLeft = -1;
    private float lastProgress = -1;
    private int diagnosticStep = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    BlackGradientRenderer(Handler handler, Consumer<Throwable> failure) {
        this.handler = handler; this.failure = failure;
    }
    void render(String display, Object address, int w, int h, int layerStack,
                boolean isInner, boolean allowSnapshot, float amount, float visibility, boolean opening, float targetProgress, float intensity) throws Exception {
        if (closed) return;
        if (!identity.equals(display) || width != w || height != h || inner != isInner || stack != layerStack || snapshotAllowed != allowSnapshot) {
            clear(); identity = display; width = w; height = h; inner = isInner; stack = layerStack; snapshotAllowed = allowSnapshot;
        }
        if (!inner && snapshotAllowed && !requested) {
            requested = true;
            final int request = generation;
            // Use the currently active physical display, never an arbitrary first display.
            long physical = (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
            captureThread.execute(() -> {
                Bitmap bitmap = null;
                try {
                    if (closed || generation != request) return;
                    bitmap = capture(physical, w, h);
                    final Bitmap result = bitmap;
                    synchronized (pendingBitmaps) {
                        if (closed || generation != request) { result.recycle(); return; }
                        pendingBitmaps.add(result);
                    }
                    if (!handler.post(() -> {
                        synchronized (pendingBitmaps) {
                            pendingBitmaps.remove(result);
                            if (closed || generation != request) {
                                if (!result.isRecycled()) result.recycle();
                            } else {
                                snapshot = result;
                                System.out.println("V2 snapshot ready " + w + "x" + h);
                            }
                        }
                    })) {
                        synchronized (pendingBitmaps) {
                            pendingBitmaps.remove(result);
                            if (!result.isRecycled()) result.recycle();
                        }
                    }
                } catch (Throwable error) {
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                    handler.post(() -> { if (!closed && generation == request) failure.accept(error); });
                }
            });
        }
        if (!inner && snapshotAllowed && snapshot == null) return;
        int pane = inner ? width / 2 : width;
        if (layer == null) {
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName("FoldTransition-V2")
                    .setBufferSize(pane, height).setFormat(PixelFormat.RGBA_8888).setOpaque(false);
            // Do not expose the retained snapshot in subsequent captures by other apps.
            SurfaceControl.Builder.class.getMethod("setSecure", boolean.class).invoke(builder, true);
            layer = builder.build();
            canvasSurface = new Surface(layer);
            System.out.println("V2 layer ready panel=" + (inner ? "inner" : "cover") + " snapshotAllowed=" + snapshotAllowed);
        }
        long now = android.os.SystemClock.elapsedRealtime();
        float dt = progressTick == 0 ? 16 : Math.min(64, now - progressTick);
        progressTick = now;
        // Release opacity must never rewind the spatial opening animation.
        if (!inner && opening)
            coverProgress += Math.max(0, targetProgress - coverProgress) * Math.min(1, dt / 140f);
        if (!inner && opening) {
            int step = (int) (coverProgress * 5);
            if (step != diagnosticStep) {
                diagnosticStep = step;
                System.out.println("V2 cover progress=" + coverProgress + " scale="
                        + CoverReveal.depthScale(coverProgress) + " snapshot=" + (snapshot != null));
            }
        }
        float reveal = !inner && opening ? CoverReveal.opacity(coverProgress) : 1;
        int left = !inner && opening ? Math.round(pane * CoverReveal.left(coverProgress)) : 0;
        // Cover reveal already has its own envelope: multiplying by angle strength
        // again made the entrance nearly invisible before the coarse 90° event.
        int alpha = Math.round(255 * clamp(.94f * (!inner && opening
                ? visibility * intensity : amount) * reveal));
        int bitmapAlpha = Math.round(255 * clamp(visibility) * (!inner && opening ? CoverReveal.snapshot(coverProgress) : 1));
        if (alpha == lastAlpha && bitmapAlpha == lastVisibility && left == lastLeft && coverProgress == lastProgress) return;
        Canvas canvas = canvasSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            paint.setShader(null); paint.setAlpha(255);
            if (!inner && snapshot != null) {
                // Fade the backing and image as one group. Otherwise the live,
                // full-size screen shows around the reduced snapshot as a duplicate.
                int saved = canvas.saveLayerAlpha(0, 0, pane, height, bitmapAlpha);
                try {
                    paint.setAlpha(255);
                    if (opening) {
                        canvas.drawColor(Color.BLACK);
                        float scale = CoverReveal.depthScale(coverProgress);
                        canvas.scale(scale, scale, pane / 2f, height / 2f);
                    }
                    canvas.drawBitmap(snapshot, null, new Rect(0, 0, pane, height), paint);
                } finally { canvas.restoreToCount(saved); }
            }
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(left, 0, !inner && opening ? left + pane * .85f : pane, 0,
                    inner ? Color.argb(alpha, 0, 0, 0) : Color.TRANSPARENT,
                    inner ? Color.TRANSPARENT : Color.argb(alpha, 0, 0, 0), Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, pane, height, paint);
        } finally { paint.setShader(null); canvasSurface.unlockCanvasAndPost(canvas); }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SurfaceControl.Transaction.class.getMethod("setLayerStack", SurfaceControl.class, int.class).invoke(t, layer, stack);
            t.setLayer(layer, 2000000);
            SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class).invoke(t, layer);
            t.apply();
        }
        lastAlpha = alpha; lastVisibility = bitmapAlpha; lastLeft = left; lastProgress = coverProgress;
    }
    private static float clamp(float x) { return Math.max(0, Math.min(1, x)); }
    private static Bitmap capture(long physical, int width, int height) throws Exception {
        IBinder token = (IBinder) SurfaceControl.class.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physical);
        if (token == null) throw new IllegalStateException("V2 display token unavailable");
        String api;
        try { Class.forName("android.window.ScreenCaptureInternal$DisplayCaptureArgs"); api = "android.window.ScreenCaptureInternal"; }
        catch (ClassNotFoundException legacyApi) { api = "android.window.ScreenCapture"; }
        Class<?> argsClass = Class.forName(api + "$DisplayCaptureArgs");
        Class<?> builderClass = Class.forName(api + "$DisplayCaptureArgs$Builder");
        Object builder = builderClass.getConstructor(IBinder.class).newInstance(token);
        builderClass.getMethod("setSize", int.class, int.class).invoke(builder, width, height);
        Class<?> policies = api.endsWith("Internal")
                ? Class.forName("android.window.ScreenCapture$ScreenCaptureParams") : null;
        CapturePolicy.redact(builder, policies);
        Object args = builderClass.getMethod("build").invoke(builder);
        Object capture = Class.forName(api).getMethod("captureDisplay", argsClass).invoke(null, args);
        if (capture == null) throw new IllegalStateException("V2 screen capture unavailable; select V1 blur");
        HardwareBuffer buffer = (HardwareBuffer) capture.getClass().getMethod("getHardwareBuffer").invoke(capture);
        Bitmap hardware = null;
        try {
            if ((Boolean) capture.getClass().getMethod("containsSecureLayers").invoke(capture))
                throw new IllegalStateException("V2 refuses secure capture content");
            hardware = (Bitmap) capture.getClass().getMethod("asBitmap").invoke(capture);
            if (hardware == null) throw new IllegalStateException("V2 empty capture");
            Bitmap copy = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IllegalStateException("V2 snapshot allocation failed");
            return copy;
        } finally {
            if (hardware != null) hardware.recycle();
            if (buffer != null) buffer.close();
        }
    }
    void clear() {
        generation++;
        // close() also removes Handler callbacks; own queued results so those
        // bitmaps are released even when their delivery callback never executes.
        synchronized (pendingBitmaps) {
            for (Bitmap bitmap : pendingBitmaps) if (!bitmap.isRecycled()) bitmap.recycle();
            pendingBitmaps.clear();
        }
        if (canvasSurface != null) { canvasSurface.release(); canvasSurface = null; }
        if (layer != null) {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                SurfaceControl.Transaction.class.getMethod("remove", SurfaceControl.class).invoke(t, layer); t.apply();
            } catch (Exception error) { System.out.println("V2 cleanup=" + error.getClass().getSimpleName()); }
            finally { layer.release(); layer = null; }
        }
        if (snapshot != null) { snapshot.recycle(); snapshot = null; }
        requested = false; identity = ""; lastAlpha = -1; lastVisibility = -1; lastLeft = -1;
        coverProgress = 0; progressTick = 0; lastProgress = -1; diagnosticStep = -1;
    }
    void close() { closed = true; clear(); captureThread.shutdownNow(); }
}
