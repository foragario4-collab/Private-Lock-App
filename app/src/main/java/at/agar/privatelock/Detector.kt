package at.agar.privatelock

import kotlin.math.sqrt

/**
 * Erkennt aus dem Beschleunigungssensor:
 *  - GRAB: das Handy wird weggerissen (starker Ruck)
 *  - FALL: das Handy fällt (freier Fall)
 *
 * "Intelligente Erkennung": ein einzelner kurzer Stoß (Handy hinlegen, Schlagloch)
 * reicht nicht – die Bewegung muss kurz anhalten, wie beim Wegreißen.
 */
class Detector(private val onTrigger: (Reason) -> Unit) {

    enum class Reason { GRAB, FALL }

    var threshold = 12f
    var smart = true
    var fall = true

    /** Letzte gemessene Bewegungsstärke (m/s², ohne Schwerkraft) – für die Live-Anzeige */
    var lastMagnitude = 0f
        private set

    private class Sample(val t: Long, val v: Float)

    private val gravity = FloatArray(3)
    private var gravityReady = false
    private val samples = ArrayDeque<Sample>()
    private var fallStart = 0L
    private var lastTrigger = 0L

    fun reset() {
        gravityReady = false
        samples.clear()
        fallStart = 0L
    }

    fun onAccel(x: Float, y: Float, z: Float, tMs: Long) {
        val raw = sqrt(x * x + y * y + z * z)

        // Schwerkraft herausrechnen (Tiefpass)
        if (!gravityReady) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityReady = true
        } else {
            val a = 0.85f
            gravity[0] = a * gravity[0] + (1 - a) * x
            gravity[1] = a * gravity[1] + (1 - a) * y
            gravity[2] = a * gravity[2] + (1 - a) * z
        }
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val lin = sqrt(lx * lx + ly * ly + lz * lz)
        lastMagnitude = lin

        samples.addLast(Sample(tMs, lin))
        while (samples.isNotEmpty() && samples.first().t < tMs - WINDOW_MS) samples.removeFirst()

        if (tMs - lastTrigger < COOLDOWN_MS) return

        // Freier Fall: fast keine Beschleunigung messbar
        if (fall) {
            if (raw < FREE_FALL) {
                if (fallStart == 0L) {
                    fallStart = tMs
                } else if (tMs - fallStart >= FALL_MS) {
                    fire(Reason.FALL, tMs)
                    return
                }
            } else {
                fallStart = 0L
            }
        }

        if (lin >= threshold) {
            val ok = if (!smart) true else samples.count { it.v >= threshold * 0.6f } >= 4
            if (ok) fire(Reason.GRAB, tMs)
        }
    }

    private fun fire(r: Reason, t: Long) {
        lastTrigger = t
        fallStart = 0L
        onTrigger(r)
    }

    companion object {
        private const val WINDOW_MS = 350L
        private const val COOLDOWN_MS = 2000L
        private const val FREE_FALL = 2.5f
        private const val FALL_MS = 120L

        /** Obergrenze der Live-Anzeige */
        const val SCALE_MAX = 35f

        /**
         * Empfindlichkeit 1 (wenig) … 10 (sehr empfindlich) -> Schwelle in m/s².
         * Gröber eingestellt: braucht einen deutlich stärkeren Ruck als vorher
         * (Stufe 1 ≈ 28, Stufe 6 ≈ 17, Stufe 10 ≈ 8 m/s²).
         */
        fun threshold(sens: Int, mode: String): Float {
            val base = 30f - sens.coerceIn(1, 10) * 2.2f
            val factor = when (mode) {
                Prefs.MODE_DRIVE -> 1.7f
                Prefs.MODE_BAG -> 1.5f
                else -> 1f
            }
            return base * factor
        }
    }
}
