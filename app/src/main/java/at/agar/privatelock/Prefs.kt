package at.agar.privatelock

import android.content.Context
import android.content.SharedPreferences

/** Schalter in den Einstellungen: Schlüssel + Standardwert */
enum class Flag(val key: String, val def: Boolean) {
    SMART("smart", true),
    FALL("fall", true),
    VIBRATE("vibrate", true),
    ALARM("alarm", false),
    NOTIFY("notify", true),
    BOOT("boot", true),
    AUTO_DRIVE("auto_drive", false),
    SCREEN_OFF("screen_off", false)
}

/** Alle Einstellungen an einer Stelle */
object Prefs {
    const val MODE_NORMAL = "normal"
    const val MODE_DRIVE = "fahrt"
    const val MODE_BAG = "tasche"
    val MODES = listOf(MODE_NORMAL, MODE_DRIVE, MODE_BAG)

    const val DELAY_NOW = "sofort"
    const val DELAY_SHORT = "kurz"
    const val DELAY_LONG = "lang"
    val DELAYS = listOf(DELAY_NOW, DELAY_SHORT, DELAY_LONG)

    fun get(ctx: Context): SharedPreferences = ctx.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    fun active(ctx: Context): Boolean = get(ctx).getBoolean("active", false)
    fun setActive(ctx: Context, on: Boolean) = get(ctx).edit().putBoolean("active", on).apply()

    fun sens(ctx: Context): Int = get(ctx).getInt("sens", 6).coerceIn(1, 10)
    fun setSens(ctx: Context, v: Int) = get(ctx).edit().putInt("sens", v.coerceIn(1, 10)).apply()

    fun mode(ctx: Context): String = get(ctx).getString("mode", MODE_NORMAL)?.takeIf { it in MODES } ?: MODE_NORMAL
    fun setMode(ctx: Context, m: String) = get(ctx).edit().putString("mode", m).apply()

    fun delay(ctx: Context): String = get(ctx).getString("delay", DELAY_SHORT)?.takeIf { it in DELAYS } ?: DELAY_SHORT
    fun setDelay(ctx: Context, d: String) = get(ctx).edit().putString("delay", d).apply()

    fun on(ctx: Context, f: Flag): Boolean = get(ctx).getBoolean(f.key, f.def)
    fun set(ctx: Context, f: Flag, v: Boolean) = get(ctx).edit().putBoolean(f.key, v).apply()

    /** Wird vom Dienst gesetzt, wenn ein Auto per Bluetooth verbunden ist */
    fun carConnected(ctx: Context): Boolean = get(ctx).getBoolean("car_connected", false)
    fun setCarConnected(ctx: Context, v: Boolean) = get(ctx).edit().putBoolean("car_connected", v).apply()

    /** Modus, der gerade wirklich gilt (Auto verbunden -> Fahrmodus) */
    fun effectiveMode(ctx: Context): String =
        if (on(ctx, Flag.AUTO_DRIVE) && carConnected(ctx)) MODE_DRIVE else mode(ctx)

    fun drivingByCar(ctx: Context): Boolean = on(ctx, Flag.AUTO_DRIVE) && carConnected(ctx)

    fun threshold(ctx: Context): Float = Detector.threshold(sens(ctx), effectiveMode(ctx))

    fun delayMs(ctx: Context): Long = when (delay(ctx)) {
        DELAY_NOW -> 0L
        DELAY_LONG -> 800L
        else -> 300L
    }

    fun modeName(m: String): String = when (m) {
        MODE_DRIVE -> "Fahrmodus"
        MODE_BAG -> "Tasche"
        else -> "Normal"
    }
}
