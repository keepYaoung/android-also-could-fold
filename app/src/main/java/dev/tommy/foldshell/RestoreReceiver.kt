package dev.tommy.foldshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as FoldApplication
        if (app.enabled) {
            app.restore()
            // Background FGS policy may require reopening the app after boot.
            try { context.startForegroundService(Intent(context, KeepAliveService::class.java)) }
            catch (_: IllegalStateException) { }
        }
    }
}
