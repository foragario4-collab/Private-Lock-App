package at.agar.privatelock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Einstellungen der App-Sperre */
class AppLockActivity : Activity() {

    private lateinit var t: Strings
    private lateinit var frame: FrameLayout
    private var first = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = L.of(this)
        Ui.edgeToEdge(this)
    }

    override fun onResume() {
        super.onResume()
        if (AppLock.gateSelf(this)) return
        build()
    }

    private fun build() {
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root

        root.addView(Ui.backButton(this) { finish() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(Glass.label(this, t.secAppLock, 32f, Glass.TEXT, bold = true).apply {
            letterSpacing = -0.02f
        }, Ui.lp(dp(14)))

        // Bedienungshilfe nötig?
        val acc = AppLockService.isEnabled(this)
        if (!acc) {
            val warn = Ui.card(this, 2)
            warn.addView(Ui.navRow(this, t.accTitle, t.accSub, t.allow, Glass.ORANGE) { askAccessibility() })
            root.addView(warn, Ui.lp(dp(20)))
        }

        // Apps
        val n = AppLock.selected(this).size
        val apps = Ui.card(this, 2)
        apps.addView(Ui.navRow(this, t.chooseApps, String.format(t.appsCount, n.toString()), null) {
            startActivity(Intent(this, AppPickerActivity::class.java))
        })
        root.addView(apps, Ui.lp(dp(20)))
        root.addView(Glass.button(this, "🔒  " + t.lockAllNow, primary = true) {
            LockSetup.ensure(this, onReady = {
                AppLock.lockAll(this)
                AppLock.selfUnlocked = true // wir sind gerade hier drin
                Feedback.vibrate(this, 40)
                build()
                Ui.showBanner(frame, t.lockedAllMsg)
            })
        }, Ui.lp(dp(14)))

        // Wann
        root.addView(Ui.section(this, t.whenTitle), Ui.lp(dp(26)))
        val whenCard = Ui.card(this, 2)
        whenCard.addView(Ui.segmentRow(
            this, t.whenTitle, t.whenSub, listOf(t.whenAlways, t.whenTheft),
            AppLock.WHENS.indexOf(AppLock.whenMode(this)).coerceAtLeast(0)
        ) { i -> AppLock.setWhenMode(this, AppLock.WHENS[i]) })
        whenCard.addView(Ui.separator(this))
        whenCard.addView(Ui.segmentRow(
            this, t.relockTitle, t.relockSub, listOf(t.delayNow, t.relockMinute, t.relockScreen),
            AppLock.RELOCKS.indexOf(AppLock.relock(this)).coerceAtLeast(0)
        ) { i -> AppLock.setRelock(this, AppLock.RELOCKS[i]) })
        whenCard.addView(Ui.separator(this))
        whenCard.addView(Ui.switchRow(this, t.theftAppsTitle, t.theftAppsSub, Prefs.on(this, Flag.APPLOCK_THEFT)) { on ->
            Prefs.set(this, Flag.APPLOCK_THEFT, on)
        }.view)
        root.addView(whenCard, Ui.lp(dp(8)))

        // Entsperren
        root.addView(Ui.section(this, t.methodTitle), Ui.lp(dp(26)))
        val method = Ui.card(this, 2)
        method.addView(Ui.segmentRow(
            this, t.methodTitle, if (!AppLock.systemAvailable(this)) t.noSecureLock else null,
            listOf(t.methodSystem, t.methodPin),
            AppLock.METHODS.indexOf(AppLock.method(this)).coerceAtLeast(0)
        ) { i ->
            val m = AppLock.METHODS[i]
            AppLock.setMethod(this, m)
            if (m == AppLock.METHOD_PIN && !AppLock.hasPin(this)) {
                LockSetup.askPin(this, onSaved = {
                    build()
                    Ui.showBanner(frame, t.pinSaved)
                }, onCancel = {
                    // ohne PIN zurück auf Fingerabdruck/Code, damit man sich nicht aussperrt
                    AppLock.setMethod(this, AppLock.METHOD_SYSTEM)
                    build()
                })
            }
        })
        method.addView(Ui.separator(this))
        val hasPin = AppLock.hasPin(this)
        method.addView(Ui.navRow(
            this, t.pinTitle, null, if (hasPin) t.pinIsSet else t.pinNotSet,
            if (hasPin) Glass.GREEN else Glass.TEXT_SOFT
        ) {
            LockSetup.askPin(this, onSaved = {
                build()
                Ui.showBanner(frame, t.pinSaved)
            })
        })
        method.addView(Ui.separator(this))
        lateinit var selfRow: Ui.SwitchRow
        selfRow = Ui.switchRow(this, t.selfTitle, t.selfSub, AppLock.protectSelf(this)) { on ->
            if (on) {
                LockSetup.ensure(this, onReady = {
                    AppLock.setProtectSelf(this, true)
                    AppLock.selfUnlocked = true
                    build()
                }, onCancel = { selfRow.sw.setOn(false, animate = true) })
            } else {
                AppLock.setProtectSelf(this, false)
            }
        }
        method.addView(selfRow.view)
        root.addView(method, Ui.lp(dp(8)))

        // Berechtigung + Tipp
        if (acc) {
            root.addView(Ui.section(this, t.secPermissions), Ui.lp(dp(26)))
            val perm = Ui.card(this, 2)
            perm.addView(Ui.navRow(this, t.accTitle, t.accSub, t.granted, Glass.GREEN) { openAccessibility() })
            root.addView(perm, Ui.lp(dp(8)))
        }
        root.addView(Glass.label(this, t.settingsHint, 12.5f).apply {
            gravity = Gravity.CENTER
        }, Ui.lp(dp(20)))

        if (first) {
            Ui.animateIn(root)
            first = false
        }
    }

    // ---------------------------------------------------------------- Bedienungshilfe

    private fun askAccessibility() {
        val d = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.accTitle)
            .setMessage(t.accExplain)
            .setPositiveButton(t.allow) { _, _ -> openAccessibility() }
            .setNegativeButton(t.cancel, null)
            .show()
        LockSetup.glassy(this, d)
    }

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
        }
    }
}
