package at.agar.privatelock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Startseite: großer Schalter, Empfindlichkeit mit Live-Anzeige, Modus, Test */
class MainActivity : Activity(), SensorEventListener {

    private lateinit var frame: FrameLayout
    private lateinit var orb: OrbView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var warnBox: LinearLayout
    private lateinit var sensValue: TextView
    private lateinit var graph: SensorGraphView
    private lateinit var modeText: TextView

    private var sm: SensorManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val testDetector = Detector { r -> onTestTrigger(r) }
    private var testUntil = 0L
    private var batchMax = 0f
    private var lastPush = 0L

    // ---------------------------------------------------------------- Aufbau

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockService.createChannels(this)
        sm = getSystemService(SENSOR_SERVICE) as? SensorManager
        Ui.edgeToEdge(this)
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root

        // Kopf: Titel + Status, rechts Einstellungen
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(Glass.label(this, "Private Lock", 30f, Glass.TEXT, bold = true).apply { letterSpacing = -0.02f })
        val statusLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this)
        statusLine.addView(statusDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        statusText = Glass.label(this, "", 14f)
        statusLine.addView(statusText)
        titles.addView(statusLine, Ui.lp(dp(4)))
        header.addView(titles, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Ui.iconButton(this, R.drawable.ic_settings, "Einstellungen") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(header)

        // Hinweis, falls die Sperr-Berechtigung fehlt
        warnBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(warnBox, Ui.lp(0))

        // Großer Knopf
        orb = OrbView(this)
        orb.onToggle = { toggle() }
        root.addView(orb, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })

        // Empfindlichkeit
        val sensCard = Ui.card(this, 16)
        val sensHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sensHead.addView(Glass.label(this, "Empfindlichkeit", 16f, Glass.TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        sensValue = Glass.label(this, "", 14f)
        sensHead.addView(sensValue)
        sensCard.addView(sensHead)
        sensCard.addView(Ui.slider(this, 9, Prefs.sens(this) - 1) { p, fromUser ->
            if (fromUser) Prefs.setSens(this, p + 1)
            updateSensLabel(p + 1)
            refreshThreshold()
        }, Ui.lp(dp(8)))
        graph = SensorGraphView(this)
        sensCard.addView(graph, Ui.lp(dp(6)))
        sensCard.addView(Glass.label(this, "Bewegung live – Ausschläge über der Linie sperren das Handy.", 12.5f), Ui.lp(dp(10)))
        root.addView(sensCard, Ui.lp(dp(6)))

        // Modus
        val modeCard = Ui.card(this, 14)
        val modeIdx = Prefs.MODES.indexOf(Prefs.mode(this)).coerceAtLeast(0)
        modeCard.addView(GlassSegmented(this, listOf("Normal", "Fahrmodus", "Tasche"), modeIdx) { i ->
            Prefs.setMode(this, Prefs.MODES[i])
            updateModeText()
            refreshThreshold()
        })
        modeText = Glass.label(this, "", 13f).apply {
            minLines = 2
            setPadding(dp(4), 0, dp(4), 0)
        }
        modeCard.addView(modeText, Ui.lp(dp(10)))
        root.addView(modeCard, Ui.lp(dp(16)))

        // Test
        root.addView(Glass.button(this, "Sperre testen") { startTest() }, Ui.lp(dp(16)))
        root.addView(Glass.label(this, "Testet die Erkennung 15 Sekunden lang – ohne wirklich zu sperren", 12.5f).apply {
            gravity = Gravity.CENTER
        }, Ui.lp(dp(8)))

        updateSensLabel(Prefs.sens(this))
        updateModeText()
        refreshThreshold()
        refreshStatus(false)
        Ui.animateIn(root)
    }

    override fun onResume() {
        super.onResume()
        sm?.let { m ->
            m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
        testDetector.reset()
        refreshThreshold()
        refreshStatus(false)
        // Schutz soll laufen, läuft aber nicht (z. B. nach dem Beenden durch Android) -> neu starten
        if (Prefs.active(this) && !LockService.running) LockService.start(this)
    }

    override fun onPause() {
        super.onPause()
        sm?.unregisterListener(this)
    }

    // ---------------------------------------------------------------- Ein/Aus

    private fun toggle() {
        if (Prefs.active(this)) {
            Prefs.setActive(this, false)
            LockService.stop(this)
            Feedback.vibrate(this, 20)
            refreshStatus(true)
            return
        }
        if (!Admin.isActive(this)) {
            requestAdmin(REQ_ADMIN_ACTIVATE)
            return
        }
        activate()
    }

    private fun activate() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        Prefs.setActive(this, true)
        LockService.start(this)
        Feedback.vibrate(this, 30)
        refreshStatus(true)
    }

    private fun requestAdmin(req: Int) {
        try {
            startActivityForResult(Admin.requestIntent(this), req)
        } catch (_: Exception) {
            Ui.showBanner(frame, "Die Sperr-Berechtigung konnte nicht geöffnet werden")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ADMIN_ACTIVATE || requestCode == REQ_ADMIN_ONLY) {
            if (Admin.isActive(this)) {
                if (requestCode == REQ_ADMIN_ACTIVATE) activate()
                else Ui.showBanner(frame, "Sperr-Berechtigung erteilt")
            } else {
                Ui.showBanner(frame, "Ohne Sperr-Berechtigung kann die App das Handy nicht sperren", 3800L)
            }
            refreshStatus(false)
        }
    }

    // ---------------------------------------------------------------- Anzeige

    private fun refreshStatus(animate: Boolean) {
        val active = Prefs.active(this)
        val admin = Admin.isActive(this)
        orb.show(active, animate)

        val (color, text) = when {
            active && !admin -> Glass.ORANGE to "Sperr-Berechtigung fehlt"
            active && Prefs.drivingByCar(this) -> 0xFF30D158.toInt() to "Schutz aktiv · Fahrmodus (Auto)"
            active -> 0xFF30D158.toInt() to "Schutz aktiv · überwacht Bewegung"
            else -> 0x80EBEBF5.toInt() to "Schutz ausgeschaltet"
        }
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        statusText.text = text

        warnBox.removeAllViews()
        if (!admin) {
            val card = Ui.card(this, 2)
            card.addView(Ui.navRow(
                this, "Sperr-Berechtigung fehlt", "Ohne sie kann die App das Handy nicht sperren",
                "Erlauben", Glass.ORANGE
            ) { requestAdmin(REQ_ADMIN_ONLY) })
            warnBox.addView(card, Ui.lp(dp(16)))
        }
    }

    private fun updateSensLabel(sens: Int) {
        val word = when {
            sens <= 3 -> "Niedrig"
            sens <= 7 -> "Mittel"
            else -> "Hoch"
        }
        sensValue.text = "$word · $sens/10"
    }

    private fun updateModeText() {
        modeText.text = when (Prefs.mode(this)) {
            Prefs.MODE_DRIVE -> "Weniger empfindlich – Bremsen und Schlaglöcher lösen nichts aus."
            Prefs.MODE_BAG -> "Sperrt nur bei starkem Ruck, z. B. wenn jemand das Handy herauszieht."
            else -> "Standard-Schutz für den Alltag."
        }
    }

    private fun refreshThreshold() {
        val t = Prefs.threshold(this)
        graph.threshold = t
        testDetector.threshold = t
        testDetector.smart = Prefs.on(this, Flag.SMART)
        testDetector.fall = Prefs.on(this, Flag.FALL)
    }

    // ---------------------------------------------------------------- Sensor (Live-Anzeige + Test)

    override fun onSensorChanged(event: SensorEvent) {
        val t = event.timestamp / 1_000_000L
        testDetector.onAccel(event.values[0], event.values[1], event.values[2], t)
        if (testDetector.lastMagnitude > batchMax) batchMax = testDetector.lastMagnitude
        if (t - lastPush >= 70L) {
            graph.push(batchMax)
            batchMax = 0f
            lastPush = t
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---------------------------------------------------------------- Test

    private fun startTest() {
        testUntil = SystemClock.elapsedRealtime() + TEST_MS
        LockService.pausedUntil = testUntil
        testDetector.reset()
        Ui.showBanner(frame, "Test läuft – schnapp dir das Handy oder beweg es ruckartig")
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ Ui.showBanner(frame, "Test beendet – der Schutz ist wieder scharf") }, TEST_MS)
    }

    private fun onTestTrigger(r: Detector.Reason) {
        if (SystemClock.elapsedRealtime() > testUntil) return
        Feedback.vibrate(this, 200)
        val what = if (r == Detector.Reason.FALL) "Sturz erkannt!" else "Erkannt!"
        Ui.showBanner(frame, "$what Im Echtbetrieb wäre das Handy jetzt gesperrt.")
    }

    companion object {
        private const val REQ_ADMIN_ACTIVATE = 10
        private const val REQ_ADMIN_ONLY = 11
        private const val REQ_NOTIFY = 12
        private const val TEST_MS = 15_000L
    }
}
