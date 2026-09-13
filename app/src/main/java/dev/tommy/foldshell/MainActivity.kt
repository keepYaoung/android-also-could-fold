package dev.tommy.foldshell

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {
    private val app get() = application as FoldApplication
    private lateinit var status: TextView
    private lateinit var statusLabel: TextView
    private lateinit var toggle: Switch
    private lateinit var saveMode: Button
    private var pendingMode = ""
    private lateinit var modeDescription: TextView
    private lateinit var intensityValue: TextView
    private val modeRows = linkedMapOf<String, View>()
    private val stepRows = mutableListOf<View>()
    private val refresh = object : Runnable { override fun run() { render(); app.main.postDelayed(this, 1000) } }
    private val modes = mapOf(
        "v1" to Triple(R.string.mode_v1, R.string.mode_v1_sub, R.string.mode_v1_desc),
        "v2" to Triple(R.string.mode_v2, R.string.mode_v2_sub, R.string.mode_v2_desc),
        "v3" to Triple(R.string.mode_v3, R.string.mode_v3_sub, R.string.mode_v3_desc),
        "v4" to Triple(R.string.mode_v4, R.string.mode_v4_sub, R.string.mode_v4_desc))

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Lang.wrap(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.prefs.getBoolean("onboarded", false)) {
            startActivity(Intent(this, OnboardingActivity::class.java)); finish(); return
        }
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.scroll).setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        status = findViewById(R.id.status); statusLabel = findViewById(R.id.status_label)
        toggle = findViewById(R.id.toggle); saveMode = findViewById(R.id.save_mode); modeDescription = findViewById(R.id.mode_description)
        pendingMode = app.mode
        intensityValue = findViewById(R.id.intensity_value)
        findViewById<Button>(R.id.lang).apply { text = Lang.label(this@MainActivity); setOnClickListener { Lang.pick(this@MainActivity) } }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked == app.enabled) return@setOnCheckedChangeListener
            if (checked && !app.paired) { toggle.isChecked = false; openSetup(4) }
            else app.setEnabled(checked)
            render()
        }
        saveMode.setOnClickListener { app.setMode(pendingMode); render() }
        modeRows["v1"] = findViewById(R.id.row_v1); modeRows["v2"] = findViewById(R.id.row_v2)
        modeRows["v3"] = findViewById(R.id.row_v3); modeRows["v4"] = findViewById(R.id.row_v4)
        stepRows += listOf<View>(findViewById(R.id.row_notifications), findViewById(R.id.row_developer),
            findViewById(R.id.row_wireless), findViewById(R.id.row_pairing))
        val seek = findViewById<SeekBar>(R.id.intensity)
        seek.progress = app.intensity - 50
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, user: Boolean) { intensityValue.text = "${progress + 50}%" }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) { app.setIntensity(bar.progress + 50) }
        })
        findViewById<View>(R.id.manual_pairing).setOnClickListener { OnboardingActivity.manualPairing(this, app) }
        findViewById<View>(R.id.row_setup_again).bindRow("🧭", getString(R.string.setup_again), getString(R.string.setup_again_sub),
            getString(R.string.view), getColor(R.color.text_tertiary)) { openSetup(0) }
        findViewById<View>(R.id.row_licenses).bindRow("📄", getString(R.string.licenses), getString(R.string.licenses_sub),
            getString(R.string.view), getColor(R.color.text_tertiary)) { showLicenses() }
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, BuildConfig.VERSION_NAME)
        render()
    }
    private fun openSetup(step: Int) {
        startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_STEP, step))
    }
    private fun render() {
        status.text = app.messageText(this)
        statusLabel.setText(if (app.enabled) R.string.status_on else R.string.status_off)
        if (toggle.isChecked != app.enabled) toggle.isChecked = app.enabled
        for ((key, row) in modeRows) {
            val (title, sub, _) = modes.getValue(key)
            val selected = pendingMode == key
            row.bindRow(key.removePrefix("v"), getString(title), getString(sub), if (selected) "✓" else "",
                getColor(R.color.primary)) { if (pendingMode != key) { pendingMode = key; render() } }
        }
        modeDescription.setText(modes.getValue(pendingMode).third)
        val dirty = pendingMode != app.mode
        saveMode.isEnabled = dirty
        saveMode.setText(if (dirty) R.string.save_mode else R.string.saved_mode)
        intensityValue.text = "${app.intensity}%"
        val steps = OnboardingActivity.setupSteps(app)
        for (i in steps.indices) {
            val done = steps[i].done()
            stepRows[i].bindRow(steps[i].icon, getString(steps[i].label), getString(if (done) steps[i].doneText else steps[i].todoText),
                getString(if (done) R.string.done else R.string.set_up), getColor(if (done) R.color.success else R.color.primary)) { openSetup(i + 1) }
        }
    }
    private fun showLicenses() {
        val names = assets.list("licenses").orEmpty().sorted()
        AlertDialog.Builder(this).setTitle(R.string.licenses)
            .setItems(names.toTypedArray()) { _, index ->
                val notice = assets.open("licenses/${names[index]}").bufferedReader().use { it.readText() }
                AlertDialog.Builder(this).setTitle(names[index]).setMessage(notice).setPositiveButton(R.string.close, null).show()
            }.setNegativeButton(R.string.close, null).show()
    }
    override fun onResume() { super.onResume(); app.restore(); app.main.post(refresh) }
    override fun onPause() { app.main.removeCallbacks(refresh); super.onPause() }
}
