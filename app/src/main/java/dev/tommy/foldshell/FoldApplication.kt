package dev.tommy.foldshell

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FoldApplication : Application() {
    companion object { val MODES = listOf("v1", "v2", "v3", "v4") }
    val prefs by lazy { getSharedPreferences("fold", MODE_PRIVATE) }
    val main = Handler(Looper.getMainLooper())
    /** Status shown in the UI: a string resource plus an optional raw detail line from the engine. */
    @Volatile var messageRes = R.string.msg_setup_required; private set
    @Volatile var messageDetail: String? = null; private set
    @Volatile var pairingPort = 0; private set
    @Volatile private var connectPort = 0
    @Volatile private var setupUntil = 0L
    @Volatile private var stream: AdbStream? = null
    @Volatile private var ready = false
    private var adb: LocalAdb? = null
    private var lastReply = 0L
    @Volatile private var retryAt = 0L
    @Volatile private var engineError: String? = null
    private var failures = 0
    private val io = Executors.newSingleThreadScheduledExecutor()
    private lateinit var discovery: AdbDiscovery
    val enabled get() = prefs.getBoolean("enabled", false)
    val intensity get() = prefs.getInt("intensity", 100)
    /** "v1" live blur, "v2" snapshot + gradient, "v3" flat gradient blur. Migrates the older v2 switch. */
    val mode: String get() = prefs.getString("mode", null) ?: if (prefs.getBoolean("v2", true)) "v2" else "v1"
    val paired get() = prefs.getBoolean("paired", false)
    // Global settings are world-readable; the app only reads them and never writes.
    val developerOptionsEnabled get() = Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    val wirelessDebuggingEnabled get() = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1
    val setupActive get() = SystemClock.elapsedRealtime() < setupUntil
    /** Resources in the app-selected language, for notifications and other non-Activity text. */
    fun localized(): Context = Lang.wrap(this)
    fun messageText(context: Context): String =
        context.getString(messageRes) + (messageDetail?.let { "\n" + it } ?: "")
    private fun say(res: Int, detail: String? = null) { messageRes = res; messageDetail = detail }
    override fun onCreate() {
        super.onCreate()
        createChannels()
        discovery = AdbDiscovery(this) { pairing, port ->
            if (pairing) { pairingPort = port; if (setupActive) main.post { pairingNotification() } }
            else { connectPort = port; retryAt = 0 }
        }
        io.scheduleWithFixedDelay({ try { reconcile() } catch (_: Exception) { failure(R.string.msg_rechecking) } }, 1, 5, TimeUnit.SECONDS)
    }
    private fun createChannels() {
        val res = localized()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("fold", res.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(android.app.NotificationChannel("fold-pairing", res.getString(R.string.channel_pairing), NotificationManager.IMPORTANCE_HIGH))
    }
    /** Re-labels channels and any visible notifications after a language change. */
    fun refreshNotifications() {
        createChannels()
        if (setupActive) pairingNotification()
        if (enabled || setupActive) startForegroundService(Intent(this, KeepAliveService::class.java))
    }
    fun preparePairing() {
        setupUntil = SystemClock.elapsedRealtime() + 600000
        startForegroundService(Intent(this, KeepAliveService::class.java))
        discovery.start()
        say(R.string.msg_open_pair_dialog)
        pairingNotification()
    }
    private fun pairingNotification() {
        if (!setupActive) return
        val res = localized()
        val reply = PendingIntent.getBroadcast(this, 22, Intent(this, PairingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder("code").setLabel(res.getString(R.string.hint_code)).build()
        val open = PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "fold-pairing")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle(res.getString(R.string.notif_pair_title))
            .setContentText(res.getString(if (pairingPort > 0) R.string.notif_pair_text_ready else R.string.notif_pair_text_wait))
            .addAction(Notification.Action.Builder(null, res.getString(R.string.notif_pair_action), reply).addRemoteInput(input).build())
            .setStyle(Notification.BigTextStyle().bigText(res.getString(R.string.notif_pair_big)))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(600000).build()
        getSystemService(NotificationManager::class.java).notify(22, notification)
    }
    fun pair(port: Int, code: String) {
        if (port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            say(R.string.msg_check_port_code); return
        }
        say(R.string.msg_pairing)
        io.execute {
            try {
                val manager = adb ?: LocalAdb(this).also { adb = it }
                check(manager.pair("127.0.0.1", port, code)) { "Pairing rejected" }
                prefs.edit().putBoolean("paired", true).apply()
                setupUntil = 0
                getSystemService(NotificationManager::class.java).cancel(22)
                say(R.string.msg_paired)
                retryAt = 0
                if (enabled) reconcile()
            } catch (_: Exception) {
                say(R.string.msg_pair_failed)
            }
        }
    }
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        retryAt = 0; engineError = null
        if (!value) {
            setupUntil = 0
            getSystemService(NotificationManager::class.java).cancel(22)
        }
        if (value) {
            discovery.start()
            startForegroundService(Intent(this, KeepAliveService::class.java))
        }
        io.execute {
            if (enabled) reconcile() else {
                shutdownStream(); say(R.string.msg_off)
                if (!setupActive) main.post { discovery.stop(); stopService(Intent(this, KeepAliveService::class.java)) }
            }
        }
    }
    fun stopDiscoveryIfIdle() {
        if (!enabled && !setupActive) discovery.stop()
    }
    fun setMode(value: String) {
        require(value in MODES) { "Unknown mode" }
        if (value == mode) return
        prefs.edit().putString("mode", value).apply()
        engineError = null; retryAt = 0
        io.execute { shutdownStream(); if (enabled) reconcile() }
    }
    fun setIntensity(value: Int) {
        prefs.edit().putInt("intensity", value.coerceIn(50, 150)).apply()
        io.execute { if (ready) try { send("INTENSITY ${intensity / 100f}") } catch (_: Exception) { failure(R.string.msg_reconnect_wait) } }
    }
    fun restore() {
        if (enabled || setupActive) discovery.start()
        // Network and shell work never run on the UI thread.
        if (enabled) io.execute { try { reconcile() } catch (_: Exception) { failure(R.string.msg_reconnect_wait) } }
    }
    private fun reconcile() {
        if (!enabled) return
        engineError?.let { say(R.string.msg_engine_error, it); return }
        if (!paired) { if (!setupActive) say(R.string.msg_setup_first); return }
        val now = SystemClock.elapsedRealtime()
        if (stream != null) {
            if (now - lastReply > 25000) { failure(R.string.msg_engine_silent); return }
            try { send("STATUS") } catch (_: Exception) { failure(R.string.msg_disconnected) }
            return
        }
        if (now < retryAt) return
        if (connectPort == 0) {
            say(if (!wirelessDebuggingEnabled) R.string.msg_wireless_off else R.string.msg_wireless_wait)
            main.post { discovery.stop(); discovery.start() }; retryAt = now + 10000; return
        }
        say(R.string.msg_connecting)
        try {
            val manager = adb ?: LocalAdb(this).also { adb = it }
            manager.disconnect()
            if (!manager.connect("127.0.0.1", connectPort)) throw IllegalStateException("ADB unavailable")
            // Source path comes from PackageManager; it is never supplied by a user or mDNS.
            val apk = applicationInfo.sourceDir.replace("'", "'\\''")
            val command = "CLASSPATH='$apk' app_process /system/bin dev.tommy.foldshell.system.LocalFoldDaemon ${intensity / 100f} $mode"
            val current = manager.openStream("shell,raw:$command")
            stream = current; ready = false; lastReply = now
            Thread({
                try {
                    BufferedReader(InputStreamReader(current.openInputStream())).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            // Engine diagnostics (timings, angles, layer events; no screen content).
                            Log.i("FoldEngine", line)
                            if (line.startsWith("FOLD ")) io.execute {
                                if (stream === current) {
                                    lastReply = SystemClock.elapsedRealtime()
                                    when {
                                        line == "FOLD RUNNING" -> { ready = true; failures = 0; say(R.string.msg_running) }
                                        line.startsWith("FOLD ERROR") -> {
                                            engineError = line.removePrefix("FOLD ERROR").trim()
                                            say(R.string.msg_engine_error, engineError)
                                            shutdownStream()
                                        }
                                        line == "FOLD STOPPED" -> say(R.string.msg_off)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {} finally {
                    io.execute { if (stream === current) failure(R.string.msg_disconnected) }
                }
            }, "FoldEngineOutput").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            connectPort = 0
            failure(R.string.msg_wireless_wait_recheck)
            main.post { discovery.stop(); discovery.start() }
        }
    }
    private fun send(command: String) {
        val output = stream?.openOutputStream() ?: return
        output.write((command + "\n").toByteArray()); output.flush()
    }
    private fun shutdownStream() {
        try { send("STOP") } catch (_: Exception) {}
        val old = stream; stream = null; ready = false
        try { old?.close() } catch (_: Exception) {}
        try { adb?.disconnect() } catch (_: Exception) {}
    }
    private fun failure(res: Int) {
        shutdownStream()
        failures = (failures + 1).coerceAtMost(5)
        retryAt = SystemClock.elapsedRealtime() + (1000L shl failures).coerceAtMost(30000)
        if (enabled) say(res)
    }
}
