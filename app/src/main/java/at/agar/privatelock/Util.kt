package at.agar.privatelock

import android.content.Context

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Farbe mit neuer Deckkraft (0..1) */
fun withAlpha(color: Int, a: Float): Int =
    (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)
