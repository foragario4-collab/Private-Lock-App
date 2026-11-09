package at.agar.privatelock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var t: Strings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = L.of(this)
        Ui.edgeToEdge(this)
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root

        root.addView(Ui.backButton(this) { finish() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(Glass.label(this, t.settings, 32f, Glass.TEXT, bold = true).apply {
            letterSpacing = -0.02f
        }, Ui.lp(dp(14)))

        // Sprache
        root.addView(Ui.section(this, t.secLanguage), Ui.lp(dp(26)))
        val langCard = Ui.card(this, 2)
        langCard.addView(Ui.navRow(this, t.languageTitle, null, currentLangName()) { chooseLanguage() })
        root.addView(langCard, Ui.lp(dp(8)))

        // Erkennung
        root.addView(Ui.section(this, t.secDetection), Ui.lp(dp(26)))
        val detect = Ui.card(this, 2)
        detect.addView(flagRow(Flag.SMART, t.smartTitle, t.smartSub).view)
        detect.addView(Ui.separator(this))
        detect.addView(flagRow(Flag.FALL, t.fallTitle, t.fallSub).view)
        detect.addView(Ui.separator(this))
        detect.addView(Ui.segmentRow(
            this, t.delayTitle, t.delaySub,
            listOf(t.delayNow, t.delayShort, t.delayLong), Prefs.DELAYS.indexOf(Prefs.delay(this)).coerceAtLeast(0)
        ) { i -> Prefs.setDelay(this, Prefs.DELAYS[i]) })
        root.addView(detect, Ui.lp(dp(8)))

        // Beim Sperren
        root.addView(Ui.section(this, t.secOnLock), Ui.lp(dp(26)))
        val onLock = Ui.card(this, 2)
        onLock.addView(flagRow(Flag.VIBRATE, t.vibrateTitle, t.vibrateSub).view)
        onLock.addView(Ui.separator(this))
        onLock.addView(flagRow(Flag.ALARM, t.alarmTitle, t.alarmSub).view)
        onLock.addView(Ui.separator(this))
        onLock.addView(flagRow(Flag.NOTIFY, t.notifyTitle, t.notifySub).view)
        root.addView(onLock, Ui.lp(dp(8)))

        // Automatisch
        root.addView(Ui.section(this, t.secAuto), Ui.lp(dp(26)))
        val auto = Ui.card(this, 2)
        auto.addView(flagRow(Flag.BOOT, t.bootTitle, t.bootSub).view)
        auto.addView(Ui.separator(this))
        val drive = Ui.switchRow(
            this, t.driveTitle, t.driveSub,
            Prefs.on(this, Flag.AUTO_DRIVE)
        ) { on ->
            Prefs.set(this, Flag.AUTO_DRIVE, on)
            if (on) askBluetooth()
        }
        autoDriveRow = drive
        auto.addView(drive.view)
        auto.addView(Ui.separator(this))
        auto.addView(flagRow(
            Flag.SCREEN_OFF, t.screenOffTitle, t.screenOffSub
        ).view)
        root.addView(auto, Ui.lp(dp(8)))

        // Berechtigungen (wird in onResume gefüllt)
        root.addView(Ui.section(this, t.secPermissions), Ui.lp(dp(26)))
        permCard = Ui.card(this, 2)
        root.addView(permCard, Ui.lp(dp(8)))

        root.addView(Glass.label(this, "Private Lock · ${t.version} ${appVersion()}", 12f).apply {
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
            this, t.adminTitle, t.adminSub,
            if (admin) t.granted else t.allow, if (admin) Glass.GREEN else Glass.ORANGE,
            onClick = if (admin) null else ({ requestAdmin() })
        ))
        permCard.addView(Ui.separator(this))

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        permCard.addView(Ui.navRow(
            this, t.batteryTitle, t.batterySub,
            if (ignoring) t.batteryDone else t.change, if (ignoring) Glass.GREEN else Glass.ORANGE
        ) { openBatterySettings() })

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            permCard.addView(Ui.separator(this))
            permCard.addView(Ui.navRow(
                this, t.notificationsTitle, t.notificationsSub,
                if (granted) t.allowed else t.allow, if (granted) Glass.GREEN else Glass.ORANGE,
                onClick = if (granted) null else ({ openNotificationSettings() })
            ))
        }

        if (admin) {
            permCard.addView(Ui.separator(this))
            permCard.addView(Ui.navRow(
                this, t.uninstallTitle, t.uninstallSub,
                null, danger = true
            ) {
                Prefs.setActive(this, false)
                LockService.stop(this)
                Admin.remove(this)
                Ui.showBanner(frame, t.adminRemoved, 3500L)
                frame.postDelayed({ refreshPermissions() }, 500L)
            })
        }
    }

    private fun requestAdmin() {
        try {
            startActivity(Admin.requestIntent(this))
        } catch (_: Exception) {
            Ui.showBanner(frame, t.adminOpenFail)
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

    // ---------------------------------------------------------------- Sprache

    private fun currentLangName(): String {
        val chosen = Prefs.lang(this)
        if (chosen == Prefs.LANG_AUTO) return t.languageAuto
        return L.LANGS.firstOrNull { it.first == chosen }?.second ?: t.languageAuto
    }

    private fun chooseLanguage() {
        val codes = listOf(Prefs.LANG_AUTO) + L.LANGS.map { it.first }
        val names = (listOf(t.languageAuto) + L.LANGS.map { it.second }).toTypedArray()
        val current = codes.indexOf(Prefs.lang(this)).coerceAtLeast(0)
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.languageTitle)
            .setSingleChoiceItems(names, current) { dialog, which ->
                dialog.dismiss()
                if (codes[which] != Prefs.lang(this)) {
                    Prefs.setLang(this, codes[which])
                    recreate()
                }
            }
            .show()
        d.window?.setBackgroundDrawable(android.graphics.drawable.InsetDrawable(
            LiquidGlassDrawable(dp(28).toFloat(), 0xF2141A2A.toInt(), dp(1).toFloat() * 1.5f), dp(16)
        ))
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
                Ui.showBanner(frame, t.btDenied, 3500L)
            } else {
                Ui.showBanner(frame, t.btOk)
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
