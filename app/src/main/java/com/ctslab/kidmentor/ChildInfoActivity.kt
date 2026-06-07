package com.ctslab.kidmentor

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Thông tin bé" — READ-ONLY detail of one child profile under the logged-in parent
 * (tên bé, lớp, ngày sinh, quê quán, bộ sách, quan hệ với bé). Fetched from Dashboard
 * /api/v1/children/{id} and displayed as plain text.
 *
 * The parent adds/edits/deletes children on the Dashboard, not in the app — there is no
 * form, no save, and no create flow here. grade + bộ sách drive RAG personalization once
 * the child is the active one ([ActiveChild], chosen in [ChildrenActivity]).
 */
class ChildInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHILD_ID = "extra_child_id"
        // curriculum / relationship codes → Vietnamese labels.
        private val CURRICULUM_CODES = listOf("chan_troi_sang_tao", "canh_dieu", "ket_noi_tri_thuc")
        private val RELATIONSHIP_CODES = listOf("father", "mother", "grandparent", "guardian", "other")
    }

    private lateinit var tvName: TextView
    private lateinit var tvGrade: TextView
    private lateinit var tvDob: TextView
    private lateinit var tvHometown: TextView
    private lateinit var tvCurriculum: TextView
    private lateinit var tvRelationship: TextView
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar

    private var childId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_info)

        TokenManager.init(this)
        ActiveChild.init(this)
        if (!TokenManager.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.student_login_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        childId = intent.getStringExtra(EXTRA_CHILD_ID)

        bindViews()
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvChildHeader).setText(R.string.child_header_edit)

        val id = childId
        if (id.isNullOrBlank()) {
            // Nothing to show without a child id — children are added on the Dashboard.
            showError(getString(R.string.student_load_error))
        } else {
            loadChild(id)
        }
    }

    private fun bindViews() {
        tvName = findViewById(R.id.tvChildName)
        tvGrade = findViewById(R.id.tvChildGrade)
        tvDob = findViewById(R.id.tvChildDob)
        tvHometown = findViewById(R.id.tvChildHometown)
        tvCurriculum = findViewById(R.id.tvChildCurriculum)
        tvRelationship = findViewById(R.id.tvChildRelationship)
        tvError = findViewById(R.id.tvChildError)
        progress = findViewById(R.id.progressChild)
    }

    private fun loadChild(id: String) {
        progress.visibility = View.VISIBLE
        hideError()
        lifecycleScope.launch {
            val c = ChildrenApiService.get(id)
            progress.visibility = View.GONE
            if (c != null) {
                tvName.text = c.fullName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.family_value_placeholder)
                tvGrade.text = displayGrade(c.grade)
                tvDob.text = displayDate(c.dateOfBirth)
                tvHometown.text = c.hometown?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.family_value_placeholder)
                tvCurriculum.text = displayCurriculum(c.curriculum)
                tvRelationship.text = displayRelationship(c.relationship)
            } else {
                showError(getString(R.string.student_load_error))
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private fun displayGrade(grade: String?): String {
        val value = grade?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        return getString(R.string.student_grade_fmt, value)
    }

    /** "YYYY-MM-DD" → "DD/MM/YYYY"; falls back to placeholder when blank/unparseable. */
    private fun displayDate(iso: String?): String {
        val value = iso?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        val p = value.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else value
    }

    private fun displayCurriculum(code: String?): String {
        val value = code?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        val idx = CURRICULUM_CODES.indexOf(value)
        return if (idx >= 0) resources.getStringArray(R.array.student_curriculum_options)[idx] else value
    }

    private fun displayRelationship(code: String?): String {
        val value = code?.takeIf { it.isNotBlank() } ?: return getString(R.string.family_value_placeholder)
        val idx = RELATIONSHIP_CODES.indexOf(value)
        return if (idx >= 0) resources.getStringArray(R.array.child_relationship_options)[idx] else value
    }

    private fun showError(message: String) { tvError.text = message; tvError.visibility = View.VISIBLE }
    private fun hideError() { tvError.visibility = View.GONE }
}
