package com.ctslab.ptalk_signature

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A subtle animated star field background for P-Talk Signature (Kid Mentor & Elder Care).
 * Draws small 4-pointed stars that drift gently, like a calm galaxy.
 *
 * Self-contained: it generates stars programmatically and animates itself while attached
 * to the window, so it only needs to be declared in the layout (no extra wiring required).
 */
class StarFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Star(
        var x: Float,
        var y: Float,
        val size: Float,
        val speedX: Float,
        val speedY: Float,
        val alpha: Int,
        val twinkleSpeed: Float,
        var twinklePhase: Float
    )

    private val stars = mutableListOf<Star>()
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPath = Path()
    private var animator: ValueAnimator? = null

    private val starColors = intArrayOf(
        0xFF80CBC4.toInt(), // teal-200
        0xFFA5D6A7.toInt(), // green-200
        0xFF81D4FA.toInt(), // light-blue-200
        0xFFAED581.toInt(), // light-green-300
        0xFF80DEEA.toInt(), // cyan-200
        0xFF4DB6AC.toInt(), // teal-300
        0xFF4FC3F7.toInt(), // light-blue-300
    )

    private fun ensureStars() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || stars.isNotEmpty()) return

        val density = resources.displayMetrics.density
        val count = 45

        for (i in 0 until count) {
            stars.add(
                Star(
                    x = Random.nextFloat() * w,
                    y = Random.nextFloat() * h,
                    size = (6f + Random.nextFloat() * 12f) * density,
                    speedX = (Random.nextFloat() - 0.5f) * 0.5f * density,
                    speedY = (0.2f + Random.nextFloat() * 0.5f) * density,
                    alpha = 80 + Random.nextInt(140), // 80–220
                    twinkleSpeed = 0.025f + Random.nextFloat() * 0.05f,
                    twinklePhase = Random.nextFloat() * (Math.PI.toFloat() * 2f)
                )
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stars.clear()
        ensureStars()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            ensureStars()
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                updateStars()
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun updateStars() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        for (star in stars) {
            star.x += star.speedX
            star.y += star.speedY
            star.twinklePhase += star.twinkleSpeed

            if (star.y > h + star.size * 2) {
                star.y = -star.size * 2
                star.x = Random.nextFloat() * w
            }
            if (star.x < -star.size * 2) star.x = w + star.size
            if (star.x > w + star.size * 2) star.x = -star.size
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for ((index, star) in stars.withIndex()) {
            val twinkle = (sin(star.twinklePhase.toDouble()) * 0.5 + 0.5).toFloat()
            val alpha = (star.alpha * (0.3f + twinkle * 0.7f)).toInt().coerceIn(30, 220)

            starPaint.color = starColors[index % starColors.size]
            starPaint.alpha = alpha
            starPaint.style = Paint.Style.FILL

            draw4PointedStar(canvas, star.x, star.y, star.size, star.size * 0.3f)
        }
    }

    private fun draw4PointedStar(canvas: Canvas, cx: Float, cy: Float, outerR: Float, innerR: Float) {
        starPath.reset()
        val points = 4
        val angleStep = Math.PI / points

        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outerR else innerR
            val angle = i * angleStep - Math.PI / 2
            val px = cx + (r * cos(angle)).toFloat()
            val py = cy + (r * sin(angle)).toFloat()
            if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
        }
        starPath.close()
        canvas.drawPath(starPath, starPaint)
    }
}
