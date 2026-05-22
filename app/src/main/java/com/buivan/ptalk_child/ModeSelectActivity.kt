package com.buivan.ptalk_child

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.buivan.ptalk_child.databinding.ActivityModeSelectBinding

/**
 * Màn hình chọn chế độ: Kid Mentor hoặc Elder Care.
 * Được hiển thị sau SplashActivity, trước LoginActivity.
 */
class ModeSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModeSelectBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModeSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animateEntrance()
        setupCards()
    }

    private fun setupCards() {
        binding.cardKidMentor.setOnClickListener {
            selectMode(AppMode.KID_MENTOR)
        }

        binding.cardElderCare.setOnClickListener {
            selectMode(AppMode.ELDER_CARE)
        }
    }

    private fun selectMode(mode: AppMode) {
        // Lưu mode vào ServerConfig (runtime global)
        ServerConfig.activeMode = mode

        // Animate the selected card
        val selectedCard = when (mode) {
            AppMode.KID_MENTOR -> binding.cardKidMentor
            AppMode.ELDER_CARE -> binding.cardElderCare
        }

        selectedCard.animate()
            .scaleX(0.95f).scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                selectedCard.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(100)
                    .withEndAction { navigateToLogin(mode) }
                    .start()
            }
            .start()
    }

    private fun navigateToLogin(mode: AppMode) {
        val intent = Intent(this, LoginActivity::class.java).apply {
            putExtra(EXTRA_APP_MODE, mode.name)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun animateEntrance() {
        val cards = listOf(binding.cardKidMentor, binding.cardElderCare)

        // Start invisible + offset
        cards.forEachIndexed { index, card ->
            card.alpha = 0f
            card.translationY = 60f
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay((index * 120).toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Header + headline
        binding.tvModeHeadline.alpha = 0f
        binding.tvModeHeadline.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(50)
            .start()

        binding.tvModeSubheadline.alpha = 0f
        binding.tvModeSubheadline.animate()
            .alpha(1f)
            .setDuration(400)
            .setStartDelay(150)
            .start()
    }

    companion object {
        const val EXTRA_APP_MODE = "extra_app_mode"
    }
}
