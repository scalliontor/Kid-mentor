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

/**
 * Standalone subscription / plans screen ("Gói Đăng Ký").
 *
 * The three plans differ only by daily question quota. The user's current tier is
 * resolved from the Authentik JWT access-token claims (subscription_tier /
 * is_superuser); if the user is a guest or the claim is absent it falls back to the
 * free "Cơ Bản" tier. Upgrades route to a contact email — there is no payment flow yet.
 */
class SubscriptionActivity : AppCompatActivity() {

    private data class Plan(
        val tier: String, val name: String, val quota: String, val price: String, val badge: String?
    )

    private lateinit var plans: List<Plan>
    private val tabIds = listOf(R.id.tabPlanBasic, R.id.tabPlanPro, R.id.tabPlanUltra)
    private var appMode: AppMode = AppMode.KID_MENTOR
    private var currentTier = "basic"   // resolved from the signed-in user's JWT
    private var selectedIndex = 1       // showcase Pro first

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscription)

        TokenManager.init(this)
        currentTier = resolveTier(TokenManager.getAccessToken())
        selectedIndex = when (currentTier) { "pro" -> 1; "ultra", "admin" -> 2; else -> 1 }

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
        selectPlan(selectedIndex)
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
                    putExtra(Intent.EXTRA_SUBJECT, "Nâng cấp gói P-Talk Signature")
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

    /**
     * Resolve the subscription tier from the JWT access-token claims
     * (subscription_tier / is_superuser). Any decode failure or missing token
     * (e.g. guest mode) falls back to the free tier.
     */
    private fun resolveTier(token: String?): String {
        if (token.isNullOrBlank()) return "basic"
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return "basic"
            val payload = String(
                android.util.Base64.decode(
                    parts[1],
                    android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
                )
            )
            val json = org.json.JSONObject(payload)
            if (json.optBoolean("is_superuser", false)) "admin"
            else json.optString("subscription_tier", "basic").ifBlank { "basic" }
        } catch (e: Exception) {
            "basic"
        }
    }
}
