package at.agar.privatelock

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Einstellungen: Erkennung · Beim Sperren · Automatisch · Berechtigungen */
class SettingsActivity : Activity() {

    private lateinit var frame: FrameLayout
    private lateinit var permCard: LinearLayout
    private var autoDriveRow: Ui.SwitchRow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.edgeToEdge(this)
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root

        root.addView(Ui.backButton(this) { finish() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(Glass.label(this, "Einstellungen", 32f, Glass.TEXT, bold = true).apply {
            letterSpacing = -0.02f
        }, Ui.lp(dp(14)))

        // Erkennung
        root.addView(Ui.section(this, "Erkennung"), Ui.lp(dp(26)))
        val detect = Ui.card(this, 2)
        detect.addView(flagRow(Flag.SMART, "Intelligente Erkennung", "Unterscheidet Wegreißen von normalem Hinlegen").view)
        detect.addView(Ui.separator(this))
        detect.addView(flagRow(Flag.FALL, "Fallerkennung", "Sperrt, wenn das Handy herunterfällt").view)
        detect.addView(Ui.separator(this))
        detect.addView(Ui.segmentRow(
            this, "Auslöseverzögerung", "Wie schnell nach dem Ruck gesperrt wird",
            listOf("Sofort", "0,3 s", "0,8 s"), Prefs.DELAYS.indexOf(Prefs.delay(this)).coerceAtLeast(0)
        ) { i -> Prefs.setDelay(this, Prefs.DELAYS[i]) })
        root.addView(detect, Ui.lp(dp(8)))

        // Beim Sperren
        root.addView(Ui.section(this, "Beim Sperren"), Ui.lp(dp(26)))
        val onLock = Ui.card(this, 2)
        onLock.addView(flagRow(Flag.VIBRATE, "Vibration", "Kurzes Feedback, wenn gesperrt wird").view)
        onLock.addView(Ui.separator(this))
        onLock.addView(flagRow(Flag.ALARM, "Alarmton", "Lauter Ton, bis du das Handy entsperrst (höchstens 1 Minute)").view)
        onLock.addView(Ui.separator(this))
        onLock.addView(flagRow(Flag.NOTIFY, "Benachrichtigung", "Zeigt danach, wann gesperrt wurde").view)
        root.addView(onLock, Ui.lp(dp(8)))

        // Automatisch
        root.addView(Ui.section(this, "Automatisch"), Ui.lp(dp(26)))
        val auto = Ui.card(this, 2)
        auto.addView(flagRow(Flag.BOOT, "Beim Hochfahren starten", "Schutz läuft nach einem Neustart sofort wieder").view)
        auto.addView(Ui.separator(this))
        val drive = Ui.switchRow(
            this, "Fahrmodus im Auto",
            "Schaltet automatisch um, wenn das Handy per Bluetooth mit dem Auto verbunden ist",
            Prefs.on(this, Flag.AUTO_DRIVE)
        ) { on ->
            Prefs.set(this, Flag.AUTO_DRIVE, on)
            if (on) askBluetooth()
        }
        autoDriveRow = drive
        auto.addView(drive.view)
        auto.addView(Ui.separator(this))
        auto.addView(flagRow(
            Flag.SCREEN_OFF, "Auch bei ausgeschaltetem Display",
            "Bewegung erzwingt sofort die PIN-Eingabe – braucht etwas mehr Akku"
        ).view)
        root.addView(auto, Ui.lp(dp(8)))

        // Berechtigungen (wird in onResume gefüllt)
        root.addView(Ui.section(this, "Berechtigungen"), Ui.lp(dp(26)))
        permCard = Ui.card(this, 2)
        root.addView(permCard, Ui.lp(dp(8)))

        root.addView(Glass.label(this, "Private Lock · Version ${appVersion()}", 12f).apply {
            gravity = Gravity.CENTER
        }, Ui.lp(dp(28)))

        Ui.animateIn(root)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun flagRow(f: Flag, title: String, sub: String): Ui.SwitchRow =
        Ui.switchRow(this, title, sub, Prefs.on(this, f)) { on -> Prefs.set(this, f, on) }

    // ---------------------------------------------------------------- Berechtigungen

    private fun refreshPermissions() {
        permCard.removeAllViews()
        val admin = Admin.isActive(this)
        permCard.addView(Ui.navRow(
            this, "Sperr-Berechtigung", "Nötig, damit die App das Handy sperren kann",
            if (admin) "Erteilt" else "Erlauben", if (admin) Glass.GREEN else Glass.ORANGE,
            onClick = if (admin) null else ({ requestAdmin() })
        ))
        permCard.addView(Ui.separator(this))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        permCard.addView(Ui.navRow(
            this, "Akku-Optimierung", "Auf „Nicht optimieren“ stellen, damit Android den Schutz nicht beendet",
            if (ignoring) "Aus" else "Ändern", if (ignoring) Glass.GREEN else Glass.ORANGE
        ) { openBatterySettings() })

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            permCard.addView(Ui.separator(this))
            permCard.addView(Ui.navRow(
                this, "Benachrichtigungen", "Für die Anzeige „Schutz aktiv“ und Sperr-Meldungen",
                if (granted) "Erlaubt" else "Erlauben", if (granted) Glass.GREEN else Glass.ORANGE,
                onClick = if (granted) null else ({ openNotificationSettings() })
            ))
        }

        if (admin) {
            permCard.addView(Ui.separator(this))
            permCard.addView(Ui.navRow(
                this, "Vor dem Deinstallieren",
                "Sperr-Berechtigung entziehen – sonst lässt sich die App nicht löschen",
                null, danger = true
            ) {
                Prefs.setActive(this, false)
                LockService.stop(this)
                Admin.remove(this)
                Ui.showBanner(frame, "Berechtigung entzogen – du kannst die App jetzt deinstallieren", 3500L)
                frame.postDelayed({ refreshPermissions() }, 500L)
            })
        }
    }

    private fun requestAdmin() {
        try {
            startActivity(Admin.requestIntent(this))
        } catch (_: Exception) {
            Ui.showBanner(frame, "Die Sperr-Berechtigung konnte nicht geöffnet werden")
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- Bluetooth für den Fahrmodus

    private fun askBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!ok) {
                Prefs.set(this, Flag.AUTO_DRIVE, false)
                autoDriveRow?.sw?.setOn(false, animate = true)
                Ui.showBanner(frame, "Ohne Bluetooth-Erlaubnis kann die App das Auto nicht erkennen", 3500L)
            } else {
                Ui.showBanner(frame, "Fahrmodus schaltet ab jetzt automatisch um")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    companion object {
        private const val REQ_BT = 20
    }
}
