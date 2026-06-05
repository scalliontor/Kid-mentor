package com.ctslab.kidmentor

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.runBlocking

class HomeProfileActivity : AppCompatActivity() {

    private data class Plan(
        val tier: String, val name: String, val quota: String, val price: String, val badge: String?
    )
    private lateinit var plans: List<Plan>
    private val tabIds = listOf(R.id.tabPlanBasic, R.id.tabPlanPro, R.id.tabPlanUltra)
    private var currentTier = "basic"
    private var selectedIndex = 1   // default highlight = Pro
    private var plansReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home_profile)

        TokenManager.init(this)
        ActiveChild.init(this)

        setupClickListeners()
        setupPlans()

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

        // Logout
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            if (TokenManager.isLoggedIn()) {
                TokenManager.clearTokens()
            }
            // Clear the active child so the next account doesn't inherit it as device_id.
            ActiveChild.clear()
            // First-login add-child prompt should fire again for the next account.
            getSharedPreferences("ptalk_student_info", MODE_PRIVATE).edit().clear().apply()
            val intent = Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        // Buy device
        findViewById<Button>(R.id.btnBuyDevice).setOnClickListener {
            Toast.makeText(this, getString(R.string.profile_upgrade_coming_soon), Toast.LENGTH_SHORT).show()
        }

        // Parent info + children management — logged-in only. Null-safe in case a
        // layout variant omits a card.
        val loggedIn = TokenManager.isLoggedIn()
        findViewById<androidx.cardview.widget.CardView?>(R.id.cardParentInfo)?.let { card ->
            if (loggedIn) card.setOnClickListener {
                startActivity(Intent(this, ParentProfileActivity::class.java))
            } else card.visibility = View.GONE
        }
        findViewById<androidx.cardview.widget.CardView?>(R.id.cardChildren)?.let { card ->
            if (loggedIn) card.setOnClickListener {
                startActivity(Intent(this, ChildrenActivity::class.java))
            } else card.visibility = View.GONE
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
        // The membership tier is NOT the JWT `user_type` claim (that's a role like
        // "account_owner"/"child"). It comes authoritatively from the server quota
        // (loadQuota → applyTier). Show a placeholder until that returns.
        applyTier("basic")
    }

    /** Set the tier badge from the real subscription tier + refresh the plan card. */
    private fun applyTier(tier: String) {
        currentTier = tier
        val label = when (tier) {
            "admin" -> getString(R.string.profile_tier_admin)
            "ultra" -> getString(R.string.profile_tier_ultra)
            "pro" -> getString(R.string.profile_tier_pro)
            else -> getString(R.string.profile_tier_basic)
        }
        findViewById<TextView>(R.id.tvProfileTier).text = label
        if (plansReady) {
            // Jump the selector to the user's own plan (admin → showcase Ultra).
            selectPlan(when (tier) { "pro" -> 1; "ultra", "admin" -> 2; else -> 0 })
        }
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
                    // Authoritative tier from server → drives the badge (fixes the bug
                    // where everyone showed "Thành viên miễn phí" from user_type).
                    applyTier(quota.tier)
                    val limit = quota.dailyLimit
                    val used = quota.usedToday
                    tvCount.text = if (limit == -1) "$used / ∞" else "$used / $limit"
                    val pct = if (limit > 0) (used * 100 / limit) else 0
                    progress.progress = pct.coerceIn(0, 100)
                    tvHint.text = when {
                        quota.tier == "admin" -> getString(R.string.profile_quota_hint_admin)
                        limit != -1 && used >= limit -> getString(R.string.profile_quota_exhausted)
                        quota.tier == "pro" -> getString(R.string.profile_quota_hint_pro)
                        else -> getString(R.string.profile_quota_hint_basic)
                    }
                } else {
                    tvCount.text = "—"
                    progress.progress = 0
                    tvHint.text = getString(R.string.profile_quota_hint_basic)
                }
            }
        }.start()
    }

    // ── Subscription plans (segmented selector + detail card) ─────────────
    private fun setupPlans() {
        plans = listOf(
            Plan("basic", getString(R.string.profile_plan_basic),
                getString(R.string.profile_plan_basic_quota),
                getString(R.string.profile_plan_basic_price), null),
            Plan("pro", getString(R.string.profile_plan_pro),
                getString(R.string.profile_plan_pro_quota),
                getString(R.string.profile_plan_pro_price),
                getString(R.string.profile_plan_badge_popular)),
            Plan("ultra", getString(R.string.profile_plan_ultra),
                getString(R.string.profile_plan_ultra_quota),
                getString(R.string.profile_plan_ultra_price), null),
        )
        tabIds.forEachIndexed { i, id ->
            findViewById<TextView>(id).setOnClickListener { selectPlan(i) }
        }
        plansReady = true
        selectPlan(selectedIndex)
    }

    /** Plans differ ONLY by daily quota; rank decides current/upgrade/lower. */
    private fun planRank(tier: String) = when (tier) {
        "pro" -> 1; "ultra" -> 2; "admin" -> 3; else -> 0
    }

    private fun selectPlan(index: Int) {
        if (!plansReady) return
        selectedIndex = index
        val plan = plans[index]

        // Segmented tab visuals (reuse the language-toggle pill drawables).
        tabIds.forEachIndexed { i, id ->
            val tv = findViewById<TextView>(id)
            if (i == index) {
                tv.setBackgroundResource(R.drawable.bg_lang_selected)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundResource(0)
                tv.setTextColor(Color.parseColor("#707072"))
            }
        }

        findViewById<TextView>(R.id.tvPlanName).text = plan.name
        findViewById<TextView>(R.id.tvPlanQuota).text = plan.quota
        findViewById<TextView>(R.id.tvPlanPrice).text = plan.price

        val badge = findViewById<TextView>(R.id.tvPlanBadge)
        val action = findViewById<Button>(R.id.btnPlanAction)
        val curRank = planRank(currentTier)
        val planR = planRank(plan.tier)

        when {
            planR == curRank -> {                       // the plan the user is on
                badge.text = getString(R.string.profile_plan_active)
                badge.visibility = View.VISIBLE
                action.text = getString(R.string.profile_plan_active)
                action.isEnabled = false
            }
            planR > curRank -> {                        // an upgrade
                if (plan.badge != null) {
                    badge.text = plan.badge
                    badge.visibility = View.VISIBLE
                } else badge.visibility = View.GONE
                action.text = getString(R.string.profile_btn_upgrade_fmt, plan.name)
                action.isEnabled = true
            }
            else -> {                                   // below the user's plan
                badge.visibility = View.GONE
                action.text = getString(R.string.profile_plan_have_higher)
                action.isEnabled = false
            }
        }
        action.setOnClickListener { if (action.isEnabled) showUpgradeDialog() }
    }

    /** No payment yet — point the user at the contact email. */
    private fun showUpgradeDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_upgrade_title)
            .setMessage(R.string.profile_upgrade_message)
            .setPositiveButton(R.string.profile_upgrade_send_email) { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ctslab@ptit.vn")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "Nâng cấp gói PTalk")
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "ctslab@ptit.vn", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.profile_close, null)
            .show()
    }

    override fun onBackPressed() {
        finish()
    }
}
