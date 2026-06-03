package com.ctslab.kidmentor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A particle-burst view that shoots 4-pointed stars outward from a given
 * center point, applies gravity so they fall naturally, and fades/shrinks
 * them until they disappear.
 *
 * Usage:
 *   starBurstView.burst(centerX, centerY)   // coordinates relative to this view
 */
class StarBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Particle data class ────────────────────────────────────────────
    private data class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,          // px per frame
        var vy: Float,          // px per frame
        var size: Float,        // outer radius in px
        var alpha: Float,       // 0..1
        var rotation: Float,    // current rotation degrees
        var rotationSpeed: Float,
        var color: Int,
        var life: Float,        // remaining life 1..0
        var lifeDecay: Float    // how fast life decreases per frame
    )

    // ── Configuration ──────────────────────────────────────────────────
    companion object {
        private const val PARTICLE_COUNT = 28
        private const val GRAVITY = 0.35f          // px/frame² (natural fall)
        private const val FRAME_MS = 16L           // ~60 fps
    }

    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val starPath = Path()

    private val particleColors = intArrayOf(
        0xFF80CBC4.toInt(),  // Mint pastel
        0xFF6BAF8A.toInt(),  // Sage xanh pastel
        0xFFF5A672.toInt(),  // Cam đào pastel
        0xFFE88B8B.toInt(),  // Hồng coral pastel
        0xFFC8B6E2.toInt(),  // Tím lavender pastel
        0xFF81D4FA.toInt(),  // Xanh trời pastel
        0xFFA5D6A7.toInt(),  // Xanh lá nhạt pastel
    )

    private var isAnimating = false
    private val density = resources.displayMetrics.density

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Trigger a burst of stars from the given center point.
     * Coordinates are relative to this view's coordinate space.
     */
    fun burst(centerX: Float, centerY: Float) {
        particles.clear()

        for (i in 0 until PARTICLE_COUNT) {
            // Random angle across full 360°
            val angle = Random.nextFloat() * (Math.PI * 2.0)
            // Random speed — some fast, some slow for natural spread
            val speed = (4f + Random.nextFloat() * 12f) * density

            // Bias upward slightly: subtract from vy so more stars go up first
            val upwardBias = -2f * density

            val vx = (cos(angle) * speed).toFloat()
            val vy = (sin(angle) * speed).toFloat() + upwardBias

            val size = (4f + Random.nextFloat() * 10f) * density
            val life = 0.7f + Random.nextFloat() * 0.3f  // 0.7–1.0
            val lifeDecay = 0.012f + Random.nextFloat() * 0.008f  // varied decay

            particles.add(
                Particle(
                    x = centerX,
                    y = centerY,
                    vx = vx,
                    vy = vy,
                    size = size,
                    alpha = 1f,
                    rotation = Random.nextFloat() * 360f,
                    rotationSpeed = (Random.nextFloat() - 0.5f) * 15f,  // -7.5..+7.5 deg/frame
                    color = particleColors[Random.nextInt(particleColors.size)],
                    life = life,
                    lifeDecay = lifeDecay
                )
            )
        }

        isAnimating = true
        invalidate()
    }

    // ── Animation loop ─────────────────────────────────────────────────

    private fun updateParticles() {
        val iterator = particles.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()

            // Physics
            p.x += p.vx
            p.y += p.vy
            p.vy += GRAVITY * density * 0.12f  // Gravity pull
            p.vx *= 0.985f                     // Air resistance

            // Life decay
            p.life -= p.lifeDecay
            p.alpha = p.life.coerceIn(0f, 1f)
            p.rotation += p.rotationSpeed

            // Shrink as life decreases
            p.size *= (0.985f + p.life * 0.01f)

            // Remove dead particles
            if (p.life <= 0f || p.alpha <= 0.01f || p.size < 1f) {
                iterator.remove()
            }
        }

        if (particles.isEmpty()) {
            isAnimating = false
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isAnimating || particles.isEmpty()) return

        for (p in particles) {
            paint.color = p.color
            paint.alpha = (p.alpha * 255).toInt().coerceIn(0, 255)

            canvas.save()
            canvas.translate(p.x, p.y)
            canvas.rotate(p.rotation)

            draw4PointedStar(canvas, 0f, 0f, p.size, p.size * 0.3f)

            canvas.restore()
        }

        // Schedule next frame
        updateParticles()
        if (isAnimating) {
            postInvalidateDelayed(FRAME_MS)
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
        canvas.drawPath(starPath, paint)
    }
}
