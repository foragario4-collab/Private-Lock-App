package at.agar.privatelock

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.InsetDrawable
import android.text.InputType
import android.view.Gravity
import android.widget.LinearLayout

/**
 * Sorgt dafür, dass vor dem Sperren immer eine Sperrmethode feststeht –
 * sonst könnte man sich aus den eigenen Apps aussperren.
 */
object LockSetup {

    /** Ist eine funktionierende Sperrmethode eingerichtet? */
    fun ready(a: Activity): Boolean = AppLock.canAuthenticate(a)

    /** Führt [onReady] sofort aus, wenn eine Methode da ist – sonst erst nachdem man eine festgelegt hat */
    fun ensure(a: Activity, onReady: () -> Unit, onCancel: () -> Unit = {}) {
        if (ready(a)) {
            onReady()
            return
        }
        val t = L.of(a)
        val system = AppLock.systemAvailable(a)
        val b = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.methodTitle)
            .setMessage(if (system) t.setupMsg else t.setupMsg + "\n\n" + t.noSecureLock)
            .setCancelable(false)
            .setNeutralButton(t.methodPin) { _, _ ->
                askPin(a, onSaved = {
                    AppLock.setMethod(a, AppLock.METHOD_PIN)
                    onReady()
                }, onCancel = onCancel)
            }
            .setNegativeButton(t.cancel) { _, _ -> onCancel() }
        if (system) {
            b.setPositiveButton(t.methodSystem) { _, _ ->
                AppLock.setMethod(a, AppLock.METHOD_SYSTEM)
                onReady()
            }
        }
        glassy(a, b.show())
    }

    /** Dialog: eigene PIN zweimal eingeben – mit eigenem Ziffernfeld statt Handy-Tastatur */
    fun askPin(a: Activity, onSaved: () -> Unit, onCancel: () -> Unit = {}) {
        val t = L.of(a)
        var first: String? = null
        lateinit var d: AlertDialog
        lateinit var pad: PinPad
        pad = PinPad(a, 0) { pin ->
            val f = first
            if (f == null) {
                // Schritt 1: neue PIN
                if (pin.length !in 4..PinPad.MAX) {
                    pad.fail(t.pinTooShort)
                } else {
                    first = pin
                    pad.autoLength = pin.length
                    pad.clear()
                    pad.title.text = t.confirmPin
                }
            } else {
                // Schritt 2: wiederholen
                if (pin == f) {
                    AppLock.setPin(a, pin)
                    d.dismiss()
                    onSaved()
                } else {
                    first = null
                    pad.autoLength = 0
                    pad.fail(t.pinMismatch)
                    pad.title.text = t.newPin
                }
            }
        }
        pad.title.text = t.newPin
        pad.setPadding(a.dp(8), a.dp(16), a.dp(8), a.dp(8))
        d = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.pinTitle)
            .setView(pad)
            .setCancelable(false)
            .setNegativeButton(t.cancel) { _, _ -> onCancel() }
            .show()
        glassy(a, d)
    }

    fun glassy(a: Activity, d: AlertDialog) {
        d.window?.setBackgroundDrawable(InsetDrawable(
            LiquidGlassDrawable(a.dp(28).toFloat(), 0xF2141A2A.toInt(), a.dp(1).toFloat() * 1.5f), a.dp(16)
        ))
        for (btn in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            d.getButton(btn)?.setTextColor(Glass.ACCENT)
        }
    }
}
