package dev.tommy.foldshell

import kotlin.math.pow

object TransitionGeometry {
    data class State(
        val expansion: Float,
        val edgeShade: Float,
        val centerShade: Float,
        val contentScale: Float
    )

    fun state(
        angle: Float,
        direction: TransitionOverlayView.Direction,
        handoffAngle: Float
    ): State {
        val a = angle.coerceIn(0f, 180f)
        val h = handoffAngle.coerceIn(20f, 160f)

        val phase = when (direction) {
            TransitionOverlayView.Direction.OPENING -> {
                if (a <= h) 0.52f * easeOutCubic(a / h)
                else 0.52f + 0.48f * springish((a - h) / (180f - h))
            }
            TransitionOverlayView.Direction.CLOSING -> {
                // Keep the same geometric coordinate system: 0=closed, 1=open.
                if (a >= h) 0.52f + 0.48f * springish((a - h) / (180f - h))
                else 0.52f * easeOutCubic(a / h)
            }
        }.coerceIn(0f, 1f)

        val middle = 1f - kotlin.math.abs(phase * 2f - 1f)
        return State(
            expansion = phase,
            edgeShade = 0.10f * middle,
            centerShade = 0.16f * middle,
            contentScale = 0.985f + 0.015f * phase
        )
    }

    private fun easeOutCubic(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return 1f - (1f - t).pow(3)
    }

    private fun springish(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        // Monotonic approximation of a critically damped finish.
        return 1f - (1f - t).pow(2.4f)
    }
}
