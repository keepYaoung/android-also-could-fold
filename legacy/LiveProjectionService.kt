package dev.tommy.foldshell

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.view.WindowManager

class LiveProjectionService : Service() {
    companion object {
        private const val TAG = "LiveProjection"
        private const val CHANNEL_ID = "fold_capture"
        private const val NOTIF_ID = 42
        const val ACTION_START = "dev.tommy.foldshell.START_CAPTURE"
        const val ACTION_STOP = "dev.tommy.foldshell.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameBusy = false
    private var lastFrameMs = 0L
    private var frameCount = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            ACTION_START -> {
                startForeground(NOTIF_ID, notification())
                if (projection == null) {
                    val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    @Suppress("DEPRECATION")
                    val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        projection = mgr.getMediaProjection(resultCode, data)
                        projection?.registerCallback(object : MediaProjection.Callback() {
                            override fun onStop() {
                                Log.i(TAG, "projection stopped")
                                releaseDisplay()
                                stopSelf()
                            }
                        }, mainThreadHandler)
                        createDisplay()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (projection != null) resizeDisplayForCurrentBounds()
    }

    private val mainThreadHandler by lazy { android.os.Handler(mainLooper) }

    private fun currentCaptureSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        val width = (bounds.width() / 2).coerceAtLeast(1)
        val height = (bounds.height() / 2).coerceAtLeast(1)
        return Triple(width, height, resources.displayMetrics.densityDpi)
    }

    private fun createReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                if (frameBusy) {
                    r.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastFrameMs < 100L) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastFrameMs = now
                frameBusy = true
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val widthNow = image.width
                    val heightNow = image.height
                    val rowPadding = rowStride - pixelStride * widthNow
                    val paddedWidth = widthNow + rowPadding / pixelStride
                    val padded = Bitmap.createBitmap(paddedWidth, heightNow, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(buffer)
                    val cropped = if (paddedWidth == widthNow) padded else Bitmap.createBitmap(padded, 0, 0, widthNow, heightNow).also {
                        if (it !== padded) padded.recycle()
                    }
                    LiveFrameBus.publish(cropped)
                    frameCount++
                    if (frameCount % 20 == 0) Log.i(TAG, "frames=$frameCount latest=${cropped.width}x${cropped.height}")
                } catch (t: Throwable) {
                    Log.e(TAG, "frame decode failed", t)
                } finally {
                    image.close()
                    frameBusy = false
                }
            }, mainThreadHandler)
        }
    }

    private fun createDisplay() {
        val (width, height, density) = currentCaptureSize()
        Log.i(TAG, "create display ${width}x$height density=$density")
        reader = createReader(width, height)
        virtualDisplay = projection?.createVirtualDisplay(
            "FoldLiveCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            mainThreadHandler
        )
    }

    private fun resizeDisplayForCurrentBounds() {
        val vd = virtualDisplay ?: return
        val (width, height, density) = currentCaptureSize()
        Log.i(TAG, "resize display -> ${width}x$height density=$density")
        val newReader = createReader(width, height)
        try {
            vd.resize(width, height, density)
            vd.surface = newReader.surface
            val old = reader
            reader = newReader
            mainThreadHandler.postDelayed({ try { old?.close() } catch (_: Throwable) {} }, 350L)
        } catch (t: Throwable) {
            Log.e(TAG, "resize failed", t)
            newReader.close()
        }
    }

    private fun releaseDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
    }

    private fun stopCapture() {
        releaseDisplay()
        projection?.stop()
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseDisplay()
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Fold live capture", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Fold Transition")
        .setContentText("Live screen capture is active for fold effects")
        .setOngoing(true)
        .build()
}
