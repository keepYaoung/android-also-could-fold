package dev.tommy.foldshell

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*

/** One requirement per page. Every page re-checks its state each second and after returning from Settings. */
class OnboardingActivity : Activity() {
    class Step(val key: String, val icon: String, val label: Int, val title: Int, val body: Int, val action: Int,
               val doneText: Int, val todoText: Int, val done: () -> Boolean)
    companion object {
        const val EXTRA_STEP = "step"
        fun notificationsGranted(context: Context) = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        fun steps(app: FoldApplication) = listOf(
            Step("notifications", "🔔", R.string.step_notifications, R.string.ob_notif_title, R.string.ob_notif_body,
                R.string.ob_notif_action, R.string.ob_notif_done, R.string.ob_notif_todo) { notificationsGranted(app) },
            Step("developer", "🛠️", R.string.step_developer, R.string.ob_dev_title, R.string.ob_dev_body,
                R.string.ob_dev_action, R.string.ob_dev_done, R.string.ob_dev_todo) { app.developerOptionsEnabled },
            Step("wireless", "📶", R.string.step_wireless, R.string.ob_wifi_title, R.string.ob_wifi_body,
                R.string.ob_wifi_action, R.string.ob_wifi_done, R.string.ob_wifi_todo) { app.wirelessDebuggingEnabled },
            Step("pairing", "🔗", R.string.step_pairing, R.string.ob_pair_title, R.string.ob_pair_body,
                R.string.ob_pair_action, R.string.ob_pair_done, R.string.ob_pair_todo) { app.paired },
        )
        fun firstIncomplete(app: FoldApplication): Int {
            val all = steps(app)
            val index = all.indexOfFirst { !it.done() }
            return if (index < 0) all.size - 1 else index
        }
        fun open(activity: Activity, intent: Intent) {
            try { activity.startActivity(intent) }
            catch (_: Exception) { Toast.makeText(activity, R.string.toast_settings_fail, Toast.LENGTH_LONG).show() }
        }
        /** No public wireless-debugging page exists (checked on SM-F966N and API 34). Developer
         *  options accept a preference key to scroll to and highlight; verified on One UI 8. */
        fun openWirelessDebugging(activity: Activity) {
            Toast.makeText(activity, R.string.toast_wireless, Toast.LENGTH_LONG).show()
            open(activity, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"))
        }
        fun manualPairing(activity: Activity, app: FoldApplication) {
            val density = activity.resources.displayMetrics.density
            val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0) }
            val port = EditText(activity).apply { setHint(R.string.hint_port); inputType = InputType.TYPE_CLASS_NUMBER; if (app.pairingPort > 0) setText(app.pairingPort.toString()) }
            val code = EditText(activity).apply { setHint(R.string.hint_code); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
            box.addView(port); box.addView(code)
            AlertDialog.Builder(activity).setTitle(R.string.manual_pairing_title).setView(box)
                .setPositiveButton(R.string.connect) { _, _ ->
                    app.preparePairing(); app.pair(port.text.toString().toIntOrNull() ?: 0, code.text.toString().trim())
                    code.text.clear()
                }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    private val app get() = application as FoldApplication
    private val steps by lazy { steps(app) }
    private var index = 0
    private var fromMain = false
    private lateinit var dots: LinearLayout
    private lateinit var icon: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var status: TextView
    private lateinit var action: Button
    private lateinit var alt: Button
    private lateinit var next: Button
    private lateinit var skip: Button
    private val poll = object : Runnable { override fun run() { render(); app.main.postDelayed(this, 1000) } }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Lang.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom); insets
        }
        dots = findViewById(R.id.dots); icon = findViewById(R.id.icon); title = findViewById(R.id.title)
        body = findViewById(R.id.body); status = findViewById(R.id.status)
        action = findViewById(R.id.action); alt = findViewById(R.id.alt); next = findViewById(R.id.next); skip = findViewById(R.id.skip)
        findViewById<Button>(R.id.lang).apply { text = Lang.label(this@OnboardingActivity); setOnClickListener { Lang.pick(this@OnboardingActivity) } }
        val density = resources.displayMetrics.density
        for (i in steps.indices) dots.addView(View(this), LinearLayout.LayoutParams(0, (4 * density).toInt(), 1f).apply {
            marginEnd = if (i == steps.size - 1) 0 else (6 * density).toInt()
        })
        fromMain = intent.hasExtra(EXTRA_STEP)
        index = savedInstanceState?.getInt(EXTRA_STEP)
            ?: intent.getIntExtra(EXTRA_STEP, -1).let { if (it in steps.indices) it else if (fromMain) firstIncomplete(app) else 0 }
        action.setOnClickListener { runAction() }
        alt.setOnClickListener { manualPairing(this, app) }
        next.setOnClickListener { if (index >= steps.size - 1) finishFlow() else { index++; render() } }
        skip.setOnClickListener { finishFlow() }
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putInt(EXTRA_STEP, index) }
    override fun onResume() { super.onResume(); app.main.post(poll) }
    override fun onPause() { app.main.removeCallbacks(poll); super.onPause() }
    override fun onBackPressed() { if (index > 0 && !fromMain) { index--; render() } else super.onBackPressed() }

    private fun render() {
        val step = steps[index]; val done = step.done()
        for (i in 0 until dots.childCount) dots.getChildAt(i).setBackgroundResource(if (i <= index) R.drawable.bg_dot_active else R.drawable.bg_dot)
        icon.text = step.icon; setTitle(step.title); title.setText(step.title); body.setText(step.body)
        status.text = (if (done) "✅  " else "⏳  ") + getString(if (done) step.doneText else step.todoText)
        action.setText(step.action); action.visibility = if (done) View.GONE else View.VISIBLE
        next.visibility = if (done) View.VISIBLE else View.GONE
        next.setText(if (index >= steps.size - 1) R.string.onboarding_start else R.string.onboarding_next)
        alt.setText(R.string.manual_pairing)
        alt.visibility = if (step.key == "pairing" && !done) View.VISIBLE else View.GONE
        skip.visibility = if (done || index == 0) View.GONE else View.VISIBLE
    }
    private fun runAction() {
        when (steps[index].key) {
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            "developer" -> open(this, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
            "wireless" -> openWirelessDebugging(this)
            "pairing" -> { app.preparePairing(); openWirelessDebugging(this) }
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results); render()
    }
    private fun finishFlow() {
        app.prefs.edit().putBoolean("onboarded", true).apply()
        if (!fromMain) startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
