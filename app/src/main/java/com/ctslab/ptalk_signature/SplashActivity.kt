package com.ctslab.ptalk_signature

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
                showTitles(logo, title, tvMadeBy, logoCts)
            }
        })

        flipAnim.start()
    }

    // ── Phase 3: Tiêu đề fade in + slide up ──────────────────────────────
    private fun showTitles(logo: View, title: View, tvMadeBy: View, logoCts: View) {
        title.translationY = 24f
        title.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setInterpolator(DecelerateInterpolator())
            .start()

        tvMadeBy.translationY = 16f
        tvMadeBy.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(130)
            .setInterpolator(DecelerateInterpolator())
            .start()

        logoCts.translationY = 16f
        logoCts.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(260)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                // ── Phase 4: Chờ 800ms rồi navigate ───────────────────
                logo.postDelayed({ navigateToMain() }, 800)
            }
            .start()
    }

    private fun navigateToMain() {
        val intent = Intent(this, ModeSelectActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
