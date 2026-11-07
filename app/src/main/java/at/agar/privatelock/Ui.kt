package at.agar.privatelock

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Bausteine für die Bildschirme im Liquid-Glass-Look */
object Ui {

    class Screen(val frame: FrameLayout, val root: LinearLayout)

    class SwitchRow(val view: LinearLayout, val sw: GlassSwitch)

    @Suppress("DEPRECATION")
    fun edgeToEdge(a: Activity) {
        a.window.statusBarColor = Color.TRANSPARENT
        a.window.navigationBarColor = Color.TRANSPARENT
        a.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /** Hintergrund mit Farbflecken + scrollbare Spalte */
    fun screen(a: Activity): Screen {
        val frame = FrameLayout(a)
        frame.background = Glass.backdrop()
        val blobs = listOf(
            Triple(0xE63A5BFF.toInt(), 460, Pair(-150, -80)),
            Triple(0xD9B23CFF.toInt(), 420, Pair(170, 330)),
            Triple(0xD900C2A8.toInt(), 460, Pair(-150, 680))
        )
        for ((color, size, pos) in blobs) {
            val b = Glass.blob(a, color, size)
            b.translationX = a.dp(pos.first).toFloat()
            b.translationY = a.dp(pos.second).toFloat()
            frame.addView(b)
        }
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(20), a.dp(56), a.dp(20), a.dp(48))
        }
        val scroll = ScrollView(a).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(root)
        }
        frame.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        a.setContentView(frame)
        return Screen(frame, root)
    }

    /** Abschnitte gleiten nacheinander weich von unten herein */
    fun animateIn(root: LinearLayout) {
        for (i in 0 until root.childCount) {
            val v = root.getChildAt(i)
            v.alpha = 0f
            v.translationY = root.context.dp(22).toFloat()
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(45L * i).setDuration(520)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    fun card(ctx: Context, padV: Int): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(18), ctx.dp(padV), ctx.dp(18), ctx.dp(padV))
        background = Glass.surface(ctx, 26)
    }

    fun section(ctx: Context, s: String) = Glass.label(ctx, s.uppercase(), 13f).apply {
        letterSpacing = 0.06f
        setPadding(ctx.dp(6), 0, 0, 0)
    }

    fun separator(ctx: Context) = View(ctx).apply {
        background = ColorDrawable(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
    }

    private fun texts(ctx: Context, title: String, sub: String?, titleColor: Int = Glass.TEXT): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(Glass.label(ctx, title, 17f, titleColor))
            if (sub != null) addView(Glass.label(ctx, sub, 13f), lp(ctx.dp(2)))
        }

    /** Zeile mit Schalter rechts; Tippen auf die ganze Zeile schaltet auch */
    fun switchRow(ctx: Context, title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): SwitchRow {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(12), 0, ctx.dp(12))
        }
        r.addView(texts(ctx, title, sub), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sw = GlassSwitch(ctx)
        sw.setOn(checked)
        sw.onChange = { v ->
            sw.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onChange(v)
        }
        r.addView(sw, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ctx.dp(12) })
        r.isClickable = true
        r.setOnClickListener { if (sw.isEnabled) sw.setOn(!sw.isChecked, animate = true, fromUser = true) }
        return SwitchRow(r, sw)
    }

    /** Zeile mit Wert/Pfeil rechts */
    fun navRow(ctx: Context, title: String, sub: String?, value: String?, valueColor: Int = Glass.TEXT_SOFT,
               danger: Boolean = false, onClick: (() -> Unit)?): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(0, ctx.dp(12), 0, ctx.dp(12))
        }
        r.addView(texts(ctx, title, sub, if (danger) 0xFFFF8A80.toInt() else Glass.TEXT),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val right = when {
            value != null && onClick != null -> "$value  ›"
            value != null -> value
            onClick != null -> "›"
            else -> null
        }
        if (right != null) {
            r.addView(Glass.label(ctx, right, if (value == null) 24f else 16f, valueColor),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = ctx.dp(12) })
        }
        if (onClick != null) {
            r.isClickable = true
            r.background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))
            r.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
            Glass.pressEffect(r, 0.98f)
        }
        return r
    }

    /** Zeile mit Titel, Erklärung und Auswahl-Leiste darunter */
    fun segmentRow(ctx: Context, title: String, sub: String?, labels: List<String>, selected: Int,
                   onSelect: (Int) -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ctx.dp(12), 0, ctx.dp(14))
        addView(texts(ctx, title, sub))
        addView(GlassSegmented(ctx, labels, selected, onSelect), lp(ctx.dp(10)))
    }

    /** Runder Glas-Knopf mit Symbol */
    fun iconButton(ctx: Context, icon: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(icon)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            LiquidGlassDrawable(ctx.dp(23).toFloat(), 0x26FFFFFF, ctx.dp(1).toFloat() * 1.5f), null
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
        Glass.pressEffect(this, 0.9f)
    }

    /** Glas-Pille „‹ Zurück“ */
    fun backButton(ctx: Context, onClick: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(18), ctx.dp(10))
        background = RippleDrawable(
            ColorStateList.valueOf(0x40FFFFFF),
            LiquidGlassDrawable(ctx.dp(22).toFloat(), 0x26FFFFFF, ctx.dp(1).toFloat() * 1.5f), null
        )
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_back) })
        addView(Glass.label(ctx, "Zurück", 16f, Glass.TEXT).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(ctx.dp(4), 0, 0, 0)
        })
        isClickable = true
        isFocusable = true
        contentDescription = "Zurück"
        setOnClickListener { onClick() }
        Glass.pressEffect(this, 0.94f)
    }

    /** Schieberegler im Glas-Look */
    fun slider(ctx: Context, max: Int, progress: Int, onChange: (Int, Boolean) -> Unit): SeekBar = SeekBar(ctx).apply {
        this.max = max
        this.progress = progress
        progressTintList = ColorStateList.valueOf(Glass.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(ctx.dp(1).coerceAtLeast(1), 0x33000000)
            setSize(ctx.dp(26), ctx.dp(26))
        }
        splitTrack = false
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) sb?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onChange(p, fromUser)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    /** Glas-Meldung, die federnd von oben hereinfällt und nach ein paar Sekunden verschwindet */
    fun showBanner(frame: FrameLayout, msg: String, durationMs: Long = 2800L) {
        val ctx = frame.context
        val bannerTag = "banner"
        frame.findViewWithTag<View>(bannerTag)?.let { frame.removeView(it) }
        val tv = TextView(ctx).apply {
            tag = bannerTag
            text = msg
            textSize = 15f
            setTextColor(Glass.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(ctx.dp(20), ctx.dp(15), ctx.dp(20), ctx.dp(15))
            setShadowLayer(ctx.dp(3).toFloat(), 0f, ctx.dp(1).toFloat(), 0x73000000)
            background = LiquidGlassDrawable(ctx.dp(24).toFloat(), 0xD9141A2A.toInt(), ctx.dp(1).toFloat() * 1.5f)
            elevation = ctx.dp(12).toFloat()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply { setMargins(ctx.dp(16), ctx.dp(44), ctx.dp(16), 0) }
        frame.addView(tv, params)
        tv.alpha = 0f
        tv.translationY = -ctx.dp(44).toFloat()
        tv.scaleX = 0.92f
        tv.scaleY = 0.92f
        tv.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(520).setInterpolator(OvershootInterpolator(1.2f)).start()
        tv.postDelayed({
            tv.animate().alpha(0f).translationY(-ctx.dp(30).toFloat()).setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { frame.removeView(tv) }.start()
        }, durationMs)
    }
}
