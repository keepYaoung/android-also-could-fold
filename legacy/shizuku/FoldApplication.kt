package dev.tommy.foldshell

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku

class FoldApplication : Application() {
    val prefs by lazy { getSharedPreferences("fold", MODE_PRIVATE) }
    val main = Handler(Looper.getMainLooper())
    var message = "Shizuku 연결 대기 중"
        private set
    private var remote: IFoldEngine? = null
    private var binding = false
    private var attempts = 0
    val enabled get() = prefs.getBoolean("enabled", false)
    val intensity get() = prefs.getInt("intensity", 100)
    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, FoldUserService::class.java))
            .daemon(true).processNameSuffix("fold_engine").tag("fold-engine")
            .version(BuildConfig.VERSION_CODE)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.post {
                binding = false
                remote = IFoldEngine.Stub.asInterface(binder)
                if (!enabled) { stopRemote(); return@post }
                applySettings()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            main.post { remote = null; binding = false; message = "연결 끊김 · 복구 대기" }
        }
    }
    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky {
            main.post { remote = null; binding = false; attempts = 0; restore() }
        }
        Shizuku.addBinderDeadListener {
            main.post { remote = null; binding = false; message = "Shizuku가 중지되었습니다" }
        }
        main.post(object : Runnable {
            override fun run() {
                if (enabled) restore()
                main.postDelayed(this, 15000)
            }
        })
    }
    fun granted(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        attempts = 0
        if (value) restore() else stopRemote()
    }
    fun setIntensity(value: Int) {
        prefs.edit().putInt("intensity", value.coerceIn(50, 150)).apply()
        if (enabled && remote != null) applySettings()
    }
    fun restore() {
        if (!enabled) { message = "꺼짐"; return }
        if (!granted()) { message = "Shizuku 실행 및 권한 허용이 필요합니다"; return }
        try {
            if (Shizuku.getUid() != 2000) { message = "Shizuku를 무선 디버깅 또는 ADB로 실행해주세요"; return }
            remote?.let {
                val state = it.status()
                message = if (state == "RUNNING") "실행 중 · 접힘/펼침 감지" else state
                // Firmware errors stay visible; avoid endless restart loops.
                if (state == "STOPPED" && attempts < 3) applySettings()
                return
            }
            if (!binding && attempts < 3) {
                attempts++; binding = true; message = "블러 엔진 연결 중"
                Shizuku.bindUserService(args, connection)
                main.postDelayed({ if (remote == null) { binding = false; message = "엔진 연결 지연 · 다시 시도 중" } }, 10000)
            } else if (attempts >= 3 && !binding) message = "복구 실패 · 껐다 켜서 다시 시도해주세요"
        } catch (error: Exception) { remote = null; binding = false; message = "연결 오류: ${error.message}" }
    }
    private fun applySettings() {
        try {
            val result = remote?.start(Shizuku.getBinder(), intensity / 100f) ?: return
            message = if (result == "RUNNING") "실행 중 · 접힘/펼침 감지" else result
            if (result == "RUNNING") attempts = 0 else attempts++
        } catch (error: Exception) { remote = null; message = "실행 오류: ${error.message}" }
    }
    private fun stopRemote() {
        try {
            remote?.stop()
            if (granted()) Shizuku.unbindUserService(args, connection, true)
            message = "꺼짐"
        } catch (error: Exception) { message = "중지 확인 필요: ${error.message}" }
        remote = null; binding = false
    }
}
