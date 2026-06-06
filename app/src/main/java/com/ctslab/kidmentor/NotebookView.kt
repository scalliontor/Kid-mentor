package com.ctslab.kidmentor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Grade-1 handwriting practice block on Vietnamese "ô-ly" (grid) paper — part of the
 * Kid Mentor chat "mini-notebook" (from the Claude Design handoff: NotebookBlock).
 *
 * Draws a faint blue square grid, a dashed midline + a red baseline (tập-viết style),
 * the target glyph in the handwriting font, and — when [strokeOrder] is on — a numbered
 * start dot and a direction arrow.
 */
class NotebookView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var glyph: String = "a"
        set(value) { field = value; invalidate() }
    var hint: String? = null
        set(value) { field = value; invalidate() }
    var strokeOrder: Boolean = false
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val gridColor = ContextCompat.getColor(context, R.color.km_notebook_grid)
    private val baselineColor = ContextCompat.getColor(context, R.color.km_notebook_baseline)
    private val glyphColor = ContextCompat.getColor(context, R.color.km_notebook_glyph)
    private val sun = ContextCompat.getColor(context, R.color.km_sun)
    private val orange = ContextCompat.getColor(context, R.color.km_orange)

    private val handTypeface: Typeface? =
        ResourcesCompat.getFont(context, R.font.patrick_hand)
    private val uiTypeface: Typeface? =
        ResourcesCompat.getFont(context, R.font.nunito_extrabold)

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor; strokeWidth = dp(1f); style = Paint.Style.STROKE
    }
    private val midlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor; strokeWidth = dp(1.5f); style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = baselineColor; strokeWidth = dp(2f); style = Paint.Style.STROKE; alpha = 140
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = glyphColor; typeface = handTypeface; textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = orange; strokeWidth = dp(2.2f); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sun }
    private val dotTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = uiTypeface; textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.km_slate)
        typeface = uiTypeface; textSize = dp(12f)
    }

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.NotebookView)
            glyph = a.getString(R.styleable.NotebookView_km_glyph) ?: glyph
            hint = a.getString(R.styleable.NotebookView_km_hint)
            strokeOrder = a.getBoolean(R.styleable.NotebookView_km_strokeOrder, false)
            a.recycle()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cell = dp(22f)

        // ── Square ô-ly grid ──────────────────────────────────────────────
        var x = cell
        while (x < w) { canvas.drawLine(x, 0f, x, h, gridPaint); x += cell }
        var y = cell
        while (y < h) { canvas.drawLine(0f, y, w, y, gridPaint); y += cell }

        // ── Writing guides: dashed midline + red baseline ─────────────────
        val mid = h * 0.5f
        canvas.drawLine(0f, mid, w, mid, midlinePaint)
        val baseY = h * 0.76f
        canvas.drawLine(0f, baseY, w, baseY, baselinePaint)

        // ── Glyph in the handwriting font ─────────────────────────────────
        glyphPaint.textSize = h * 0.6f
        val cx = w / 2f
        val fm = glyphPaint.fontMetrics
        val gy = mid - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, cx, gy, glyphPaint)

        // ── Stroke-order hint: numbered dot + arrow ───────────────────────
        if (strokeOrder) {
            val gw = glyphPaint.measureText(glyph)
            val dotR = dp(12f)
            val dotCx = cx - gw / 2f
            val dotCy = gy + fm.ascent
            canvas.drawCircle(dotCx, dotCy, dotR, dotPaint)
            dotTextPaint.textSize = dp(13f)
            val tfm = dotTextPaint.fontMetrics
            canvas.drawText("1", dotCx, dotCy - (tfm.ascent + tfm.descent) / 2f, dotTextPaint)

            // arrow to the right of the glyph: "→"
            val ax = cx + gw / 2f + dp(14f)
            val ay = mid
            val aLen = dp(22f)
            canvas.drawLine(ax, ay, ax + aLen, ay, arrowPaint)
            val head = Path().apply {
                moveTo(ax + aLen - dp(8f), ay - dp(6f))
                lineTo(ax + aLen, ay)
                lineTo(ax + aLen - dp(8f), ay + dp(6f))
            }
            canvas.drawPath(head, arrowPaint)
        }

        // ── Hint label (bottom-left) ──────────────────────────────────────
        hint?.let {
            canvas.drawText(it, dp(10f), h - dp(8f), hintPaint)
        }
    }
}
