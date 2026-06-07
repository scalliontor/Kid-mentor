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
import kotlinx.coroutines.runBlocking

class HomeProfileActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_profile)

        TokenManager.init(this)
        ActiveChild.init(this)

        setupClickListeners()

        if (TokenManager.isLoggedIn()) {
            loadUserProfile()
            loadQuota()
        } else {
            // Guest mode
            loadGuestProfile()
        }
    }

    private fun setupClickListeners() {
        // Back button
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // Family info (read-only parent + children)
        findViewById<View>(R.id.cardFamilyInfo).setOnClickListener {
            startActivity(Intent(this, FamilyInfoActivity::class.java))
        }

        // Logout
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            if (TokenManager.isLoggedIn()) {
                TokenManager.clearTokens()
            }
            // Clear the active child so the next account doesn't inherit it as device_id.
            ActiveChild.clear()
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

    // ── Guest mode ───────────────────────────────────────────────────────
    private fun loadGuestProfile() {
        findViewById<TextView>(R.id.tvProfileUsername).text = "Khách"
        findViewById<TextView>(R.id.tvProfileTier).text = "Chế độ xem thử"

        // Read guest counter from SharedPreferences
        val prefs = getSharedPreferences("ptalk_guest", MODE_PRIVATE)
        val used = prefs.getInt("guest_request_count", 0)
        val limit = 20

        val tvCount = findViewById<TextView>(R.id.tvQuotaCount)
        val progress = findViewById<ProgressBar>(R.id.progressQuota)
        val tvHint = findViewById<TextView>(R.id.tvQuotaHint)

        tvCount.text = "$used / $limit"
        val pct = if (limit > 0) (used * 100 / limit) else 0
        progress.progress = pct.coerceIn(0, 100)
        tvHint.text = if (used >= limit)
            getString(R.string.profile_quota_exhausted)
        else
            "Đăng ký tài khoản để dùng nhiều hơn"

        // Hide upgrade buttons for guest (show register prompt instead)
        findViewById<Button>(R.id.btnLogout).text = "Đăng nhập / Đăng ký"
    }

    // ── Logged-in user ───────────────────────────────────────────────────
    private fun loadUserProfile() {
        val username = TokenManager.getUsername() ?: "User"
        findViewById<TextView>(R.id.tvProfileUsername).text = username

        // Render the tier from the locally-stored login value first so the screen is
        // never blank, then overwrite it with the DB source of truth from /api/v1/profile.
        renderTier(TokenManager.getUserType() ?: "basic", false)

        // The subscription tier is users.subscription_tier on the PARENT account, NOT the
        // stored login user_type / JWT. Resolve it from the profile API; on failure keep
        // the locally-stored value already rendered above.
        lifecycleScope.launch {
            val profile = ProfileApiService.getProfile()
            if (profile != null) {
                val tier = profile.subscriptionTier?.ifBlank { null } ?: "basic"
                renderTier(tier, profile.isSuperuser)
            }
        }
    }

    /** Paint the tier label + plan badges for the given tier (isSuperuser → "admin"). */
    private fun renderTier(tier: String, isSuperuser: Boolean) {
        val activeTier = if (isSuperuser) "admin" else tier
        val tierLabel = when (activeTier) {
            "admin" -> getString(R.string.profile_tier_admin)
            "ultra" -> getString(R.string.profile_tier_ultra)
            "pro" -> getString(R.string.profile_tier_pro)
            else -> getString(R.string.profile_tier_basic)
        }
        findViewById<TextView>(R.id.tvProfileTier).text = tierLabel
        updatePlanBadges(activeTier, isSuperuser)
    }

    private fun loadQuota() {
        val tvCount = findViewById<TextView>(R.id.tvQuotaCount)
        val progress = findViewById<ProgressBar>(R.id.progressQuota)
        val tvHint = findViewById<TextView>(R.id.tvQuotaHint)

        val username = TokenManager.getUsername()
        if (username == null) {
            tvCount.text = "—"
            progress.progress = 0
            tvHint.text = getString(R.string.profile_quota_hint_basic)
            return
        }

        // Fetch quota from server
        Thread {
            val quota = runBlocking { AuthApiService.getQuota(username) }
            runOnUiThread {
                if (quota != null) {
                    val limit = quota.dailyLimit
                    val used = quota.usedToday
                    tvCount.text = if (limit == -1) "$used / ∞" else "$used / $limit"
                    val pct = if (limit > 0) (used * 100 / limit) else 0
                    progress.progress = pct.coerceIn(0, 100)
                    tvHint.text = if (limit != -1 && used >= limit)
                        getString(R.string.profile_quota_exhausted)
                    else
                        getString(R.string.profile_quota_hint_basic)
                } else {
                    tvCount.text = "—"
                    progress.progress = 0
                    tvHint.text = getString(R.string.profile_quota_hint_basic)
                }
            }
        }.start()
    }

    private fun updatePlanBadges(tier: String, isSuperuser: Boolean) {
        val badgeBasic = findViewById<TextView>(R.id.tvBasicBadge)
        val btnPro = findViewById<Button>(R.id.btnUpgradePro)
        val btnUltra = findViewById<Button>(R.id.btnUpgradeUltra)

        val activeTier = if (isSuperuser) "admin" else tier

        // Reset to the default "basic" state first so this is idempotent — it can be
        // called twice (stored fallback, then the DB tier) and must fully override.
        badgeBasic.visibility = View.VISIBLE
        btnPro.visibility = View.VISIBLE
        btnPro.text = getString(R.string.profile_btn_upgrade)
        btnPro.isEnabled = true
        btnUltra.text = getString(R.string.profile_btn_upgrade)
        btnUltra.isEnabled = true

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
                // basic — default state already applied above.
            }
        }
    }

    override fun onBackPressed() {
        finish()
    }
}
