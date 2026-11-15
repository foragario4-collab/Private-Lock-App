package at.agar.privatelock

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Sperrbildschirm für eine geschützte App */
class LockActivity : Activity() {

    private lateinit var t: Strings
    private lateinit var frame: FrameLayout
    private var pkg = ""
    private var inSystemAuth = false
    private var cancel: CancellationSignal? = null
    private var done = false
    private var userLeft = false      // Home/Übersicht gedrückt
    private var autoPrompted = false  // Fingerabdruck-Abfrage schon automatisch gezeigt
    private var stopped = false
    private var triedFallback = false
    private var test = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        t = L.of(this)
        // Inhalt nicht in der "Zuletzt verwendet"-Ansicht oder auf Screenshots zeigen
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        AppLock.authBegin()
        setup(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        AppLock.authBegin()
        // Gleiche App, Sperrbildschirm ist schon sichtbar -> nichts neu starten (Abfrage läuft weiter)
        val newPkg = intent?.getStringExtra(EXTRA_PKG) ?: packageName
        if (newPkg == pkg && !done && !stopped) return
        cancel?.cancel()
        inSystemAuth = false
        done = false
        setup(intent)
        if (hasWindowFocus()) autoPrompt()
    }

    private fun setup(i: Intent?) {
        pkg = i?.getStringExtra(EXTRA_PKG) ?: packageName
        test = i?.getBooleanExtra(EXTRA_TEST, false) ?: false
        userLeft = false
        autoPrompted = false
        triedFallback = false
        if (!AppLock.canAuthenticate(this)) {
            // Keine Möglichkeit zu entsperren (keine Displaysperre, keine PIN) -> nicht aussperren
            success()
            return
        }
        build()
    }

    /** Erst wenn der Sperrbildschirm wirklich vorne ist, die System-Abfrage starten (sonst bricht Android sie ab) */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) autoPrompt()
    }

    private fun autoPrompt() {
        if (done || inSystemAuth || autoPrompted || AppLock.usesPin(this)) return
        autoPrompted = true
        handler.postDelayed({ if (!done && !stopped) systemAuth() }, 80L)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        userLeft = true
    }

    override fun onStart() {
        super.onStart()
        stopped = false
    }

    /** Sperrbildschirm wurde von der App verdrängt -> schließen und gleich neu darüberlegen */
    private fun coveredByApp() {
        done = true
        cancel?.cancel()
        AppLock.authReset()
        finish()
        AppLockService.instance?.recheckSoon()
    }

    private fun build() {
        Ui.edgeToEdge(this)
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root
        root.gravity = Gravity.CENTER_HORIZONTAL
        val pinMode = AppLock.usesPin(this)
        root.setPadding(dp(24), if (pinMode) dp(64) else dp(110), dp(24), dp(40))

        val icon = ImageView(this).apply {
            setImageDrawable(appIcon())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconSize = if (pinMode) dp(64) else dp(84)
        root.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
        root.addView(Glass.label(this, appLabel(), 26f, Glass.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(18) })
        root.addView(Glass.label(this, "🔒  " + t.lockedApp, 15f).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })

        if (AppLock.usesPin(this)) buildPin(root) else {
            root.addView(Glass.button(this, t.unlock, primary = true) { systemAuth() }, Ui.lp(dp(40)))
        }
        root.addView(Glass.button(this, t.cancel) { goHome() }, Ui.lp(dp(12)))
        Ui.animateIn(root)
    }

    private fun buildPin(root: LinearLayout) {
        lateinit var pad: PinPad
        pad = PinPad(this, AppLock.pinLength(this)) { pin ->
            val wait = lockoutLeft()
            if (wait <= 0 && failed >= MAX_TRIES) failed = 0
            if (wait > 0) {
                pad.fail(t.tooManyTries)
            } else if (AppLock.checkPin(this, pin)) {
                failed = 0
                success()
            } else {
                failed++
                if (failed >= MAX_TRIES) lockoutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
                Feedback.vibrate(this, 120)
                pad.fail(if (failed >= MAX_TRIES) t.tooManyTries else t.wrongPin)
            }
        }
        pad.title.text = t.enterPin
        root.addView(pad, Ui.lp(dp(26)))
    }

    private fun lockoutLeft(): Long = lockoutUntil - SystemClock.elapsedRealtime()

    // ---------------------------------------------------------------- System: Fingerabdruck / Handy-Code

    private fun systemAuth() {
        if (done || inSystemAuth) return
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) {
            success()
            return
        }
        inSystemAuth = true
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val b = BiometricPrompt.Builder(this)
                    .setTitle(appLabel())
                    .setSubtitle(t.lockedApp)
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                } else {
                    allowCredentialLegacy(b)
                }
                val c = CancellationSignal()
                cancel = c
                b.build().authenticate(c, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        inSystemAuth = false
                        success()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        inSystemAuth = false
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            AppLock.lastError = "BiometricPrompt $errorCode: ${errString ?: ""}"
                        }
                        if (done) return
                        if (stopped && !userLeft) {
                            coveredByApp()
                        } else if (stopped) {
                            // Während der Abfrage Home gedrückt
                            done = true
                            AppLock.authFinish()
                            finish()
                        } else if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED) {
                            // Abfrage ließ sich nicht starten -> normale Code-Abfrage des Handys
                            fallbackConfirm(km)
                        }
                    }
                })
            } else {
                legacyConfirm(km)
            }
        } catch (e: Exception) {
            inSystemAuth = false
            AppLock.lastError = "BiometricPrompt: " + e.javaClass.simpleName + " " + (e.message ?: "")
            fallbackConfirm(km)
        }
    }

    /** Notfall-Weg: die gewohnte Entsperr-Seite des Handys (PIN/Muster, je nach Handy auch Fingerabdruck) */
    private fun fallbackConfirm(km: KeyguardManager) {
        if (triedFallback || done) return
        triedFallback = true
        try {
            inSystemAuth = true
            legacyConfirm(km)
        } catch (e: Exception) {
            inSystemAuth = false
            AppLock.lastError = "Confirm: " + e.javaClass.simpleName + " " + (e.message ?: "")
            Ui.showBanner(frame, t.noSecureLock)
        }
    }

    @Suppress("DEPRECATION")
    private fun allowCredentialLegacy(b: BiometricPrompt.Builder) {
        b.setDeviceCredentialAllowed(true)
    }

    @Suppress("DEPRECATION")
    private fun legacyConfirm(km: KeyguardManager) {
        val i = km.createConfirmDeviceCredentialIntent(appLabel(), t.lockedApp)
        if (i == null) {
            inSystemAuth = false
            success()
        } else {
            startActivityForResult(i, REQ_CONFIRM)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CONFIRM) {
            inSystemAuth = false
            if (resultCode == RESULT_OK) success()
        }
    }

    // ---------------------------------------------------------------- Ende

    private fun success() {
        if (done) return
        done = true
        if (test) {
            AppLock.lastError = null
            android.widget.Toast.makeText(this, "✓ " + t.testOk, android.widget.Toast.LENGTH_LONG).show()
        } else {
            AppLock.markUnlocked(this, pkg)
        }
        AppLock.authFinish()
        finish()
        noAnimation()
    }

    private fun goHome() {
        done = true
        cancel?.cancel()
        AppLock.authFinish()
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
        finish()
        noAnimation()
    }

    @Suppress("DEPRECATION")
    private fun noAnimation() {
        overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        if (inSystemAuth || isFinishing || done) return
        if (userLeft) {
            // Home gedrückt: Sperrbildschirm schließen (die App bleibt gesperrt)
            done = true
            AppLock.authFinish()
            finish()
        } else {
            // Die App hat sich darübergeschoben (z. B. nach dem Ladebildschirm)
            coveredByApp()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cancel?.cancel()
        if (!done) AppLock.authFinish()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- App-Infos

    private fun appLabel(): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun appIcon(): Drawable? = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) {
        getDrawable(R.drawable.ic_lock_big)
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_TEST = "test"
        private const val REQ_CONFIRM = 31
        private const val MAX_TRIES = 5
        private const val LOCKOUT_MS = 30_000L

        // Fehlversuche gelten für alle Apps gemeinsam
        private var failed = 0
        private var lockoutUntil = 0L
    }
}
