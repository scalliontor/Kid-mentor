package com.buivan.ptalk_child

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class HomeProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_profile)

        TokenManager.init(this)

        setupClickListeners()
        loadUserProfile()
        loadQuota()
    }

    private fun setupClickListeners() {
        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Logout
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            TokenManager.clearTokens()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        // Upgrade Pro
        findViewById<Button>(R.id.btnUpgradePro).setOnClickListener {
            Toast.makeText(this, getString(R.string.profile_upgrade_coming_soon), Toast.LENGTH_SHORT).show()
        }

        // Upgrade Ultra
        findViewById<Button>(R.id.btnUpgradeUltra).setOnClickListener {
            Toast.makeText(this, getString(R.string.profile_upgrade_coming_soon), Toast.LENGTH_SHORT).show()
        }

        // Buy device
        findViewById<Button>(R.id.btnBuyDevice).setOnClickListener {
            Toast.makeText(this, getString(R.string.profile_upgrade_coming_soon), Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUserProfile() {
        val username = TokenManager.getUsername() ?: "User"
        findViewById<TextView>(R.id.tvProfileUsername).text = username

        val accessToken = TokenManager.getAccessToken() ?: return

        lifecycleScope.launch {
            when (val result = AuthApiService.getMe(accessToken)) {
                is AuthApiService.AuthResult.Success -> {
                    val user = result.data
                    findViewById<TextView>(R.id.tvProfileUsername).text =
                        user.displayName ?: user.username

                    // Update tier label
                    val tierLabel = when {
                        user.isSuperuser -> getString(R.string.profile_tier_admin)
                        user.subscriptionTier == "ultra" -> getString(R.string.profile_tier_ultra)
                        user.subscriptionTier == "pro" -> getString(R.string.profile_tier_pro)
                        else -> getString(R.string.profile_tier_basic)
                    }
                    findViewById<TextView>(R.id.tvProfileTier).text = tierLabel

                    // Update plan badges
                    updatePlanBadges(user.subscriptionTier, user.isSuperuser)
                }
                is AuthApiService.AuthResult.Error -> {
                    // Token expired or invalid — still show cached username
                }
            }
        }
    }

    private fun loadQuota() {
        val accessToken = TokenManager.getAccessToken() ?: return

        lifecycleScope.launch {
            when (val result = AuthApiService.getQuota(accessToken)) {
                is AuthApiService.AuthResult.Success -> {
                    val quota = result.data
                    val tvCount = findViewById<TextView>(R.id.tvQuotaCount)
                    val progress = findViewById<ProgressBar>(R.id.progressQuota)
                    val tvHint = findViewById<TextView>(R.id.tvQuotaHint)

                    if (quota.dailyLimit == -1) {
                        // Unlimited
                        tvCount.text = getString(R.string.profile_quota_unlimited)
                        progress.progress = 0
                        tvHint.text = if (quota.isAdmin)
                            getString(R.string.profile_quota_hint_admin)
                        else
                            "Gói Ultra — không giới hạn"
                    } else {
                        tvCount.text = "${quota.usedToday} / ${quota.dailyLimit}"
                        val pct = if (quota.dailyLimit > 0) (quota.usedToday * 100 / quota.dailyLimit) else 0
                        progress.progress = pct.coerceIn(0, 100)

                        tvHint.text = if (quota.remaining <= 0)
                            getString(R.string.profile_quota_exhausted)
                        else
                            getString(R.string.profile_quota_hint_basic)
                    }
                }
                is AuthApiService.AuthResult.Error -> {
                    // Can't load quota — show default
                }
            }
        }
    }

    private fun updatePlanBadges(tier: String, isSuperuser: Boolean) {
        val badgeBasic = findViewById<TextView>(R.id.tvBasicBadge)
        val btnPro = findViewById<Button>(R.id.btnUpgradePro)
        val btnUltra = findViewById<Button>(R.id.btnUpgradeUltra)

        val activeTier = if (isSuperuser) "admin" else tier

        when (activeTier) {
            "admin", "ultra" -> {
                badgeBasic.visibility = View.GONE
                btnPro.visibility = View.GONE
                btnUltra.text = getString(R.string.profile_plan_active)
                btnUltra.isEnabled = false
            }
            "pro" -> {
                badgeBasic.visibility = View.GONE
                btnPro.text = getString(R.string.profile_plan_active)
                btnPro.isEnabled = false
            }
            else -> {
                // basic — default state, all buttons active
            }
        }
    }

    override fun onBackPressed() {
        finish()
    }
}
