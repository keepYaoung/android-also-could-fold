package dev.tommy.foldshell

import android.app.*
import android.content.Intent
import android.os.IBinder

/** Visible lifecycle guardian. The compositor runs over a private local ADB stream. */
class KeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("fold", "접힘 효과 실행 상태", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, KeepAliveService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(10, Notification.Builder(this, "fold")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Fold Transition")
            .setContentText("접힘 효과 활성화 · 상태는 앱에서 확인")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "끄기", stop).build()).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as FoldApplication
        if (intent?.action == "stop") app.setEnabled(false)
        if (!app.enabled && !app.setupActive) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
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
