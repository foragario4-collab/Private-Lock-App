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
        cancel?.cancel()
        inSystemAuth = false
        done = false
        setup(intent)
    }

    private fun setup(i: Intent?) {
        pkg = i?.getStringExtra(EXTRA_PKG) ?: packageName
        if (!AppLock.canAuthenticate(this)) {
            // Keine Möglichkeit zu entsperren (keine Displaysperre, keine PIN) -> nicht aussperren
            success()
            return
        }
        build()
        if (!AppLock.usesPin(this)) handler.postDelayed({ if (!done) systemAuth() }, 250L)
    }

    private fun build() {
        Ui.edgeToEdge(this)
        val s = Ui.screen(this)
        frame = s.frame
        val root = s.root
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(24), dp(110), dp(24), dp(48))

        val icon = ImageView(this).apply {
            setImageDrawable(appIcon())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        root.addView(icon, LinearLayout.LayoutParams(dp(84), dp(84)))
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
        val input = Glass.input(this, t.enterPin, true).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 24f
            letterSpacing = 0.3f
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val error = Glass.label(this, "", 14f, Glass.RED).apply { gravity = Gravity.CENTER }
        val check: () -> Unit = {
            val wait = lockoutLeft()
            if (wait <= 0 && failed >= MAX_TRIES) failed = 0
            if (wait > 0) {
                error.text = t.tooManyTries
            } else if (AppLock.checkPin(this, input.text.toString())) {
                failed = 0
                success()
            } else {
                failed++
                if (failed >= MAX_TRIES) lockoutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
                input.setText("")
                error.text = if (failed >= MAX_TRIES) t.tooManyTries else t.wrongPin
                Feedback.vibrate(this, 120)
                input.animate().translationX(dp(10).toFloat()).setDuration(50).withEndAction {
                    input.animate().translationX(-dp(10).toFloat()).setDuration(50).withEndAction {
                        input.animate().translationX(0f).setDuration(50).start()
                    }.start()
                }.start()
            }
        }
        input.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) {
                check()
                true
            } else false
        }
        root.addView(input, Ui.lp(dp(36)))
        root.addView(error, Ui.lp(dp(8)))
        root.addView(Glass.button(this, t.unlock, primary = true) { check() }, Ui.lp(dp(14)))
        input.requestFocus()
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
                    }
                })
            } else {
                legacyConfirm(km)
            }
        } catch (_: Exception) {
            inSystemAuth = false
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
        AppLock.markUnlocked(this, pkg)
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
        // Weggewischt / Home gedrückt: Sperrbildschirm schließen (die App bleibt gesperrt)
        if (!inSystemAuth && !isFinishing) {
            done = true
            AppLock.authFinish()
            finish()
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
        private const val REQ_CONFIRM = 31
        private const val MAX_TRIES = 5
        private const val LOCKOUT_MS = 30_000L

        // Fehlversuche gelten für alle Apps gemeinsam
        private var failed = 0
        private var lockoutUntil = 0L
    }
}
