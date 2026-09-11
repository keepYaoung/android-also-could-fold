package dev.tommy.foldshell

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object LiveFrameBus {
    interface Listener { fun onFrame(bitmap: Bitmap) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var latest: Bitmap? = null

    @Synchronized
    fun publish(bitmap: Bitmap) {
        val previous = latest
        latest = bitmap
        listeners.forEach { it.onFrame(bitmap) }

        // ImageView/HWUI may still reference the previous frame after setImageBitmap().
        // Recycle only after a generous GPU grace period and only if it did not
        // become current again.
        if (previous != null && previous !== bitmap) {
            handler.postDelayed({
                synchronized(this) {
                    if (previous !== latest && !previous.isRecycled) previous.recycle()
                }
            }, 1800L)
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        latest?.takeIf { !it.isRecycled }?.let(listener::onFrame)
    }

    fun removeListener(listener: Listener) { listeners -= listener }
    fun latest(): Bitmap? = latest?.takeIf { !it.isRecycled }
}
