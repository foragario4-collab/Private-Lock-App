package at.agar.privatelock

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Der große runde Glas-Knopf: leuchtet in der Akzentfarbe, wenn der Schutz aktiv ist.
 */
class OrbView(ctx: Context) : View(ctx) {
    var isOn = false
        private set
    var onToggle: (() -> Unit)? = null

    private var glow = 0f
    private var glowAnim: ValueAnimator? = null
    private val evaluator = ArgbEvaluator()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat() * 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Glass.TEXT
        textAlign = Paint.Align.CENTER
        textSize = 22f * ctx.resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13f * ctx.resources.displayMetrics.scaledDensity
    }
    private val lockClosed: Drawable? = ctx.getDrawable(R.drawable.ic_lock_big)?.mutate()
    private val lockOpen: Drawable? = ctx.getDrawable(R.drawable.ic_lock_open_big)?.mutate()
    private val s = L.of(ctx)

    init {
        isClickable = true
        isFocusable = true
        contentDescription = s.orbDesc
        setOnClickListener { onToggle?.invoke() }
        Glass.pressEffect(this, 0.94f)
    }

    fun show(on: Boolean, animate: Boolean) {
        isOn = on
        val target = if (on) 1f else 0f
        glowAnim?.cancel()
        if (animate) {
            glowAnim = ValueAnimator.ofFloat(glow, target).apply {
                duration = 550
                interpolator = DecelerateInterpolator()
                addUpdateListener { glow = it.animatedValue as Float; invalidate() }
                start()
            }
            scaleX = 0.93f
            scaleY = 0.93f
            animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(2.2f)).start()
        } else {
            glow = target
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = context.dp(260)
        setMeasuredDimension(s, s)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = context.dp(105).toFloat()
        shinePaint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.55f, r * 1.1f,
            intArrayOf(0x66FFFFFF, 0x0DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        shadePaint.shader = LinearGradient(
            0f, cy, 0f, cy + r,
            0x00000000, 0x40000000, Shader.TileMode.CLAMP
        )
        rimPaint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0xF2FFFFFF.toInt(), 0x40FFFFFF, 0x14FFFFFF, 0x33FFFFFF, 0xB3FFFFFF.toInt()),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = context.dp(105).toFloat()

        // Leuchten rundherum
        if (glow > 0.01f) {
            val outer = r * 1.24f
            glowPaint.shader = RadialGradient(
                cx, cy, outer,
                intArrayOf(withAlpha(Glass.ACCENT, 0.6f * glow), withAlpha(Glass.ACCENT, 0.18f * glow), 0x00000000),
                floatArrayOf(0.75f, 0.88f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, outer, glowPaint)
        }

        // Glaskörper
        bodyPaint.color = evaluator.evaluate(glow, 0x14FFFFFF, withAlpha(Glass.ACCENT, 0.32f)) as Int
        c.drawCircle(cx, cy, r, bodyPaint)
        c.drawCircle(cx, cy, r, shinePaint)
        c.drawCircle(cx, cy, r, shadePaint)
        c.drawCircle(cx, cy, r - rimPaint.strokeWidth / 2, rimPaint)

        // Schloss + Text
        val icon = if (isOn) lockClosed else lockOpen
        val s = context.dp(56)
        val top = (cy - context.dp(58)).toInt()
        icon?.setBounds((cx - s / 2).toInt(), top, (cx + s / 2).toInt(), top + s)
        icon?.draw(c)
        c.drawText(if (isOn) s.orbOn else s.orbOff, cx, cy + context.dp(30), labelPaint)
        c.drawText(if (isOn) s.orbHintOn else s.orbHintOff, cx, cy + context.dp(54), hintPaint)
    }
}

/** Live-Anzeige der Bewegung: Balken + gestrichelte Auslöse-Linie */
class SensorGraphView(ctx: Context) : View(ctx) {
    private val values = FloatArray(30)
    private var head = 0
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ctx.dp(1).toFloat() * 1.5f
        color = 0xCCFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(ctx.dp(5).toFloat(), ctx.dp(4).toFloat()), 0f)
    }
    private val r = RectF()

    var threshold = 12f
        set(v) {
            field = v
            invalidate()
        }

    fun push(v: Float) {
        values[head] = v
        head = (head + 1) % values.size
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), context.dp(60))
    }

    override fun onDraw(c: Canvas) {
        val n = values.size
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = context.dp(3).toFloat()
        val bw = (w - gap * (n - 1)) / n
        val rad = context.dp(3).toFloat()
        for (i in 0 until n) {
            val v = values[(head + i) % n]
            val bh = (v / Detector.SCALE_MAX).coerceIn(0.04f, 1f) * h
            bar.color = if (v >= threshold) Glass.ACCENT else 0x52FFFFFF
            val x = i * (bw + gap)
            r.set(x, h - bh, x + bw, h)
            c.drawRoundRect(r, rad, rad, bar)
        }
        val ty = h - (threshold / Detector.SCALE_MAX).coerceIn(0f, 1f) * h
        c.drawLine(0f, ty, w, ty, line)
    }
}
