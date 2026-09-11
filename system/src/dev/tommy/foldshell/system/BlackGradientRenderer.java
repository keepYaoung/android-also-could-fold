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
    private String identity = "";
    private int width, height, stack, lastAlpha = -1, lastVisibility = -1;
    private boolean inner, requested, snapshotAllowed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    BlackGradientRenderer(Handler handler, Consumer<Throwable> failure) {
        this.handler = handler; this.failure = failure;
    }
    void render(String display, Object address, int w, int h, int layerStack,
                boolean isInner, boolean allowSnapshot, float amount, float visibility) throws Exception {
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
                    if (closed || generation != request) { bitmap.recycle(); return; }
                    final Bitmap result = bitmap;
                    if (!handler.post(() -> {
                        if (closed || generation != request) result.recycle();
                        else snapshot = result;
                    })) result.recycle();
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
        }
        int alpha = Math.round(255 * .94f * clamp(amount));
        int bitmapAlpha = Math.round(255 * clamp(visibility));
        if (alpha == lastAlpha && bitmapAlpha == lastVisibility) return;
        Canvas canvas = canvasSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            paint.setShader(null); paint.setAlpha(255);
            if (!inner && snapshot != null) {
                paint.setAlpha(bitmapAlpha);
                canvas.drawBitmap(snapshot, null, new Rect(0, 0, pane, height), paint);
            }
            paint.setAlpha(255);
            paint.setShader(new LinearGradient(0, 0, pane, 0,
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
        lastAlpha = alpha; lastVisibility = bitmapAlpha;
    }
    private static float clamp(float x) { return Math.max(0, Math.min(1, x)); }
    private static Bitmap capture(long physical, int width, int height) throws Exception {
        IBinder token = (IBinder) SurfaceControl.class.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physical);
        if (token == null) throw new IllegalStateException("V2 display token unavailable");
        Class<?> argsClass = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs");
        Class<?> builderClass = Class.forName("android.window.ScreenCapture$DisplayCaptureArgs$Builder");
        Object builder = builderClass.getConstructor(IBinder.class).newInstance(token);
        builderClass.getMethod("setSize", int.class, int.class).invoke(builder, width, height);
        builderClass.getMethod("setCaptureSecureLayers", boolean.class).invoke(builder, false);
        builderClass.getMethod("setAllowProtected", boolean.class).invoke(builder, false);
        Object args = builderClass.getMethod("build").invoke(builder);
        Object capture = Class.forName("android.window.ScreenCapture").getMethod("captureDisplay", argsClass).invoke(null, args);
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
        if (canvasSurface != null) { canvasSurface.release(); canvasSurface = null; }
        if (layer != null) {
            try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
                SurfaceControl.Transaction.class.getMethod("remove", SurfaceControl.class).invoke(t, layer); t.apply();
            } catch (Exception error) { System.out.println("V2 cleanup=" + error.getClass().getSimpleName()); }
            finally { layer.release(); layer = null; }
        }
        if (snapshot != null) { snapshot.recycle(); snapshot = null; }
        requested = false; identity = ""; lastAlpha = -1; lastVisibility = -1;
    }
    void close() { closed = true; clear(); captureThread.shutdownNow(); }
}
