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

/** V2: memory snapshots, panel-specific perspective, and a redacted-lock mask fallback.
 * V3 (flatMask): no capture; the live screen stays flat while the folding-away region
 * darkens with a black gradient and blur, in proportion to the same relative-gyro depth. */
final class BlackGradientRenderer {
    private final Handler handler;
    private final Consumer<Throwable> failure;
    private final boolean flatMask, shade, stretch;
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
    private boolean inner, requested, locked, captureUnavailable;
    private float coverProgress;
    private long captureStarted, progressTick, flattenStart = -1;
    private float flattenFrom;
    private int lastLeft = -1;
    private float lastProgress = -1;
    private int diagnosticStep = -1;
    private final Matrix perspective = new Matrix();
    private BitmapShader snapshotShader;
    private final Path snapshotOutline = new Path();
    private final Paint snapshotPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float[] sourceCorners = new float[8], targetCorners = new float[8];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    BlackGradientRenderer(Handler handler, Consumer<Throwable> failure) { this(handler, failure, false); }
    BlackGradientRenderer(Handler handler, Consumer<Throwable> failure, boolean flatMask) { this(handler, failure, flatMask, flatMask); }
    /** shade: draw the V3 wide deepening gradient instead of V2's linear gradient (V4 = snapshot + shade). */
    BlackGradientRenderer(Handler handler, Consumer<Throwable> failure, boolean flatMask, boolean shade) { this(handler, failure, flatMask, shade, false); }
    /** stretch: V5 slides the snapshot outward horizontally instead of receding it in perspective. */
    BlackGradientRenderer(Handler handler, Consumer<Throwable> failure, boolean flatMask, boolean shade, boolean stretch) {
        this.handler = handler; this.failure = failure; this.flatMask = flatMask; this.shade = shade; this.stretch = stretch;
    }
    boolean flat() { return flatMask; }
    void render(String display, Object address, int w, int h, int layerStack,
                boolean isInner, boolean isLocked, float amount, float visibility, boolean opening, float targetProgress, float entryProgress, float intensity) throws Exception {
        if (closed) return;
        if (!identity.equals(display) || width != w || height != h || inner != isInner || stack != layerStack || locked != isLocked) {
            clear(); identity = display; width = w; height = h; inner = isInner; stack = layerStack; locked = isLocked;
        }
        if (!requested && flatMask) {
            // V3 never captures: the live screen stays visible under a flat mask.
            requested = true; captureUnavailable = true;
            System.out.println("V3 mask panel=" + (isInner ? "inner" : "cover") + " locked=" + isLocked);
        }
        if (!requested) {
            requested = true; captureStarted = android.os.SystemClock.elapsedRealtime();
            final int request = generation;
            // Use the currently active physical display, never an arbitrary first display.
            long physical = (Long) address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
            captureThread.execute(() -> {
                Bitmap bitmap = null;
                try {
                    if (closed || generation != request) return;
                    // Right after a panel handoff the new panel has not drawn its first frame
                    // yet, so the first capture is blank. Retry briefly instead of giving up.
                    for (int attempt = 0; ; attempt++) {
                        if (closed || generation != request) return;
                        bitmap = capture(physical, w, h);
                        if (isInner) {
                            Bitmap leftHalf = Bitmap.createBitmap(bitmap, 0, 0, w / 2, h);
                            if (leftHalf != bitmap) bitmap.recycle();
                            bitmap = leftHalf;
                        }
                        if (!isBlank(bitmap)) break;
                        bitmap.recycle(); bitmap = null;
                        if (attempt >= 6) throw new CapturePolicy.Unavailable("Blank panel snapshot");
                        try { Thread.sleep(50); } catch (InterruptedException stopped) { return; }
                    }
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
                                System.out.println("V2 snapshot ready " + result.getWidth() + "x" + result.getHeight());
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
                    handler.post(() -> {
                        if (closed || generation != request) return;
                        if (CapturePolicy.canFallback(error, isLocked)) {
                            captureUnavailable = true;
                            System.out.println("V2 capture fallback=live-mask locked=" + isLocked + " reason=" + error.getClass().getSimpleName());
                        } else failure.accept(error);
                    });
                }
            });
        }
        if (snapshot == null && !captureUnavailable) {
            if (android.os.SystemClock.elapsedRealtime() - captureStarted < 450) return;
            // Invalidate a late result before drawing the mask. Never replace it
            // mid-cycle with a capture that may include a newer panel/overlay.
            generation++; captureUnavailable = true;
            System.out.println("V2 capture fallback=live-mask reason=deadline-450ms");
        }
        int pane = inner ? width / 2 : width;
        if (layer == null) {
            SurfaceControl.Builder builder = new SurfaceControl.Builder().setName("FoldTransition-V2")
                    .setBufferSize(pane, height).setFormat(PixelFormat.RGBA_8888).setOpaque(false);
            // Do not expose the retained snapshot in subsequent captures by other apps.
            SurfaceControl.Builder.class.getMethod("setSecure", boolean.class).invoke(builder, true);
            layer = builder.build();
            canvasSurface = new Surface(layer);
            System.out.println("V2 layer ready panel=" + (inner ? "inner" : "cover") + " locked=" + locked + " snapshot=" + (snapshot != null));
        }
        long now = android.os.SystemClock.elapsedRealtime();
        float dt = progressTick == 0 ? 16 : Math.min(64, now - progressTick);
        // Seed only when a frame can actually be drawn, after capture/fallback readiness.
        // The endpoint target may already be zero by then; it must not erase entry.
        if (progressTick == 0 && inner && opening)
            coverProgress = Math.max(targetProgress, entryProgress);
        progressTick = now;
        if (visibility < 1) {
            // Hold the last visible geometry while the entire layer dissolves.
            flattenStart = -1;
        } else if (inner && opening && targetProgress == 0 && coverProgress > 0) {
            if (flattenStart < 0) { flattenStart = now; flattenFrom = coverProgress; }
            coverProgress = CoverReveal.settleDepth(flattenFrom, now - flattenStart);
        } else {
            flattenStart = -1;
            // V3 smooths harder: a shade should not tremble with gyro noise.
            coverProgress += (targetProgress - coverProgress) * Math.min(1, dt / (flatMask ? 90f : 45f));
        }
        {
            int step = (int) (coverProgress * 5);
            if (step != diagnosticStep) {
                diagnosticStep = step;
                System.out.println("V2 depth panel=" + (inner ? "inner" : "cover") + " progress=" + coverProgress + " scale="
                        + CoverReveal.depthScale(coverProgress) + " snapshot=" + (snapshot != null));
            }
        }
        float reveal = inner ? coverProgress : opening ? CoverReveal.opacity(coverProgress) : 1;
        int left = !inner && opening ? Math.round(pane * CoverReveal.left(coverProgress)) : 0;
        if (flatMask) { drawFlatMask(pane, visibility, intensity); return; }
        // Cover reveal already has its own envelope: multiplying by angle strength
        // again made the entrance nearly invisible before the coarse 90° event.
        int alpha = Math.round(255 * clamp(.94f * (!inner && opening
                ? intensity : inner ? intensity : visibility > 0 ? amount / visibility : 0) * reveal));
        int bitmapAlpha = Math.round(255 * clamp(visibility));
        if (alpha == lastAlpha && bitmapAlpha == lastVisibility && left == lastLeft && coverProgress == lastProgress) return;
        Canvas canvas = canvasSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            paint.setShader(null); paint.setAlpha(255);
            if (snapshot != null) {
                // Fade the backing and image as one group. Otherwise the live,
                // full-size screen shows around the reduced snapshot as a duplicate.
                int saved = canvas.saveLayerAlpha(0, 0, pane, height, 255);
                try {
                    paint.setAlpha(255);
                    if (inner || opening) {
                        canvas.drawColor(Color.BLACK);
                        CoverReveal.corners(0, sourceCorners);
                        if (stretch) {
                            // While dissolving, the stretched image also returns to native size.
                            float p = coverProgress * clamp(visibility);
                            if (inner) CoverReveal.innerStretchCorners(p, targetCorners);
                            else CoverReveal.stretchCorners(p, targetCorners);
                        } else if (inner) CoverReveal.innerCorners(coverProgress, targetCorners);
                        else CoverReveal.corners(coverProgress, targetCorners);
                        for (int i = 0; i < 8; i += 2) {
                            sourceCorners[i] *= pane; sourceCorners[i + 1] *= height;
                            targetCorners[i] *= pane; targetCorners[i + 1] *= height;
                        }
                        if (!perspective.setPolyToPoly(sourceCorners, 0, targetCorners, 0, 4))
                            throw new IllegalStateException("V2 invalid cover perspective");
                        if (snapshotShader == null)
                            snapshotShader = new BitmapShader(snapshot, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                        snapshotShader.setLocalMatrix(perspective);
                        snapshotOutline.reset();
                        snapshotOutline.moveTo(targetCorners[0], targetCorners[1]);
                        for (int i = 2; i < 8; i += 2)
                            snapshotOutline.lineTo(targetCorners[i], targetCorners[i + 1]);
                        snapshotOutline.close();
                        // drawBitmap filtering smooths texels but does not guarantee
                        // coverage AA on a transformed outer edge. Rasterize an AA
                        // path instead, with the same perspective in its bitmap shader.
                        snapshotPaint.setShader(snapshotShader);
                        snapshotPaint.setAlpha(inner ? 255 : Math.round(255 * CoverReveal.snapshot(coverProgress)));
                        canvas.drawPath(snapshotOutline, snapshotPaint);
                    } else {
                        canvas.drawBitmap(snapshot, new Rect(0, 0, pane, height), new Rect(0, 0, pane, height), paint);
                    }
                } finally { canvas.restoreToCount(saved); }
            }
            if (snapshot == null && captureUnavailable) {
                // Redacted/denied lock content stays live; black only the area
                // outside the projected plane. This masks, rather than warps, it.
                if (stretch) { CoverReveal.corners(0, targetCorners); } // a stretched plane covers the pane
                else if (inner) CoverReveal.innerCorners(coverProgress, targetCorners);
                else CoverReveal.corners(coverProgress, targetCorners);
                Path outside = new Path();
                outside.setFillType(Path.FillType.EVEN_ODD);
                outside.addRect(0, 0, pane, height, Path.Direction.CW);
                outside.moveTo(targetCorners[0] * pane, targetCorners[1] * height);
                for (int i = 2; i < 8; i += 2) outside.lineTo(targetCorners[i] * pane, targetCorners[i + 1] * height);
                outside.close();
                paint.setColor(Color.BLACK); paint.setAlpha(255);
                canvas.drawPath(outside, paint);
            }
            paint.setAlpha(255);
            if (shade) drawShade(canvas, pane, intensity, stretch ? coverProgress * clamp(visibility) : coverProgress);
            else {
                paint.setShader(new LinearGradient(left, 0, !inner && opening ? left + pane * .85f : pane, 0,
                        inner ? Color.argb(alpha, 0, 0, 0) : Color.TRANSPARENT,
                        inner ? Color.TRANSPARENT : Color.argb(alpha, 0, 0, 0), Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, pane, height, paint);
            }
        } finally { paint.setShader(null); canvasSurface.unlockCanvasAndPost(canvas); }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SurfaceControl.Transaction.class.getMethod("setLayerStack", SurfaceControl.class, int.class).invoke(t, layer, stack);
            t.setLayer(layer, FoldShell.CANVAS_Z);
            // One compositor alpha fades image, backing, mask and shadow together.
            t.setAlpha(layer, clamp(visibility));
            SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class).invoke(t, layer);
            t.apply();
        }
        lastAlpha = alpha; lastVisibility = bitmapAlpha; lastLeft = left; lastProgress = coverProgress;
    }
    float depthProgress() { return coverProgress; }

    /** The wide, deepening shade shared by V3 (alone) and V4 (over the V2 plane). Same profile as flatRegions. */
    private void drawShade(Canvas canvas, int pane, float intensity, float progress) {
        // V4 (shade over the perspective snapshot) is far wider and darker; V3 and V5 use the moderate shade.
        boolean wide = shade && !flatMask && !stretch;
        float reach = CoverReveal.maskReach(progress, wide), strength = CoverReveal.maskStrength(progress);
        int start = Math.round(pane * (inner ? reach : 1 - reach));
        int edgeAlpha = Math.round(255 * clamp((wide ? 1f : .94f) * intensity) * strength);
        if (edgeAlpha <= 0) return;
        int steps = 8;
        int[] colors = new int[steps + 1]; float[] stops = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            stops[i] = i / (float) steps;
            colors[i] = Color.argb(Math.round(edgeAlpha * CoverReveal.maskProfile(stops[i], wide)), 0, 0, 0);
        }
        paint.setAlpha(255);
        if (inner) {
            paint.setShader(new LinearGradient(start, 0, 0, 0, colors, stops, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, start, height, paint);
        } else {
            paint.setShader(new LinearGradient(start, 0, pane, 0, colors, stops, Shader.TileMode.CLAMP));
            canvas.drawRect(start, 0, pane, height, paint);
        }
        paint.setShader(null);
    }
    /** V3: a wide shade over the folding-away side that deepens with rotation. Never a solid block. */
    private void drawFlatMask(int pane, float visibility, float intensity) throws Exception {
        float reach = CoverReveal.maskReach(coverProgress), strength = CoverReveal.maskStrength(coverProgress);
        // Cover: shade grows from the right edge. Inner left half: from the outer left edge.
        int start = Math.round(pane * (inner ? reach : 1 - reach));
        int edgeAlpha = Math.round(255 * clamp(.94f * intensity) * strength);
        int visible = Math.round(255 * clamp(visibility));
        if (edgeAlpha == lastAlpha && visible == lastVisibility && start == lastLeft && coverProgress == lastProgress) return;
        Canvas canvas = canvasSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            paint.setShader(null); paint.setAlpha(255);
            drawShade(canvas, pane, intensity, coverProgress);
        } finally { paint.setShader(null); canvasSurface.unlockCanvasAndPost(canvas); }
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            SurfaceControl.Transaction.class.getMethod("setLayerStack", SurfaceControl.class, int.class).invoke(t, layer, stack);
            t.setLayer(layer, FoldShell.CANVAS_Z);
            t.setAlpha(layer, clamp(visibility));
            SurfaceControl.Transaction.class.getMethod("show", SurfaceControl.class).invoke(t, layer);
            t.apply();
        }
        lastAlpha = edgeAlpha; lastVisibility = visible; lastLeft = start; lastProgress = coverProgress;
    }

    private static boolean isBlank(Bitmap bitmap) {
        for (int y = 0; y < bitmap.getHeight(); y += Math.max(1, bitmap.getHeight() / 32))
            for (int x = 0; x < bitmap.getWidth(); x += Math.max(1, bitmap.getWidth() / 32)) {
                int pixel = bitmap.getPixel(x, y);
                if (CapturePolicy.visiblePixel(pixel)) return false;
            }
        return true;
    }
    private static float clamp(float x) { return Math.max(0, Math.min(1, x)); }
    private static Bitmap capture(long physical, int width, int height) throws Exception {
        IBinder token = (IBinder) SurfaceControl.class.getMethod("getPhysicalDisplayToken", long.class).invoke(null, physical);
        if (token == null) throw new CapturePolicy.Unavailable("V2 display token unavailable");
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
        if (capture == null) throw new CapturePolicy.Unavailable("V2 screen capture unavailable");
        HardwareBuffer buffer = (HardwareBuffer) capture.getClass().getMethod("getHardwareBuffer").invoke(capture);
        Bitmap hardware = null;
        try {
            if ((Boolean) capture.getClass().getMethod("containsSecureLayers").invoke(capture))
                throw new CapturePolicy.Unavailable("V2 refuses secure capture content");
            hardware = (Bitmap) capture.getClass().getMethod("asBitmap").invoke(capture);
            if (hardware == null) throw new CapturePolicy.Unavailable("V2 empty capture");
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
        snapshotPaint.setShader(null); snapshotShader = null; snapshotOutline.reset();
        if (snapshot != null) { snapshot.recycle(); snapshot = null; }
        requested = false; captureUnavailable = false; identity = ""; lastAlpha = -1; lastVisibility = -1; lastLeft = -1;
        coverProgress = 0; progressTick = 0; flattenStart = -1; lastProgress = -1; diagnosticStep = -1;
    }
    void close() { closed = true; clear(); captureThread.shutdownNow(); }
}
