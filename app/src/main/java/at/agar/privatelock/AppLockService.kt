package at.agar.privatelock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Bedienungshilfe: erkennt, welche App gerade geöffnet wird, und legt bei geschützten Apps
 * den Sperrbildschirm darüber. Liest keine Inhalte – nur den Namen der App im Vordergrund.
 */
class AppLockService : AccessibilityService() {

    private var current: String? = null
    private var ignore: Set<String> = emptySet()
    private val handler = Handler(Looper.getMainLooper())

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> AppLock.onScreenOff()
                Intent.ACTION_USER_PRESENT -> recheck()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ignore = buildIgnore()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(screenReceiver, f)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignore) return
        // Unser eigener Sperrbildschirm
        if (pkg == packageName && e.className?.toString() == LockActivity::class.java.name) return
        // Code-Eingabe des Systems, während unser Sperrbildschirm offen ist
        if (pkg in AUTH_UI && AppLock.authActive()) return

        if (pkg != current) {
            current?.let { AppLock.onLeave(this, it) }
            current = pkg
            if (pkg != packageName) AppLock.selfUnlocked = false
        }
        // Eigene Seiten prüfen sich selbst (gateSelf)
        if (pkg != packageName && AppLock.needsLock(this, pkg)) AppLock.showLock(this, pkg)
    }

    /** Nach "Jetzt sperren" oder dem Entsperren des Handys: die offene App sofort abdecken */
    fun recheck() {
        val c = current ?: return
        if (c == packageName) return
        if (AppLock.needsLock(this, c)) AppLock.showLock(this, c)
    }

    /** Die App hat sich über den Sperrbildschirm geschoben -> gleich wieder abdecken */
    fun recheckSoon() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) recheck()
        }, 300L)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /** Fenster, die keine "echte" App sind: Systemleiste, Tastaturen, Berechtigungs-Dialoge */
    private fun buildIgnore(): Set<String> {
        val s = hashSetOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            for (im in imm.enabledInputMethodList) s.add(im.packageName)
        } catch (_: Exception) {
        }
        return s
    }

    companion object {
        /** Fenster der System-Code-Abfrage (dürfen die geschützte App nicht "verlassen") */
        private val AUTH_UI = setOf("com.android.settings", "com.google.android.settings")

        @Volatile
        var instance: AppLockService? = null

        /** Ist die Bedienungshilfe in den Android-Einstellungen eingeschaltet? */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, AppLockService::class.java)
            val list = try {
                Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) {
                null
            } ?: return false
            return list.split(':').any {
                ComponentName.unflattenFromString(it) == me
            }
        }
    }
}
