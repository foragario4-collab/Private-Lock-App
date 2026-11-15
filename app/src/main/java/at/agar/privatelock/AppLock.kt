package at.agar.privatelock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App-Sperre: merkt sich, welche Apps geschützt sind und ob sie gerade gesperrt sind.
 *
 *  - lockedNow  (gespeichert): Apps, die nach Diebstahl / "Jetzt sperren" gesperrt bleiben,
 *                              bis man sie einzeln entsperrt – auch nach Neustart.
 *  - unlocked   (nur im Speicher): im Modus "Immer" die gerade entsperrten Apps.
 */
object AppLock {
    const val WHEN_ALWAYS = "always"
    const val WHEN_THEFT = "theft"
    val WHENS = listOf(WHEN_ALWAYS, WHEN_THEFT)

    const val RELOCK_NOW = "now"
    const val RELOCK_MINUTE = "minute"
    const val RELOCK_SCREEN = "screen"
    val RELOCKS = listOf(RELOCK_NOW, RELOCK_MINUTE, RELOCK_SCREEN)

    const val METHOD_SYSTEM = "system"
    const val METHOD_PIN = "pin"
    val METHODS = listOf(METHOD_SYSTEM, METHOD_PIN)

    private const val MINUTE_MS = 60_000L
    private const val AUTH_TIMEOUT_MS = 120_000L
    private const val AUTH_GRACE_MS = 1_500L

    private val unlocked = HashSet<String>()
    private val leftAt = HashMap<String, Long>()

    /** Private Lock selbst ist entsperrt (bis man die App verlässt oder das Display ausgeht) */
    @Volatile
    var selfUnlocked = false

    // Sperrbildschirm offen? Dann ignoriert der Wächter alle Fenster-Wechsel (z. B. die PIN-Abfrage des Systems)
    @Volatile
    private var authStart = 0L
    @Volatile
    private var authEnd = 0L

    // ---------------------------------------------------------------- Einstellungen

    private fun p(ctx: Context) = Prefs.get(ctx)

    fun selected(ctx: Context): Set<String> = HashSet(p(ctx).getStringSet("al_apps", null) ?: emptySet())

    fun isSelected(ctx: Context, pkg: String) = pkg in selected(ctx)

    fun setSelected(ctx: Context, pkg: String, on: Boolean) {
        val s = HashSet(selected(ctx))
        if (on) s.add(pkg) else s.remove(pkg)
        p(ctx).edit().putStringSet("al_apps", s).apply()
        if (!on) {
            val l = HashSet(lockedNow(ctx))
            l.remove(pkg)
            setLockedNow(ctx, l)
        }
    }

    fun whenMode(ctx: Context): String = p(ctx).getString("al_when", WHEN_ALWAYS)?.takeIf { it in WHENS } ?: WHEN_ALWAYS
    fun setWhenMode(ctx: Context, v: String) = p(ctx).edit().putString("al_when", v).apply()

    fun relock(ctx: Context): String = p(ctx).getString("al_relock", RELOCK_NOW)?.takeIf { it in RELOCKS } ?: RELOCK_NOW
    fun setRelock(ctx: Context, v: String) = p(ctx).edit().putString("al_relock", v).apply()

    fun method(ctx: Context): String = p(ctx).getString("al_method", METHOD_SYSTEM)?.takeIf { it in METHODS } ?: METHOD_SYSTEM
    fun setMethod(ctx: Context, v: String) = p(ctx).edit().putString("al_method", v).apply()

    fun protectSelf(ctx: Context): Boolean = p(ctx).getBoolean("al_self", false)
    fun setProtectSelf(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("al_self", v).apply()

    private fun lockedNow(ctx: Context): Set<String> = HashSet(p(ctx).getStringSet("al_locked", null) ?: emptySet())
    private fun setLockedNow(ctx: Context, s: Set<String>) = p(ctx).edit().putStringSet("al_locked", HashSet(s)).apply()

    // ---------------------------------------------------------------- PIN

    fun hasPin(ctx: Context): Boolean = p(ctx).getString("al_pin", null) != null

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        p(ctx).edit().putString("al_pin", hex(salt) + ":" + hash(salt, pin)).apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val stored = p(ctx).getString("al_pin", null) ?: return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = unhex(parts[0]) ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), parts[1].toByteArray())
    }

    private fun hash(salt: ByteArray, pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        var out = md.digest(pin.toByteArray(Charsets.UTF_8))
        repeat(5000) { out = MessageDigest.getInstance("SHA-256").digest(out + salt) }
        return hex(out)
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String): ByteArray? = try {
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    } catch (_: Exception) {
        null
    }

    /** Kann man auf diesem Handy überhaupt entsperren? (Displaysperre fürs System oder eigene PIN) */
    fun systemAvailable(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
    } catch (_: Exception) {
        false
    }

    fun usesPin(ctx: Context): Boolean = method(ctx) == METHOD_PIN || !systemAvailable(ctx)

    fun canAuthenticate(ctx: Context): Boolean = if (method(ctx) == METHOD_PIN) hasPin(ctx) else systemAvailable(ctx) || hasPin(ctx)

    // ---------------------------------------------------------------- Zustand

    @Synchronized
    fun needsLock(ctx: Context, pkg: String): Boolean {
        // Ohne Sperrmethode niemals sperren – sonst wäre man ausgesperrt
        if (!canAuthenticate(ctx)) return false
        if (pkg == ctx.packageName) return protectSelf(ctx) && !selfUnlocked
        if (pkg !in selected(ctx)) return false
        if (pkg in lockedNow(ctx)) return true
        if (whenMode(ctx) != WHEN_ALWAYS) return false
        val left = leftAt.remove(pkg)
        if (left != null && relock(ctx) == RELOCK_MINUTE && SystemClock.elapsedRealtime() - left > MINUTE_MS) {
            unlocked.remove(pkg)
        }
        return pkg !in unlocked
    }

    @Synchronized
    fun markUnlocked(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) {
            selfUnlocked = true
            return
        }
        val l = HashSet(lockedNow(ctx))
        if (l.remove(pkg)) setLockedNow(ctx, l)
        unlocked.add(pkg)
        leftAt.remove(pkg)
    }

    /** Eine App wurde verlassen (eine andere ist jetzt im Vordergrund) */
    @Synchronized
    fun onLeave(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) {
            selfUnlocked = false
            return
        }
        if (whenMode(ctx) != WHEN_ALWAYS || pkg !in unlocked) return
        when (relock(ctx)) {
            RELOCK_NOW -> unlocked.remove(pkg)
            RELOCK_MINUTE -> leftAt[pkg] = SystemClock.elapsedRealtime()
        }
    }

    @Synchronized
    fun onScreenOff() {
        unlocked.clear()
        leftAt.clear()
        selfUnlocked = false
    }

    /** Diebstahl oder "Jetzt alle sperren": alle ausgewählten Apps bleiben gesperrt, bis man sie einzeln entsperrt */
    @Synchronized
    fun lockAll(ctx: Context) {
        setLockedNow(ctx, selected(ctx))
        unlocked.clear()
        leftAt.clear()
        selfUnlocked = false
        AppLockService.instance?.recheck()
    }

    // ---------------------------------------------------------------- Sperrbildschirm

    fun authBegin() {
        authStart = SystemClock.elapsedRealtime()
        authEnd = 0L
    }

    fun authFinish() {
        authEnd = SystemClock.elapsedRealtime()
    }

    /** Sperrbildschirm wurde verdrängt: sofort wieder prüfen dürfen (ohne Wartezeit) */
    fun authReset() {
        authStart = 0L
        authEnd = 0L
    }

    fun authActive(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (authStart == 0L) return false
        if (authEnd == 0L) return now - authStart < AUTH_TIMEOUT_MS
        return now - authEnd < AUTH_GRACE_MS
    }

    fun showLock(ctx: Context, pkg: String) {
        authBegin()
        try {
            ctx.startActivity(
                Intent(ctx, LockActivity::class.java)
                    .putExtra(LockActivity.EXTRA_PKG, pkg)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
            )
        } catch (_: Exception) {
            authFinish()
        }
    }

    /** In jeder Seite von Private Lock aufrufen: verlangt die Entsperrung, wenn die App selbst geschützt ist */
    fun gateSelf(a: android.app.Activity): Boolean {
        if (!protectSelf(a) || selfUnlocked || !canAuthenticate(a)) return false
        showLock(a, a.packageName)
        return true
    }
}
