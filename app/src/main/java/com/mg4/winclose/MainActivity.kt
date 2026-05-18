package com.mg4.winclose

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tv_log)
        svLog = findViewById(R.id.sv_log)

        val prefs = getSharedPreferences(LocaleHelper.PREF_NAME, Context.MODE_PRIVATE)

        val switchAuto = findViewById<Switch>(R.id.switch_auto)
        switchAuto.isChecked = prefs.getBoolean(WindowService.PREF_AUTO_CLOSE, false)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WindowService.PREF_AUTO_CLOSE, checked).apply()
        }

        findViewById<Button>(R.id.btn_close_hold).setOnClickListener {
            Thread { WindowHardware.closeAllWindowsPulsed(5000L) }.start()
        }
        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            WindowHardware.clearLog()
        }
        findViewById<Button>(R.id.btn_info).setOnClickListener {
            showInfoDialog()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }

        WindowHardware.onLogUpdated = {
            runOnUiThread {
                tvLog.text = WindowHardware.logLines.joinToString("\n")
                svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        WindowService.start(this)
        WindowHardware.onLogUpdated?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        WindowHardware.onLogUpdated = null
    }

    private fun showInfoDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_info, null)
        val tvInfo = view.findViewById<TextView>(R.id.tv_info)
        val btnOk  = view.findViewById<Button>(R.id.btn_info_close)
        tvInfo.text = Html.fromHtml(getString(R.string.info_text), Html.FROM_HTML_MODE_COMPACT)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val btnFr    = view.findViewById<Button>(R.id.btn_lang_fr)
        val btnEn    = view.findViewById<Button>(R.id.btn_lang_en)
        val tvSpeed  = view.findViewById<TextView>(R.id.tv_speed_val)
        val tvTime   = view.findViewById<TextView>(R.id.tv_time_val)
        val tvDelay  = view.findViewById<TextView>(R.id.tv_delay_val)
        val sbSpeed  = view.findViewById<SeekBar>(R.id.sb_speed)
        val sbTime   = view.findViewById<SeekBar>(R.id.sb_time)
        val sbDelay  = view.findViewById<SeekBar>(R.id.sb_delay)
        val btnReset = view.findViewById<Button>(R.id.btn_settings_reset)
        val btnClose = view.findViewById<Button>(R.id.btn_settings_close)

        // ── Langue : peint le bouton actif et gère le changement ──
        fun paintLangButtons(lang: String) {
            val selBg = 0xFFE94560.toInt(); val selFg = 0xFFFFFFFF.toInt()
            val unselBg = 0xFF333355.toInt(); val unselFg = 0xFFAAAAAA.toInt()
            val fr = lang == "fr"
            btnFr.backgroundTintList = ColorStateList.valueOf(if (fr) selBg else unselBg)
            btnFr.setTextColor(if (fr) selFg else unselFg)
            btnEn.backgroundTintList = ColorStateList.valueOf(if (!fr) selBg else unselBg)
            btnEn.setTextColor(if (!fr) selFg else unselFg)
        }
        paintLangButtons(LocaleHelper.getLang(this))

        val dialog = AlertDialog.Builder(this).setView(view).create()
        fun changeLang(lang: String) {
            if (LocaleHelper.getLang(this) == lang) return
            LocaleHelper.setLang(this, lang)
            dialog.dismiss()
            recreate()
        }
        btnFr.setOnClickListener { changeLang("fr") }
        btnEn.setOnClickListener { changeLang("en") }

        // ── Sliders ──
        sbSpeed.min = Settings.MIN_SPEED_KMH; sbSpeed.max = Settings.MAX_SPEED_KMH
        sbTime.min  = Settings.MIN_TIME_MIN;  sbTime.max  = Settings.MAX_TIME_MIN
        sbDelay.min = Settings.MIN_DELAY_SEC; sbDelay.max = Settings.MAX_DELAY_SEC

        fun applyValues(speed: Int, time: Int, delay: Int) {
            sbSpeed.progress = speed
            sbTime.progress  = time
            sbDelay.progress = delay
            tvSpeed.text = getString(R.string.fmt_kmh, speed)
            tvTime.text  = getString(R.string.fmt_min, time)
            tvDelay.text = getString(R.string.fmt_sec, delay)
        }

        applyValues(
            Settings.getSpeedKmh(this),
            Settings.getTimeMin(this),
            Settings.getDelaySec(this)
        )

        sbSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvSpeed.text = getString(R.string.fmt_kmh, p)
                if (fromUser) Settings.setSpeedKmh(this@MainActivity, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbTime.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvTime.text = getString(R.string.fmt_min, p)
                if (fromUser) Settings.setTimeMin(this@MainActivity, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        sbDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvDelay.text = getString(R.string.fmt_sec, p)
                if (fromUser) Settings.setDelaySec(this@MainActivity, p)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // ── Boutons par vitre ──
        data class WinButtons(val area: Int, val auto: Button, val pulsed: Button, val off: Button)
        val winButtons = listOf(
            WinButtons(Settings.AREA_FL,
                view.findViewById(R.id.btn_fl_auto),
                view.findViewById(R.id.btn_fl_pulsed),
                view.findViewById(R.id.btn_fl_off)),
            WinButtons(Settings.AREA_FR,
                view.findViewById(R.id.btn_fr_auto),
                view.findViewById(R.id.btn_fr_pulsed),
                view.findViewById(R.id.btn_fr_off)),
            WinButtons(Settings.AREA_RL,
                view.findViewById(R.id.btn_rl_auto),
                view.findViewById(R.id.btn_rl_pulsed),
                view.findViewById(R.id.btn_rl_off)),
            WinButtons(Settings.AREA_RR,
                view.findViewById(R.id.btn_rr_auto),
                view.findViewById(R.id.btn_rr_pulsed),
                view.findViewById(R.id.btn_rr_off))
        )

        fun paintWindow(wb: WinButtons, mode: WindowMode) {
            val unselBg = 0xFF333355.toInt(); val unselFg = 0xFFAAAAAA.toInt()
            val autoBg = 0xFF2E8B57.toInt()   // vert (auto)
            val pulsBg = 0xFFE94560.toInt()   // rouge (pulsed)
            val offBg  = 0xFF666666.toInt()   // gris (off)
            wb.auto.backgroundTintList   = ColorStateList.valueOf(if (mode == WindowMode.AUTO) autoBg else unselBg)
            wb.auto.setTextColor(if (mode == WindowMode.AUTO) 0xFFFFFFFF.toInt() else unselFg)
            wb.pulsed.backgroundTintList = ColorStateList.valueOf(if (mode == WindowMode.PULSED) pulsBg else unselBg)
            wb.pulsed.setTextColor(if (mode == WindowMode.PULSED) 0xFFFFFFFF.toInt() else unselFg)
            wb.off.backgroundTintList    = ColorStateList.valueOf(if (mode == WindowMode.OFF) offBg else unselBg)
            wb.off.setTextColor(if (mode == WindowMode.OFF) 0xFFFFFFFF.toInt() else unselFg)
        }

        winButtons.forEach { wb ->
            paintWindow(wb, Settings.getWindowMode(this, wb.area))
            wb.auto.setOnClickListener   { Settings.setWindowMode(this, wb.area, WindowMode.AUTO);   paintWindow(wb, WindowMode.AUTO) }
            wb.pulsed.setOnClickListener { Settings.setWindowMode(this, wb.area, WindowMode.PULSED); paintWindow(wb, WindowMode.PULSED) }
            wb.off.setOnClickListener    { Settings.setWindowMode(this, wb.area, WindowMode.OFF);    paintWindow(wb, WindowMode.OFF) }
        }

        btnReset.setOnClickListener {
            Settings.resetDefaults(this)
            applyValues(
                Settings.DEFAULT_SPEED_KMH,
                Settings.DEFAULT_TIME_MIN,
                Settings.DEFAULT_DELAY_SEC
            )
            winButtons.forEach { paintWindow(it, Settings.DEFAULT_WINDOW_MODE) }
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
