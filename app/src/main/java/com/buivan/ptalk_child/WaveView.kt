package com.buivan.ptalk_child

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * A subtle animated wave background for Elder Care mode.
 * Draws multiple overlapping sine waves that ripple gently,
 * evoking a calm, soothing water surface.
 */
class WaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class WaveLayer(
        val color: Int,
        val alpha: Int,
        val amplitude: Float,
        val wavelength: Float,
        val speed: Float,
        val verticalOffset: Float
    )

    private val waveLayers = listOf(
        WaveLayer(0xFFFFCC80.toInt(), 60, 20f, 360f, 0.018f, 0.30f),   // warm peach
        WaveLayer(0xFFFFAB91.toInt(), 50, 16f, 280f, 0.024f, 0.38f),   // soft coral
        WaveLayer(0xFFFFE0B2.toInt(), 70, 24f, 420f, 0.014f, 0.22f),   // light orange
        WaveLayer(0xFFFFF3E0.toInt(), 45, 12f, 200f, 0.030f, 0.46f),   // cream
        WaveLayer(0xFFFFCCBC.toInt(), 55, 18f, 320f, 0.020f, 0.34f),   // blush
        WaveLayer(0xFFFFE082.toInt(), 40, 14f, 380f, 0.016f, 0.52f),   // amber light
    )

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePath = Path()
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
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
            duration = 20_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase += 0.03f
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val density = resources.displayMetrics.density

        for ((index, layer) in waveLayers.withIndex()) {
            wavePaint.color = layer.color
            wavePaint.alpha = layer.alpha
            wavePaint.style = Paint.Style.FILL

            val amp = layer.amplitude * density
            val wl = layer.wavelength * density
            val baseY = h * (1f - layer.verticalOffset)
            val phaseOffset = phase * layer.speed * 60f + index * 1.2f

            wavePath.reset()
            wavePath.moveTo(0f, h)

            var x = 0f
            while (x <= w) {
                val y = baseY + amp * sin((x / wl * Math.PI * 2 + phaseOffset).toDouble()).toFloat()
                wavePath.lineTo(x, y)
                x += 4f
            }

            wavePath.lineTo(w, h)
            wavePath.close()

            canvas.drawPath(wavePath, wavePaint)
        }
    }
}
