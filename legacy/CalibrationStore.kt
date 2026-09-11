package dev.tommy.foldshell

import android.content.Context

class CalibrationStore(context: Context) {
    private val prefs = context.getSharedPreferences("fold_calibration", Context.MODE_PRIVATE)

    var openHandoffAngle: Float
        get() = prefs.getFloat("open_handoff", 92f)
        set(value) = prefs.edit().putFloat("open_handoff", value.coerceIn(20f, 160f)).apply()

    var closeHandoffAngle: Float
        get() = prefs.getFloat("close_handoff", 88f)
        set(value) = prefs.edit().putFloat("close_handoff", value.coerceIn(20f, 160f)).apply()

    fun learnOpen(angle: Float) {
        openHandoffAngle = blend(openHandoffAngle, angle)
    }

    fun learnClose(angle: Float) {
        closeHandoffAngle = blend(closeHandoffAngle, angle)
    }

    private fun blend(old: Float, sample: Float): Float = old * 0.7f + sample * 0.3f
}
