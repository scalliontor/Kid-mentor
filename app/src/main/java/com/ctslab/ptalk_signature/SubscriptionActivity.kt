package com.ctslab.ptalk_signature

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Standalone subscription / plans screen ("Gói Đăng Ký").
 *
 * The three plans differ only by daily question quota. The user's current tier is the
 * DB source of truth — `users.subscription_tier` on the PARENT account, fetched from
 * /api/v1/profile (subscriptionTier / isSuperuser). The Authentik JWT carries NO
 * subscription_tier claim, so it can only be used as a guest/offline fallback to the
 * free "Cơ Bản" tier when the profile API call fails. Upgrades route to a contact
 * email — there is no payment flow yet.
 */
class SubscriptionActivity : AppCompatActivity() {

    private data class Plan(
        val tier: String, val name: String, val quota: String, val price: String, val badge: String?
    )

    private lateinit var plans: List<Plan>
    private val tabIds = listOf(R.id.tabPlanBasic, R.id.tabPlanPro, R.id.tabPlanUltra)
    private var appMode: AppMode = AppMode.KID_MENTOR
    private var currentTier = "basic"   // DB source of truth (/api/v1/profile); "basic" until resolved
    private var selectedIndex = 1       // showcase Pro first

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        TokenManager.init(this)

        val modeName = intent.getStringExtra(ModeSelectActivity.EXTRA_APP_MODE)
        appMode = modeName?.let { AppMode.valueOf(it) } ?: AppMode.KID_MENTOR
        if (appMode == AppMode.ELDER_CARE) {
            findViewById<View>(R.id.subRoot).setBackgroundResource(R.drawable.bg_gradient_eldercare)
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        plans = listOf(
            Plan("basic", getString(R.string.plan_basic),
                getString(R.string.plan_basic_quota), getString(R.string.plan_basic_price), null),
            Plan("pro", getString(R.string.plan_pro),
                getString(R.string.plan_pro_quota), getString(R.string.plan_pro_price),
                getString(R.string.plan_badge_popular)),
            Plan("ultra", getString(R.string.plan_ultra),
                getString(R.string.plan_ultra_quota), getString(R.string.plan_ultra_price), null),
        )
        tabIds.forEachIndexed { i, id ->
            findViewById<TextView>(id).setOnClickListener { selectPlan(i) }
        }

        // Render immediately with the fallback "basic" so the screen is never blank,
        // then resolve the real tier from the DB (/api/v1/profile) and re-evaluate.
        selectedIndex = 1
        selectPlan(selectedIndex)
        resolveTierAsync()
    }

    /**
     * Fetch the PARENT profile from /api/v1/profile and set [currentTier] from
     * profile.subscriptionTier (isSuperuser → "admin", the highest tier). The JWT has
     * no subscription_tier claim, so it is only a guest/offline fallback: if the API
     * call fails (or returns no tier) we keep "basic". After resolving, rebuild the
     * selected plan + re-evaluate which plan is "Đang dùng" vs upgradeable.
     */
    private fun resolveTierAsync() {
        lifecycleScope.launch {
            val profile = ProfileApiService.getProfile()
            val resolved = when {
                profile == null -> "basic"                       // API failed → guest/offline fallback
                profile.isSuperuser -> "admin"
                else -> profile.subscriptionTier?.ifBlank { null } ?: "basic"
            }
            currentTier = resolved
            // Pre-select the user's current tier (or showcase Pro if they're on basic).
            selectedIndex = when (currentTier) { "pro" -> 1; "ultra", "admin" -> 2; else -> 1 }
            selectPlan(selectedIndex)
        }
    }

    /** Mode-aware accent (green for Kid Mentor, orange for Elder Care). */
    private fun accentColor(): Int =
        if (appMode == AppMode.ELDER_CARE) Color.parseColor("#E67E22") else Color.parseColor("#2E7D52")

    private fun planRank(tier: String) = when (tier) {
        "pro" -> 1; "ultra" -> 2; "admin" -> 3; else -> 0
    }

    private fun selectPlan(index: Int) {
        selectedIndex = index
        val plan = plans[index]

        tabIds.forEachIndexed { i, id ->
            val tv = findViewById<TextView>(id)
            if (i == index) {
                tv.setBackgroundResource(R.drawable.bg_plan_selected)
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
            planR == curRank -> {
                badge.text = getString(R.string.plan_active)
                badge.visibility = View.VISIBLE
                action.text = getString(R.string.plan_active)
                action.isEnabled = false
            }
            planR > curRank -> {
                if (plan.badge != null) {
                    badge.text = plan.badge
                    badge.visibility = View.VISIBLE
                } else badge.visibility = View.GONE
                action.text = getString(R.string.plan_btn_upgrade_fmt, plan.name)
                action.isEnabled = true
            }
            else -> {
                badge.visibility = View.GONE
                action.text = getString(R.string.plan_have_higher)
                action.isEnabled = false
            }
        }
        action.backgroundTintList = ColorStateList.valueOf(
            if (action.isEnabled) accentColor() else Color.parseColor("#CCCCCC")
        )
        action.setTextColor(if (action.isEnabled) Color.WHITE else Color.parseColor("#888888"))
        action.setOnClickListener { if (action.isEnabled) showUpgradeDialog() }
    }

    /** No payment yet — point the user at the contact email. */
    private fun showUpgradeDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.plan_upgrade_title)
            .setMessage(R.string.plan_upgrade_message)
            .setPositiveButton(R.string.plan_upgrade_send_email) { _, _ ->
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:ctslab@ptit.vn")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.sub_email_subject))
                }
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "ctslab@ptit.vn", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.plan_close, null)
            .show()
    }
}
