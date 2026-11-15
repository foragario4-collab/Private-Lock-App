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

    /** Dialog: eigene PIN zweimal eingeben */
    fun askPin(a: Activity, onSaved: () -> Unit, onCancel: () -> Unit = {}) {
        val t = L.of(a)
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(22), a.dp(8), a.dp(22), a.dp(4))
        }
        fun pinField(hint: String) = Glass.input(a, hint, true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        val p1Field = pinField(t.newPin)
        val p2Field = pinField(t.confirmPin)
        box.addView(p1Field)
        box.addView(p2Field, Ui.lp(a.dp(10)))
        val d = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(t.pinTitle)
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(t.save, null)
            .setNegativeButton(t.cancel) { _, _ -> onCancel() }
            .show()
        glassy(a, d)
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val p1 = p1Field.text.toString()
            val p2 = p2Field.text.toString()
            if (p1.length !in 4..8 || !p1.all { c -> c.isDigit() }) {
                p2Field.error = t.pinTooShort
            } else if (p1 != p2) {
                p2Field.error = t.pinMismatch
            } else {
                AppLock.setPin(a, p1)
                d.dismiss()
                onSaved()
            }
        }
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
