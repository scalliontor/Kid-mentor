package com.ctslab.ptalk_signature

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ctslab.ptalk_signature.databinding.ActivityParentChildInfoBinding
import kotlinx.coroutines.launch

/**
 * READ-ONLY "Thông tin tài khoản" — shows the logged-in parent's profile and their
 * children, fetched from the Dashboard (/api/v1/profile + /api/v1/children). Shown ONLY
 * in KID_MENTOR mode (ELDER_CARE has no user info). No editing: every value is a TextView,
 * there is no save button.
 */
class ParentChildInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParentChildInfoBinding

    companion object {
        // Aligned 1:1 with the info_curriculum_options string-array.
        private val CURRICULUM_CODES = listOf("chan_troi_sang_tao", "canh_dieu", "ket_noi_tri_thuc")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityParentChildInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)
        if (!TokenManager.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.info_login_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnInfoBack.setOnClickListener { finish() }

        // Show the account email immediately from the cached token; the network load fills the rest.
        binding.tvParentEmailValue.text = TokenManager.getEmail()?.takeIf { it.isNotBlank() } ?: dash()

        load()
    }

    private fun load() {
        binding.progressInfo.visibility = View.VISIBLE
        binding.tvInfoError.visibility = View.GONE
        lifecycleScope.launch {
            val profile = ProfileApiService.getProfile()
            val children = ChildrenApiService.list()
            binding.progressInfo.visibility = View.GONE

            bindParent(profile)
            bindChildren(children)

            // Surface a soft error only when BOTH calls failed (network/auth issue).
            if (profile == null && children == null) {
                binding.tvInfoError.text = getString(R.string.info_load_error)
                binding.tvInfoError.visibility = View.VISIBLE
            }
        }
    }

    // ── Parent ──────────────────────────────────────────────────────────────────
    private fun bindParent(p: ParentProfile?) {
        binding.tvParentNameValue.text =
            p?.fullName?.takeIf { it.isNotBlank() }
                ?: p?.displayName?.takeIf { it.isNotBlank() }
                ?: dash()
        binding.tvParentDobValue.text = isoToDisplay(p?.dateOfBirth)
        binding.tvParentPhoneValue.text = p?.phone?.takeIf { it.isNotBlank() } ?: dash()
        // Prefer the email from the profile; fall back to the cached token email already shown.
        p?.email?.takeIf { it.isNotBlank() }?.let { binding.tvParentEmailValue.text = it }
    }

    // ── Children ──────────────────────────────────────────────────────────────────
    private fun bindChildren(children: List<ChildProfile>?) {
        val container = binding.containerChildren
        container.removeAllViews()

        if (children.isNullOrEmpty()) {
            binding.tvChildrenEmpty.visibility = View.VISIBLE
            return
        }
        binding.tvChildrenEmpty.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        children.forEach { child ->
            val card = inflater.inflate(R.layout.item_child_info_readonly, container, false)
            card.findViewById<TextView>(R.id.tvChildNameValue).text =
                child.fullName?.takeIf { it.isNotBlank() } ?: dash()
            card.findViewById<TextView>(R.id.tvChildDobValue).text = isoToDisplay(child.dateOfBirth)
            card.findViewById<TextView>(R.id.tvChildGradeValue).text =
                child.grade?.takeIf { it.isNotBlank() }?.let { getString(R.string.info_grade_fmt, it) } ?: dash()
            card.findViewById<TextView>(R.id.tvChildCurriculumValue).text = curriculumLabel(child.curriculum)
            container.addView(card)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────
    private fun curriculumLabel(code: String?): String {
        val idx = CURRICULUM_CODES.indexOf(code)
        return if (idx >= 0) resources.getStringArray(R.array.info_curriculum_options)[idx] else dash()
    }

    /** "YYYY-MM-DD" → "DD/MM/YYYY"; blank/invalid → em dash. */
    private fun isoToDisplay(iso: String?): String {
        if (iso.isNullOrBlank()) return dash()
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
    }

    private fun dash() = getString(R.string.info_value_empty)
}
