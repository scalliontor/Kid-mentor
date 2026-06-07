package com.buivan.ptalk_child

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Thông tin gia đình" — READ-ONLY view of the logged-in parent's own profile
 * (/api/v1/profile) and their children (/api/v1/children), fetched from the Dashboard.
 *
 * No editing: every value is shown as plain text. There is no EditText, no save button.
 * Mirrors the Dashboard data shape via [ProfileApiService] / [ChildrenApiService].
 */
class FamilyInfoActivity : AppCompatActivity() {

    companion object {
        // curriculum codes → Vietnamese labels (kept in code to avoid extra string-array resources)
        private val CURRICULUM_LABELS = mapOf(
            "chan_troi_sang_tao" to "Chân trời sáng tạo",
            "canh_dieu" to "Cánh diều",
            "ket_noi_tri_thuc" to "Kết nối tri thức"
        )
    }

    private lateinit var tvParentName: TextView
    private lateinit var tvParentDob: TextView
    private lateinit var tvParentPhone: TextView
    private lateinit var tvParentEmail: TextView
    private lateinit var containerChildren: LinearLayout
    private lateinit var tvChildrenEmpty: TextView
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_family_info)

        TokenManager.init(this)
        if (!TokenManager.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.family_login_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindViews()
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // Pre-fill the account email from the stored token while the network call runs.
        tvParentEmail.text = TokenManager.getEmail() ?: getString(R.string.family_value_placeholder)

        load()
    }

    private fun bindViews() {
        tvParentName = findViewById(R.id.tvParentName)
        tvParentDob = findViewById(R.id.tvParentDob)
        tvParentPhone = findViewById(R.id.tvParentPhone)
        tvParentEmail = findViewById(R.id.tvParentEmail)
        containerChildren = findViewById(R.id.containerChildren)
        tvChildrenEmpty = findViewById(R.id.tvChildrenEmpty)
        tvError = findViewById(R.id.tvFamilyError)
        progress = findViewById(R.id.progressFamily)
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        hideError()
        lifecycleScope.launch {
            val profile = ProfileApiService.getProfile()
            val children = ChildrenApiService.list()
            progress.visibility = View.GONE

            bindParent(profile)
            bindChildren(children)

            if (profile == null && children == null) {
                showError(getString(R.string.family_load_error))
            }
        }
    }

    // ── Parent ──────────────────────────────────────────────────────────────
    private fun bindParent(p: ParentProfile?) {
        val name = p?.fullName?.takeIf { it.isNotBlank() }
            ?: p?.displayName?.takeIf { it.isNotBlank() }
            ?: p?.username?.takeIf { it.isNotBlank() }
        tvParentName.text = name ?: getString(R.string.family_value_placeholder)
        tvParentDob.text = displayDate(p?.dateOfBirth)
        tvParentPhone.text = p?.phone?.takeIf { it.isNotBlank() }
            ?: getString(R.string.family_value_placeholder)
        val email = p?.email?.takeIf { it.isNotBlank() } ?: TokenManager.getEmail()
        tvParentEmail.text = email?.takeIf { it.isNotBlank() }
            ?: getString(R.string.family_value_placeholder)
    }

    // ── Children ────────────────────────────────────────────────────────────
    private fun bindChildren(children: List<ChildProfile>?) {
        containerChildren.removeAllViews()
        if (children.isNullOrEmpty()) {
            tvChildrenEmpty.visibility = View.VISIBLE
            return
        }
        tvChildrenEmpty.visibility = View.GONE
        val inflater = LayoutInflater.from(this)
        for (child in children) {
            val card = inflater.inflate(R.layout.item_family_child, containerChildren, false)
            card.findViewById<TextView>(R.id.tvChildName).text =
                child.fullName?.takeIf { it.isNotBlank() } ?: getString(R.string.family_value_placeholder)
            card.findViewById<TextView>(R.id.tvChildDob).text = displayDate(child.dateOfBirth)
            card.findViewById<TextView>(R.id.tvChildGrade).text = displayGrade(child.grade)
            card.findViewById<TextView>(R.id.tvChildCurriculum).text = displayCurriculum(child.curriculum)
            containerChildren.addView(card)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    /** "YYYY-MM-DD" → "DD/MM/YYYY"; falls back to placeholder when blank/unparseable. */
    private fun displayDate(iso: String?): String {
        val value = iso?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        val parts = value.split("-")
        return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else value
    }

    private fun displayGrade(grade: String?): String {
        val value = grade?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        return getString(R.string.family_grade_fmt, value)
    }

    private fun displayCurriculum(code: String?): String {
        val value = code?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        return CURRICULUM_LABELS[value] ?: value
    }

    private fun showError(message: String) {
        tvError.text = message
        tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        tvError.visibility = View.GONE
    }
}
