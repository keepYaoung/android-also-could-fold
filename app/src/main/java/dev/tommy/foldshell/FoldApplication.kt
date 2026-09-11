package dev.tommy.foldshell

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FoldApplication : Application() {
    val prefs by lazy { getSharedPreferences("fold", MODE_PRIVATE) }
    val main = Handler(Looper.getMainLooper())
    @Volatile var message = "최초 연결 설정이 필요합니다"; private set
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
    val v2 get() = prefs.getBoolean("v2", true)
    val paired get() = prefs.getBoolean("paired", false)
    val setupActive get() = SystemClock.elapsedRealtime() < setupUntil
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("fold", "접힘 효과 실행 상태", NotificationManager.IMPORTANCE_LOW))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            android.app.NotificationChannel("fold-pairing", "최초 연결 · 코드 입력", NotificationManager.IMPORTANCE_HIGH))
        discovery = AdbDiscovery(this) { pairing, port ->
            if (pairing) { pairingPort = port; if (setupActive) main.post { pairingNotification() } }
            else { connectPort = port; retryAt = 0 }
        }
        io.scheduleWithFixedDelay({ try { reconcile() } catch (_: Exception) { failure("연결을 다시 확인하고 있습니다") } }, 1, 5, TimeUnit.SECONDS)
    }
    fun preparePairing() {
        setupUntil = SystemClock.elapsedRealtime() + 600000
        startForegroundService(Intent(this, KeepAliveService::class.java))
        discovery.start()
        message = "설정에서 ‘페어링 코드로 기기 페어링’을 여세요"
        pairingNotification()
    }
    private fun pairingNotification() {
        if (!setupActive) return
        val reply = PendingIntent.getBroadcast(this, 22, Intent(this, PairingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val input = RemoteInput.Builder("code").setLabel("6자리 페어링 코드").build()
        val open = PendingIntent.getActivity(this, 22, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "fold-pairing")
            .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Fold Transition 최초 연결")
            .setContentText(if (pairingPort > 0) "코드 창을 닫지 말고 아래에 6자리 코드를 입력하세요" else "설정에서 페어링 코드 창을 열어주세요")
            .addAction(Notification.Action.Builder(null, "코드 입력", reply).addRemoteInput(input).build())
            .setStyle(Notification.BigTextStyle().bigText("설정의 페어링 코드 창을 유지한 채 이 알림을 펼쳐 ‘코드 입력’을 누르세요."))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(600000).build()
        getSystemService(NotificationManager::class.java).notify(22, notification)
    }
    fun pair(port: Int, code: String) {
        if (port !in 1..65535 || !code.matches(Regex("[0-9]{6}"))) {
            message = "페어링 포트와 6자리 코드를 확인해주세요"; return
        }
        message = "페어링 중"
        io.execute {
            try {
                val manager = adb ?: LocalAdb(this).also { adb = it }
                check(manager.pair("127.0.0.1", port, code)) { "Pairing rejected" }
                prefs.edit().putBoolean("paired", true).apply()
                setupUntil = 0
                getSystemService(NotificationManager::class.java).cancel(22)
                message = "최초 연결 완료 · 효과를 켜주세요"
                retryAt = 0
                if (enabled) reconcile()
            } catch (_: Exception) {
                message = "페어링 실패 · 새 코드 창의 포트와 코드를 확인해주세요"
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
                shutdownStream(); message = "꺼짐"
                if (!setupActive) main.post { discovery.stop(); stopService(Intent(this, KeepAliveService::class.java)) }
            }
        }
    }
    fun stopDiscoveryIfIdle() {
        if (!enabled && !setupActive) discovery.stop()
    }
    fun setV2(value: Boolean) {
        prefs.edit().putBoolean("v2", value).apply()
        engineError = null; retryAt = 0
        io.execute { shutdownStream(); if (enabled) reconcile() }
    }
    fun setIntensity(value: Int) {
        prefs.edit().putInt("intensity", value.coerceIn(50, 150)).apply()
        io.execute { if (ready) try { send("INTENSITY ${intensity / 100f}") } catch (_: Exception) { failure("재연결 대기") } }
    }
    fun restore() {
        if (enabled || setupActive) discovery.start()
        // Network and shell work never run on the UI thread.
        if (enabled) io.execute { try { reconcile() } catch (_: Exception) { failure("재연결 대기") } }
    }
    private fun reconcile() {
        if (!enabled) return
        engineError?.let { message = it; return }
        if (!paired) { if (!setupActive) message = "최초 연결 설정을 먼저 완료해주세요"; return }
        val now = SystemClock.elapsedRealtime()
        if (stream != null) {
            if (now - lastReply > 25000) { failure("엔진 응답 없음 · 재연결 대기"); return }
            try { send("STATUS") } catch (_: Exception) { failure("연결 끊김 · 자동 재연결 대기") }
            return
        }
        if (now < retryAt) return
        if (connectPort == 0) {
            message = "무선 디버깅 연결 대기 · Wi-Fi와 무선 디버깅을 켜주세요"
            main.post { discovery.stop(); discovery.start() }; retryAt = now + 10000; return
        }
        message = "블러 엔진 연결 중"
        try {
            val manager = adb ?: LocalAdb(this).also { adb = it }
            manager.disconnect()
            if (!manager.connect("127.0.0.1", connectPort)) throw IllegalStateException("ADB unavailable")
            // Source path comes from PackageManager; it is never supplied by a user or mDNS.
            val apk = applicationInfo.sourceDir.replace("'", "'\\''")
            val command = "CLASSPATH='$apk' app_process /system/bin dev.tommy.foldshell.system.LocalFoldDaemon ${intensity / 100f} ${if (v2) "v2" else "v1"}"
            val current = manager.openStream("shell,raw:$command")
            stream = current; ready = false; lastReply = now
            Thread({
                try {
                    BufferedReader(InputStreamReader(current.openInputStream())).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.startsWith("FOLD ")) io.execute {
                                if (stream === current) {
                                    lastReply = SystemClock.elapsedRealtime()
                                    when {
                                        line == "FOLD RUNNING" -> { ready = true; failures = 0; message = "실행 중 · 앱 자체 연결" }
                                        line.startsWith("FOLD ERROR") -> {
                                            engineError = line.removePrefix("FOLD ") + " · 끈 뒤 다시 켜서 재시도"
                                            message = engineError!!
                                            shutdownStream()
                                        }
                                        line == "FOLD STOPPED" -> message = "꺼짐"
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {} finally {
                    io.execute { if (stream === current) failure("연결 끊김 · 자동 재연결 대기") }
                }
            }, "FoldEngineOutput").apply { isDaemon = true; start() }
        } catch (_: Exception) {
            connectPort = 0
            failure("무선 디버깅 연결 대기 · 필요하면 최초 연결을 다시 확인해주세요")
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
    private fun failure(text: String) {
        shutdownStream()
        failures = (failures + 1).coerceAtMost(5)
        retryAt = SystemClock.elapsedRealtime() + (1000L shl failures).coerceAtMost(30000)
        if (enabled) message = text
    }
}
