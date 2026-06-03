package com.ctslab.kidmentor

import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.buivan.ptalk_child.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        startSplashAnimation()
    }

    private fun startSplashAnimation() {
        val logo     = binding.ivSplashLogo
        val title    = binding.tvSplashTitle
        val tvMadeBy = binding.tvMadeBy
        val logoCts  = binding.ivSplashLogoCts

        // ── Phase 1: Fade in + scale up với overshoot ─────────────────────
        logo.scaleX = 0.3f
        logo.scaleY = 0.3f
        logo.alpha  = 0f

        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setStartDelay(200)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withEndAction { startFlip3D(logo, title, tvMadeBy, logoCts) }
            .start()
    }

    // ── Phase 2: 3D horizontal flip (rotationY 0° → 360°) ─────────────────
    private fun startFlip3D(
        logo: View,
        title: View,
        tvMadeBy: View,
        logoCts: View
    ) {
        // Hardware layer để GPU render mượt 3D flip
        logo.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val flipAnim = ObjectAnimator.ofFloat(logo, "rotationY", 0f, 360f).apply {
            duration     = 800
            interpolator = AccelerateDecelerateInterpolator()
        }

        flipAnim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                logo.setLayerType(View.LAYER_TYPE_NONE, null)
                logo.rotationY = 0f
                showTitles(logo, binding.tvSplashTitle, binding.tvMadeBy, binding.ivSplashLogoCts)
            }
        })

        flipAnim.start()
    }

    // ── Phase 3: Tiêu đề fade in + slide up + lắc lắc + NỔ SAO PHÁO HOA ──
    private fun showTitles(logo: View, title: android.widget.TextView, tvMadeBy: View, logoCts: View) {
        // Áp dụng gradient màu pastel dịu dàng cho chữ KID MENTOR
        title.post {
            val paint = title.paint
            val width = paint.measureText(title.text.toString())
            val textShader = android.graphics.LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(
                    android.graphics.Color.parseColor("#6BAF8A"), // Xanh sage pastel
                    android.graphics.Color.parseColor("#F5A672"), // Cam đào pastel
                    android.graphics.Color.parseColor("#E88B8B")  // Hồng coral pastel
                ),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
            title.paint.shader = textShader
            title.invalidate()
        }

        title.translationY = 24f
        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // Sau khi chữ hiện lên → chạy hiệu ứng "lắc lắc" ngộ nghĩnh kiểu hoạt họa
                title.animate()
                    .translationX(-16f).setDuration(80)
                    .withEndAction {
                        title.animate().translationX(16f).setDuration(80)
                            .withEndAction {
                                title.animate().translationX(-12f).setDuration(70)
                                    .withEndAction {
                                        title.animate().translationX(12f).setDuration(70)
                                            .withEndAction {
                                                title.animate().translationX(0f).setDuration(60)
                                                    .start()
                                            }.start()
                                    }.start()
                            }.start()
                    }.start()

                // ★★★ Cùng lúc lắc chữ → BẮN PHÁO HOA NGÔI SAO từ tâm chữ ★★★
                val starBurst = binding.starBurstView
                // Tính toạ độ tâm chữ KID MENTOR trong hệ toạ độ của StarBurstView
                val titleLocation = IntArray(2)
                val burstLocation = IntArray(2)
                title.getLocationInWindow(titleLocation)
                starBurst.getLocationInWindow(burstLocation)

                val centerX = (titleLocation[0] - burstLocation[0]) + title.width / 2f
                val centerY = (titleLocation[1] - burstLocation[1]) + title.height / 2f

                starBurst.burst(centerX, centerY)

                // Đợi hiệu ứng lắc và nổ sao hoàn thành (khoảng 550ms) rồi mới hiện logo CTS
                title.postDelayed({
                    tvMadeBy.translationY = 16f
                    tvMadeBy.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(400)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                    logoCts.translationY = 16f
                    logoCts.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(400)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            // Chờ 1.2s nữa để bé kịp thấy logo rồi mới chuyển màn hình
                            logo.postDelayed({ navigateToMain() }, 1200)
                        }
                        .start()
                }, 550)
            }
            .start()
    }


    private fun navigateToMain() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
