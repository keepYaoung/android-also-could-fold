package dev.tommy.foldshell

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class FoldTransitionService : AccessibilityService(), SensorEventListener, LiveFrameBus.Listener {
    companion object {
        private const val TAG = "FoldTransition"
    }

    private lateinit var sensorManager: SensorManager
    private var hingeSensor: Sensor? = null
    private lateinit var windowManager: WindowManager
    private lateinit var calibration: CalibrationStore
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: TransitionOverlayView? = null
    private var lastAngle = Float.NaN
    private var lastConfigWidthDp = 0
    private var direction: TransitionOverlayView.Direction? = null
    private var transitionStartMs = 0L
    private var sourceFrame: Bitmap? = null
    private var lastLiveFrame: Bitmap? = null
    private var waitingForCloseHandoff = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        calibration = CalibrationStore(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lastConfigWidthDp = resources.configuration.screenWidthDp
        LiveFrameBus.addListener(this)
        hingeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        hingeSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
            Log.i(TAG, "hinge sensor connected: ${it.name}")
        }
    }

    override fun onFrame(bitmap: Bitmap) {
        lastLiveFrame = bitmap
        overlay?.onLiveFrame(bitmap)
        if (direction != null) Log.d(TAG, "live frame ${bitmap.width}x${bitmap.height} dir=$direction angle=$lastAngle")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HINGE_ANGLE || event.values.isEmpty()) return
        val angle = event.values[0].coerceIn(0f, 180f)
        val prev = lastAngle
        lastAngle = angle
        overlay?.setAngle(angle)
        Log.d(TAG, "hinge=$angle widthDp=${resources.configuration.screenWidthDp}")
        if (prev.isNaN()) return

        val delta = angle - prev
        if (direction == null) {
            if (delta > 0.15f && angle in 1f..179f) begin(TransitionOverlayView.Direction.OPENING)
            else if (delta < -0.15f && angle in 1f..179f) begin(TransitionOverlayView.Direction.CLOSING)
        }

        if (direction == TransitionOverlayView.Direction.OPENING && angle >= 179f) {
            finish()
        }
        if (direction == TransitionOverlayView.Direction.CLOSING && angle <= 1f) {
            waitingForCloseHandoff = true
            handler.postDelayed({
                if (waitingForCloseHandoff) finish()
            }, 1500L)
        }
    }

    private fun begin(dir: TransitionOverlayView.Direction) {
        val source = lastLiveFrame ?: run {
            Log.w(TAG, "no live frame; MediaProjection not active")
            return
        }
        direction = dir
        transitionStartMs = SystemClock.elapsedRealtime()
        // Freeze an owned copy for the continuity anchor. LiveFrameBus can then
        // retire streaming frames without invalidating the transition source.
        sourceFrame?.takeIf { !it.isRecycled }?.recycle()
        val frozen = source.copy(Bitmap.Config.ARGB_8888, false)
        sourceFrame = frozen
        val handoff = 90f
        showOverlay(frozen, dir, handoff)
        overlay?.setAngle(lastAngle)
        Log.i(TAG, "begin live dir=$dir angle=$lastAngle handoff=$handoff source=${frozen.width}x${frozen.height}")
    }

    private fun showOverlay(source: Bitmap, dir: TransitionOverlayView.Direction, handoff: Float) {
        if (overlay == null) {
            overlay = TransitionOverlayView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            windowManager.addView(overlay, params)
        }
        overlay?.begin(source, dir, handoff, lastAngle)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val from = lastConfigWidthDp
        lastConfigWidthDp = newConfig.screenWidthDp
        if (from <= 0 || from == newConfig.screenWidthDp) return

        when (direction) {
            TransitionOverlayView.Direction.OPENING -> Unit
            TransitionOverlayView.Direction.CLOSING -> Unit
            null -> Unit
        }
        Log.i(TAG, "display handoff $from -> ${newConfig.screenWidthDp} at angle=$lastAngle")
        overlay?.onDisplayHandoff()
        waitingForCloseHandoff = false

        if (direction == TransitionOverlayView.Direction.CLOSING && lastAngle <= 1f) {
            handler.postDelayed({ finish() }, 260L)
        }
    }

    private fun finish() {
        val dir = direction ?: return
        Log.i(TAG, "finish live dir=$dir dt=${SystemClock.elapsedRealtime()-transitionStartMs}ms")
        direction = null
        waitingForCloseHandoff = false
        overlay?.animate()?.alpha(0f)?.setDuration(90L)?.withEndAction {
            try { windowManager.removeView(overlay) } catch (_: Throwable) {}
            overlay = null
            sourceFrame?.takeIf { !it.isRecycled }?.recycle()
            sourceFrame = null
        }?.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        LiveFrameBus.removeListener(this)
        sensorManager.unregisterListener(this)
        try { overlay?.let(windowManager::removeView) } catch (_: Throwable) {}
        overlay = null
        super.onDestroy()
    }
}
