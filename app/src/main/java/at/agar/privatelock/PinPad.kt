package at.agar.privatelock

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Eigenes Ziffernfeld im Glas-Look (statt der Handy-Tastatur).
 * Zeigt die Eingabe als Punkte. Ist [autoLength] > 0, wird automatisch geprüft,
 * sobald so viele Ziffern eingegeben sind – sonst mit der ✓-Taste.
 */
class PinPad(ctx: Context, var autoLength: Int, private val onSubmit: (String) -> Unit) : LinearLayout(ctx) {

    private val entered = StringBuilder()
    val title: TextView
    val error: TextView
    private val dots: LinearLayout

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Ziffernfeld immer von links nach rechts, auch bei Arabisch/Hebräisch
        layoutDirection = View.LAYOUT_DIRECTION_LTR

        title = Glass.label(ctx, "", 17f, Glass.TEXT).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        addView(title, wrap())

        dots = LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }
        addView(dots, LayoutParams(LayoutParams.WRAP_CONTENT, ctx.dp(22)).apply { topMargin = ctx.dp(18) })

        error = Glass.label(ctx, "", 14f, Glass.RED).apply {
            gravity = Gravity.CENTER
            minLines = 1
        }
        addView(error, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })

        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("✓", "0", "⌫")
        )
        rows.forEachIndexed { i, row ->
            val line = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            row.forEachIndexed { j, k ->
                line.addView(key(ctx, k), LayoutParams(ctx.dp(74), ctx.dp(74)).apply {
                    if (j > 0) leftMargin = ctx.dp(22)
                })
            }
            addView(line, wrap().apply { topMargin = if (i == 0) ctx.dp(14) else ctx.dp(14) })
        }
        refresh()
    }

    private fun wrap() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun key(ctx: Context, k: String): TextView = TextView(ctx).apply {
        text = k
        gravity = Gravity.CENTER
        setTextColor(Glass.TEXT)
        textSize = if (k.length == 1 && k[0].isDigit()) 30f else 24f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        background = if (k[0].isDigit()) {
            LiquidGlassDrawable(ctx.dp(37).toFloat(), 0x1FFFFFFF, ctx.dp(1).toFloat() * 1.5f)
        } else {
            null // ✓ und ⌫ ohne Glas, wie beim iPhone
        }
        contentDescription = when (k) {
            "✓" -> "OK"
            "⌫" -> "Delete"
            else -> k
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            when (k) {
                "⌫" -> back()
                "✓" -> submit()
                else -> press(k[0])
            }
        }
        Glass.pressEffect(this, 0.88f)
    }

    private fun press(c: Char) {
        if (entered.length >= MAX) return
        entered.append(c)
        error.text = ""
        refresh()
        if (autoLength > 0 && entered.length == autoLength) postDelayed({ submit() }, 120L)
    }

    private fun back() {
        if (entered.isNotEmpty()) entered.deleteCharAt(entered.length - 1)
        refresh()
    }

    private fun submit() {
        if (entered.isEmpty()) return
        onSubmit(entered.toString())
    }

    /** Eingabe leeren (z. B. nach falscher PIN) */
    fun clear() {
        entered.setLength(0)
        refresh()
    }

    /** Falsche Eingabe: Punkte schütteln, leeren */
    fun fail(msg: String) {
        error.text = msg
        dots.animate().translationX(context.dp(12).toFloat()).setDuration(50).withEndAction {
            dots.animate().translationX(-context.dp(12).toFloat()).setDuration(50).withEndAction {
                dots.animate().translationX(0f).setDuration(50).start()
            }.start()
        }.start()
        clear()
    }

    private fun refresh() {
        dots.removeAllViews()
        val count = maxOf(if (autoLength > 0) autoLength else 4, entered.length)
        for (i in 0 until count) {
            val filled = i < entered.length
            val d = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (filled) setColor(Glass.TEXT) else setColor(0x00000000)
                    setStroke(context.dp(1).coerceAtLeast(2), 0xCCFFFFFF.toInt())
                }
            }
            dots.addView(d, LayoutParams(context.dp(14), context.dp(14)).apply {
                if (i > 0) leftMargin = context.dp(14)
            })
        }
    }

    companion object {
        const val MAX = 8
    }
}
