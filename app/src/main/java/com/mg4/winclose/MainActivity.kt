package com.mg4.winclose

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences(LocaleHelper.PREF_NAME, Context.MODE_PRIVATE)

        // ── Auto-close toggle ────────────────────────────────────────────────────
        val switchAuto = findViewById<Switch>(R.id.switch_auto)
        switchAuto.isChecked = prefs.getBoolean(WindowService.PREF_AUTO_CLOSE, false)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WindowService.PREF_AUTO_CLOSE, checked).apply()
        }

        // ── Manual close ─────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_close_hold).setOnClickListener {
            Thread { WindowHardware.closeAllWindowsPulsed(5000L) }.start()
        }

        // ── Info dialog ──────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_info).setOnClickListener { showInfoDialog() }

        // ── Log dialog ───────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_view_log).setOnClickListener { showLogDialog() }

        // ── Sliders ──────────────────────────────────────────────────────────────
        val tvSpeed = findViewById<TextView>(R.id.tv_speed_val)
        val tvTime  = findViewById<TextView>(R.id.tv_time_val)
        val tvDelay = findViewById<TextView>(R.id.tv_delay_val)
        val sbSpeed = findViewById<SeekBar>(R.id.sb_speed)
        val sbTime  = findViewById<SeekBar>(R.id.sb_time)
        val sbDelay = findViewById<SeekBar>(R.id.sb_delay)

        sbSpeed.min = Settings.MIN_SPEED_KMH; sbSpeed.max = Settings.MAX_SPEED_KMH
        sbTime.min  = Settings.MIN_TIME_MIN;  sbTime.max  = Settings.MAX_TIME_MIN
        sbDelay.min = Settings.MIN_DELAY_SEC; sbDelay.max = Settings.MAX_DELAY_SEC

        fun applySliderValues(speed: Int, time: Int, delay: Int) {
            sbSpeed.progress = speed; tvSpeed.text = getString(R.string.fmt_kmh, speed)
            sbTime.progress  = time;  tvTime.text  = getString(R.string.fmt_min, time)
            sbDelay.progress = delay; tvDelay.text = getString(R.string.fmt_sec, delay)
        }
        applySliderValues(Settings.getSpeedKmh(this), Settings.getTimeMin(this), Settings.getDelaySec(this))

        sbSpeed.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            tvSpeed.text = getString(R.string.fmt_kmh, p)
            if (fromUser) Settings.setSpeedKmh(this, p)
        })
        sbTime.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            tvTime.text = getString(R.string.fmt_min, p)
            if (fromUser) Settings.setTimeMin(this, p)
        })
        sbDelay.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            tvDelay.text = getString(R.string.fmt_sec, p)
            if (fromUser) Settings.setDelaySec(this, p)
        })

        // ── Delay trigger buttons ─────────────────────────────────────────────────
        val btnTrigOpen  = findViewById<Button>(R.id.btn_trigger_open)
        val btnTrigClose = findViewById<Button>(R.id.btn_trigger_close)

        fun paintTriggerButtons(t: DelayTrigger) {
            val selBg = 0xFFE94560.toInt(); val selFg = 0xFFFFFFFF.toInt()
            val unsBg = 0xFF333355.toInt(); val unsFg = 0xFFAAAAAA.toInt()
            btnTrigOpen.backgroundTintList  = ColorStateList.valueOf(if (t == DelayTrigger.DOOR_OPEN)  selBg else unsBg)
            btnTrigOpen.setTextColor(if (t == DelayTrigger.DOOR_OPEN)  selFg else unsFg)
            btnTrigClose.backgroundTintList = ColorStateList.valueOf(if (t == DelayTrigger.DOOR_CLOSE) selBg else unsBg)
            btnTrigClose.setTextColor(if (t == DelayTrigger.DOOR_CLOSE) selFg else unsFg)
        }
        paintTriggerButtons(Settings.getDelayTrigger(this))
        btnTrigOpen.setOnClickListener  { Settings.setDelayTrigger(this, DelayTrigger.DOOR_OPEN);  paintTriggerButtons(DelayTrigger.DOOR_OPEN) }
        btnTrigClose.setOnClickListener { Settings.setDelayTrigger(this, DelayTrigger.DOOR_CLOSE); paintTriggerButtons(DelayTrigger.DOOR_CLOSE) }

        // ── Beep settings ────────────────────────────────────────────────────────
        val switchBeep    = findViewById<Switch>(R.id.switch_beep)
        val layoutBeepVol = findViewById<LinearLayout>(R.id.layout_beep_volume)
        val tvBeepVol     = findViewById<TextView>(R.id.tv_beep_vol_val)
        val sbBeepVol     = findViewById<SeekBar>(R.id.sb_beep_volume)

        sbBeepVol.min = Settings.MIN_BEEP_VOL; sbBeepVol.max = Settings.MAX_BEEP_VOL

        fun applyBeepVolume(v: Int) { sbBeepVol.progress = v; tvBeepVol.text = "$v%" }
        fun applyBeepEnabled(enabled: Boolean) {
            switchBeep.isChecked = enabled
            layoutBeepVol.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        applyBeepEnabled(Settings.isBeepEnabled(this))
        applyBeepVolume(Settings.getBeepVolume(this))

        switchBeep.setOnCheckedChangeListener { _, checked ->
            Settings.setBeepEnabled(this, checked)
            applyBeepEnabled(checked)
        }
        sbBeepVol.setOnSeekBarChangeListener(simpleSeekListener { p, fromUser ->
            tvBeepVol.text = "$p%"
            if (fromUser) Settings.setBeepVolume(this, p)
        })

        // ── Language buttons ─────────────────────────────────────────────────────
        val btnFr = findViewById<Button>(R.id.btn_lang_fr)
        val btnEn = findViewById<Button>(R.id.btn_lang_en)

        fun paintLangButtons(lang: String) {
            val selBg = 0xFFE94560.toInt(); val selFg = 0xFFFFFFFF.toInt()
            val unsBg = 0xFF333355.toInt(); val unsFg = 0xFFAAAAAA.toInt()
            val fr = lang == "fr"
            btnFr.backgroundTintList = ColorStateList.valueOf(if (fr)  selBg else unsBg)
            btnFr.setTextColor(if (fr)  selFg else unsFg)
            btnEn.backgroundTintList = ColorStateList.valueOf(if (!fr) selBg else unsBg)
            btnEn.setTextColor(if (!fr) selFg else unsFg)
        }
        paintLangButtons(LocaleHelper.getLang(this))
        btnFr.setOnClickListener { if (LocaleHelper.getLang(this) != "fr") { LocaleHelper.setLang(this, "fr"); recreate() } }
        btnEn.setOnClickListener { if (LocaleHelper.getLang(this) != "en") { LocaleHelper.setLang(this, "en"); recreate() } }

        // ── Per-window buttons ────────────────────────────────────────────────────
        data class WinButtons(val area: Int, val auto: Button, val pulsed: Button, val off: Button)
        val winButtons = listOf(
            WinButtons(Settings.AREA_FL, findViewById(R.id.btn_fl_auto), findViewById(R.id.btn_fl_pulsed), findViewById(R.id.btn_fl_off)),
            WinButtons(Settings.AREA_FR, findViewById(R.id.btn_fr_auto), findViewById(R.id.btn_fr_pulsed), findViewById(R.id.btn_fr_off)),
            WinButtons(Settings.AREA_RL, findViewById(R.id.btn_rl_auto), findViewById(R.id.btn_rl_pulsed), findViewById(R.id.btn_rl_off)),
            WinButtons(Settings.AREA_RR, findViewById(R.id.btn_rr_auto), findViewById(R.id.btn_rr_pulsed), findViewById(R.id.btn_rr_off))
        )

        fun paintWindow(wb: WinButtons, mode: WindowMode) {
            val unsBg = 0xFF333355.toInt(); val unsFg = 0xFFAAAAAA.toInt()
            wb.auto.backgroundTintList   = ColorStateList.valueOf(if (mode == WindowMode.AUTO)   0xFF2E8B57.toInt() else unsBg)
            wb.auto.setTextColor(if (mode == WindowMode.AUTO)   0xFFFFFFFF.toInt() else unsFg)
            wb.pulsed.backgroundTintList = ColorStateList.valueOf(if (mode == WindowMode.PULSED) 0xFFE94560.toInt() else unsBg)
            wb.pulsed.setTextColor(if (mode == WindowMode.PULSED) 0xFFFFFFFF.toInt() else unsFg)
            wb.off.backgroundTintList    = ColorStateList.valueOf(if (mode == WindowMode.OFF)    0xFF666666.toInt() else unsBg)
            wb.off.setTextColor(if (mode == WindowMode.OFF)    0xFFFFFFFF.toInt() else unsFg)
        }

        winButtons.forEach { wb ->
            paintWindow(wb, Settings.getWindowMode(this, wb.area))
            wb.auto.setOnClickListener   { Settings.setWindowMode(this, wb.area, WindowMode.AUTO);   paintWindow(wb, WindowMode.AUTO) }
            wb.pulsed.setOnClickListener { Settings.setWindowMode(this, wb.area, WindowMode.PULSED); paintWindow(wb, WindowMode.PULSED) }
            wb.off.setOnClickListener    { Settings.setWindowMode(this, wb.area, WindowMode.OFF);    paintWindow(wb, WindowMode.OFF) }
        }

        // ── Reset ─────────────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_settings_reset).setOnClickListener {
            Settings.resetDefaults(this)
            applySliderValues(Settings.DEFAULT_SPEED_KMH, Settings.DEFAULT_TIME_MIN, Settings.DEFAULT_DELAY_SEC)
            paintTriggerButtons(Settings.DEFAULT_DELAY_TRIGGER)
            applyBeepEnabled(Settings.DEFAULT_BEEP_ENABLED)
            applyBeepVolume(Settings.DEFAULT_BEEP_VOLUME)
            winButtons.forEach { paintWindow(it, Settings.DEFAULT_WINDOW_MODE) }
        }

        // ── Start service ─────────────────────────────────────────────────────────
        WindowService.start(this)
    }

    // ── Log dialog ────────────────────────────────────────────────────────────────
    private var logDialog: AlertDialog? = null

    private fun showLogDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_log, null)
        val tvLog    = view.findViewById<TextView>(R.id.tv_log)
        val svLog    = view.findViewById<ScrollView>(R.id.sv_log)
        val btnClear = view.findViewById<Button>(R.id.btn_clear_log)
        val btnClose = view.findViewById<Button>(R.id.btn_log_close)

        fun refreshLog() {
            tvLog.text = WindowHardware.logLines.joinToString("\n")
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        refreshLog()

        val prevCb = WindowHardware.onLogUpdated
        WindowHardware.onLogUpdated = { runOnUiThread { refreshLog() } }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setOnDismissListener { WindowHardware.onLogUpdated = prevCb }
            .create()
        btnClear.setOnClickListener { WindowHardware.clearLog() }
        btnClose.setOnClickListener { dialog.dismiss() }
        logDialog = dialog
        dialog.show()
    }

    // ── Info dialog ───────────────────────────────────────────────────────────────
    private fun showInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        val tvInfo = view.findViewById<TextView>(R.id.tv_info)
        val btnOk  = view.findViewById<Button>(R.id.btn_info_close)
        tvInfo.text = Html.fromHtml(getString(R.string.info_text), Html.FROM_HTML_MODE_COMPACT)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun simpleSeekListener(onChange: (Int, Boolean) -> Unit) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

    override fun onDestroy() {
        super.onDestroy()
        logDialog?.dismiss()
    }
}
