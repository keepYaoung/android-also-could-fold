package dev.tommy.foldshell

import android.app.*
import android.content.Intent
import android.os.IBinder

/** Visible lifecycle guardian. The compositor runs over a private local ADB stream. */
class KeepAliveService : Service() {
    private fun show() {
        val res = (application as FoldApplication).localized()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, KeepAliveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(10, Notification.Builder(this, "fold")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(res.getString(R.string.app_name))
            .setContentText(res.getString(R.string.notif_run_text))
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, res.getString(R.string.notif_run_stop), stop).build()).build())
    }
    override fun onCreate() { super.onCreate(); show() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as FoldApplication
        if (intent?.action == "stop") app.setEnabled(false)
        if (!app.enabled && !app.setupActive) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        show()
        app.restore()
        app.main.removeCallbacks(check); app.main.post(check)
        return START_STICKY
    }
    private val check = object : Runnable {
        override fun run() {
            val app = application as FoldApplication
            if (!app.enabled && !app.setupActive) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            app.main.postDelayed(this, 5000)
        }
    }
    override fun onDestroy() {
        val app = application as FoldApplication
        app.main.removeCallbacks(check)
        app.stopDiscoveryIfIdle()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
