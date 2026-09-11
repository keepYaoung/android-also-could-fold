package dev.tommy.foldshell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private val app get() = application as FoldApplication
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private var afterPermission: (() -> Unit)? = null
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
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(246, 246, 248))
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.rgb(28, 30, 36))
            setPadding(0, pad / 2, 0, pad / 2); root.addView(this)
        }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value; setOnClickListener { action() }; root.addView(this)
        }
        label("Fold Transition", 30f).typeface = Typeface.DEFAULT_BOLD
        label("One UI 그대로, 접고 펼치는 순간만 부드럽게.")
        status = label(app.message, 18f)
        toggle = button(if (app.enabled) "효과 끄기" else "효과 켜기") {
            if (app.enabled) app.setEnabled(false)
            else if (!app.paired) beginSetup()
            else withNotifications { app.setEnabled(true) }
        }
        val mode = Switch(this).apply {
            text = "V2 · 캡처 + 블랙 그라디언트"
            isChecked = app.v2
            setOnCheckedChangeListener { _, checked -> app.setV2(checked) }
        }
        root.addView(mode)
        label("V2: 커버는 왼쪽 변, 내부 왼쪽 화면은 가운데 접힘선을 고정하고 바깥쪽을 후퇴시킵니다. 자이로 회전량에 따라 움직이며 커버 오른쪽·내부 왼쪽 바깥쪽에 그라디언트 블러를 더합니다. 1.5초 정지 시 함께 해제합니다. 잠금 화면도 보호 영역을 제외한 메모리 캡처를 시도하고, 차단되면 검은 원근 마스크를 표시합니다. 캡처는 저장·전송하지 않습니다. 끄면 V1 블러로 돌아갑니다.", 14f)
        label("캡처는 기기 메모리에만 잠시 유지하며 저장·전송하지 않습니다. 잠금 화면은 캡처 없이 검은 그라디언트만 표시합니다. V2는 기기 검증 전인 실험 기능입니다.", 14f)
        val strength = label("효과 강도 · ${app.intensity}%")
        root.addView(SeekBar(this).apply {
            max = 100; progress = app.intensity - 50
            contentDescription = "효과 강도"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) { strength.text = "효과 강도 · ${progress + 50}%" }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { app.setIntensity(bar.progress + 50) }
            })
        })
        label("잠금 화면에서도 동작합니다.\n1.5초 멈추면 효과가 자연스럽게 사라집니다.\n커버는 오른쪽, 내부 왼쪽 화면은 바깥쪽이 더 강합니다.")
        button("최초 연결 설정") { beginSetup() }
        button("포트와 코드 직접 입력") { manualPairing() }
        label("별도 Shizuku 앱이 필요 없습니다.\n\n① Wi-Fi와 개발자 옵션의 무선 디버깅을 켜세요.\n② ‘페어링 코드로 기기 페어링’을 여세요.\n③ 코드 창을 닫지 말고 알림창의 Fold Transition에 코드를 입력하세요.\n\n최초 승인은 필요합니다. 이후 같은 키를 재사용하며 연결을 자동으로 다시 찾습니다. USB는 분리한 상태에서 실행해주세요.", 14f)
        label("연결 시작·복구에는 Wi-Fi와 무선 디버깅이 필요합니다. 재부팅 후 자동 복구는 기기 설정에 따라 달라집니다. 기존 Shizuku 효과는 먼저 꺼주세요.\n지원 기기: Galaxy Z Fold7 (SM-F966N).", 14f)
        button("오픈소스 라이선스") {
            val names = assets.list("licenses").orEmpty().sorted()
            AlertDialog.Builder(this).setTitle("오픈소스 라이선스")
                .setItems(names.toTypedArray()) { _, index ->
                    val notice = assets.open("licenses/${names[index]}").bufferedReader().use { it.readText() }
                    AlertDialog.Builder(this).setTitle(names[index]).setMessage(notice)
                        .setPositiveButton("닫기", null).show()
                }.setNegativeButton("닫기", null).show()
        }
        label("v${BuildConfig.VERSION_NAME} · MIT", 13f)
        setContentView(ScrollView(this).apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }; addView(root)
        })
    }
    private fun beginSetup() = withNotifications {
        app.preparePairing()
        AlertDialog.Builder(this).setTitle("한 번만 연결해주세요")
            .setMessage("다음 설정에서 ‘무선 디버깅 → 페어링 코드로 기기 페어링’을 여세요. 코드 창을 유지한 채 알림창을 내려 Fold Transition 알림에 코드를 입력하세요. 알림이 없으면 분할 화면에서 이 앱의 직접 입력을 사용할 수 있습니다.")
            .setPositiveButton("개발자 옵션 열기") { _, _ -> startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            .setNegativeButton("닫기", null).show()
    }
    private fun manualPairing() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 20, 48, 0) }
        val port = EditText(this).apply { hint = "페어링 포트"; inputType = InputType.TYPE_CLASS_NUMBER; if (app.pairingPort > 0) setText(app.pairingPort.toString()) }
        val code = EditText(this).apply { hint = "6자리 페어링 코드"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        box.addView(port); box.addView(code)
        AlertDialog.Builder(this).setTitle("페어링 코드 창을 유지해주세요").setView(box)
            .setPositiveButton("연결") { _, _ -> withNotifications {
                app.preparePairing(); app.pair(port.text.toString().toIntOrNull() ?: 0, code.text.toString().trim())
                code.text.clear()
            } }.setNegativeButton("취소", null).show()
    }
    private fun withNotifications(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = action; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        } else action()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 101) { afterPermission?.invoke(); afterPermission = null }
    }
    override fun onResume() { super.onResume(); app.restore(); app.main.post(refresh) }
    override fun onPause() { app.main.removeCallbacks(refresh); super.onPause() }
}
