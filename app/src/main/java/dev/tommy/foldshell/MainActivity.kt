package dev.tommy.foldshell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val app get() = application as FoldApplication
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val permission = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            if (result == PackageManager.PERMISSION_GRANTED) enable()
            else Toast.makeText(this, "Shizuku 권한을 허용해야 효과를 실행할 수 있어요", Toast.LENGTH_LONG).show()
        }
    }
    private val refresh = object : Runnable {
        override fun run() {
            status.text = app.message
            toggle.text = if (app.enabled) "효과 끄기" else "효과 켜기"
            app.main.postDelayed(this, 1000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Fold Transition"
        Shizuku.addRequestPermissionResultListener(permission)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(246, 246, 248))
        }
        fun label(value: String, size: Float = 16f): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(28, 30, 36))
            setPadding(0, pad / 2, 0, pad / 2); root.addView(this)
        }
        label("Fold Transition", 30f).typeface = Typeface.DEFAULT_BOLD
        label("One UI 그대로, 접고 펼치는 순간만 부드럽게.")
        status = label(app.message, 18f)
        toggle = Button(this).apply {
            text = if (app.enabled) "효과 끄기" else "효과 켜기"
            setOnClickListener {
                if (app.enabled) {
                    app.setEnabled(false)
                    stopService(Intent(this@MainActivity, KeepAliveService::class.java))
                } else if (!Shizuku.pingBinder()) {
                    Toast.makeText(this@MainActivity, "먼저 Shizuku를 실행해주세요", Toast.LENGTH_LONG).show()
                } else if (app.granted()) enable()
                else try { Shizuku.requestPermission(100) }
                catch (error: Exception) { Toast.makeText(this@MainActivity, error.message, Toast.LENGTH_LONG).show() }
            }
            root.addView(this)
        }
        val strength = label("블러 강도 · ${app.intensity}%")
        root.addView(SeekBar(this).apply {
            max = 100; progress = app.intensity - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) {
                    strength.text = "블러 강도 · ${progress + 50}%"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { app.setIntensity(bar.progress + 50) }
            })
        })
        label("잠금 화면에서도 동작합니다.\n1.5초 멈추면 블러가 자연스럽게 사라집니다.\n커버는 오른쪽, 내부 왼쪽 화면은 바깥쪽이 더 강합니다.")
        root.addView(Button(this).apply {
            text = "Shizuku 열기 / 설치 안내"
            setOnClickListener {
                val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
                startActivity(intent)
            }
        })
        label("재부팅 후에는 Shizuku가 실행되어야 자동 복구됩니다. Shizuku 13.6의 자동 시작은 신뢰하는 Wi-Fi 연결이 필요합니다.\n\n현재 지원 기기: Galaxy Z Fold7 (SM-F966N). 각도 사이의 변화는 움직임 신호로 추정합니다.", 14f)
        label("v${BuildConfig.VERSION_NAME} · MIT License", 13f)
        setContentView(ScrollView(this).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(root)
        })
    }
    private fun enable() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        app.setEnabled(true)
        startForegroundService(Intent(this, KeepAliveService::class.java))
    }
    override fun onResume() { super.onResume(); app.restore(); app.main.post(refresh) }
    override fun onPause() { app.main.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(permission); super.onDestroy() }
}
