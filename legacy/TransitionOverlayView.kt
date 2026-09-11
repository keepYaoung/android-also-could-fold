package dev.tommy.foldshell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Live GPU overlay driven directly by hinge angle.
 *
 * OPENING
 * - before handoff: cover frame blur-out
 * - after handoff: final inner frame is live; LEFT pane blur-in (blur -> sharp)
 *   while frozen cover composition remains as the RIGHT continuity anchor
 *
 * CLOSING
 * - before handoff: LEFT pane blur-out, RIGHT pane remains sharp
 * - after handoff: live cover frame blur-in (blur -> sharp)
 */
class TransitionOverlayView(context: Context) : FrameLayout(context) {
    enum class Direction { OPENING, CLOSING }

    private val base = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }
    private val left = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }
    private val rightAnchor = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
    }

    private var direction = Direction.OPENING
    private var handoffAngle = 90f
    private var angle = 0f
    private var frozenSource: Bitmap? = null
    private var latestLive: Bitmap? = null
    private var handedOff = false

    private val maxBlur = 34f * resources.displayMetrics.density

    init {
        addView(base, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(left, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(rightAnchor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isClickable = false
        isFocusable = false
    }

    fun begin(source: Bitmap, direction: Direction, handoffAngle: Float, startAngle: Float) {
        this.direction = direction
        this.handoffAngle = handoffAngle.coerceIn(10f, 170f)
        this.angle = startAngle
        this.handedOff = false
        frozenSource = source
        latestLive = source
        base.setImageBitmap(source)
        left.setImageBitmap(source)
        rightAnchor.setImageBitmap(source)
        updateGeometry()
    }

    fun onLiveFrame(bitmap: Bitmap) {
        latestLive = bitmap
        base.setImageBitmap(bitmap)
        if (handedOff) left.setImageBitmap(bitmap)
        updateGeometry()
    }

    fun onDisplayHandoff() {
        handedOff = true
        latestLive?.let {
            base.setImageBitmap(it)
            left.setImageBitmap(it)
        }
        frozenSource?.let { rightAnchor.setImageBitmap(it) }
        updateGeometry()
    }

    fun setAngle(value: Float) {
        angle = value.coerceIn(0f, 180f)
        updateGeometry()
    }

    private fun updateGeometry() {
        when (direction) {
            Direction.OPENING -> updateOpening()
            Direction.CLOSING -> updateClosing()
        }
    }

    private fun updateOpening() {
        if (!handedOff) {
            // 0 -> handoff: whole cover sharp -> blurred/dissolved.
            val p = (angle / handoffAngle).coerceIn(0f, 1f)
            base.visibility = VISIBLE
            base.alpha = 1f - 0.10f * p
            blur(base, maxBlur * p)
            left.visibility = GONE
            rightAnchor.visibility = GONE
        } else {
            // handoff -> 180: real inner frame is already present.
            val p = ((angle - handoffAngle) / (180f - handoffAngle)).coerceIn(0f, 1f)
            base.visibility = VISIBLE
            base.alpha = 1f
            blur(base, 0f)

            // LEFT half starts blurred and resolves as device opens.
            left.visibility = VISIBLE
            setHalf(left, isLeft = true)
            left.alpha = 1f - p
            blur(left, maxBlur * (1f - p))

            // Cover layout is conceptually the RIGHT pane. Preserve briefly.
            rightAnchor.visibility = VISIBLE
            setHalf(rightAnchor, isLeft = false)
            rightAnchor.alpha = (1f - p * 1.15f).coerceIn(0f, 1f)
            blur(rightAnchor, maxBlur * 0.22f * (1f - p))
        }
    }

    private fun updateClosing() {
        if (!handedOff) {
            // 180 -> handoff: inner RIGHT remains sharp; only LEFT blurs out.
            val p = ((180f - angle) / (180f - handoffAngle)).coerceIn(0f, 1f)
            base.visibility = VISIBLE
            base.alpha = 1f
            blur(base, 0f)

            left.visibility = VISIBLE
            setHalf(left, isLeft = true)
            left.alpha = 1f
            blur(left, maxBlur * p)

            rightAnchor.visibility = GONE
        } else {
            // handoff -> 0: live cover comes in blurred and resolves sharp.
            val p = ((handoffAngle - angle) / handoffAngle).coerceIn(0f, 1f)
            base.visibility = VISIBLE
            base.alpha = 1f
            blur(base, maxBlur * (1f - p))
            left.visibility = GONE
            rightAnchor.visibility = GONE
        }
    }

    private fun setHalf(view: ImageView, isLeft: Boolean) {
        val lp = view.layoutParams as LayoutParams
        lp.width = LayoutParams.MATCH_PARENT
        lp.height = LayoutParams.MATCH_PARENT
        lp.gravity = if (isLeft) Gravity.START else Gravity.END
        view.layoutParams = lp
        view.clipBounds = android.graphics.Rect(
            if (isLeft) 0 else width / 2,
            0,
            if (isLeft) width / 2 else width,
            height
        )
    }

    private fun blur(view: ImageView, radius: Float) {
        if (radius < 0.5f) {
            view.setRenderEffect(null)
        } else {
            view.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        }
    }
}
