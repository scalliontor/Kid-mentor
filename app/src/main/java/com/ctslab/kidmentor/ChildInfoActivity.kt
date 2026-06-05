package com.ctslab.kidmentor

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Create or edit a child profile ("Thông tin bé") under the logged-in parent:
 * tên bé, lớp, ngày sinh, quê quán, bộ sách, quan hệ với bé.
 *
 * Modes: create (no [EXTRA_CHILD_ID]) → POST /api/v1/children; edit → GET/PUT
 * /api/v1/children/{id}. [EXTRA_ONBOARDING] shows a "Để sau" skip (first-run add-a-child).
 * grade + bộ sách drive RAG personalization once the child is the active one.
 */
class ChildInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHILD_ID = "extra_child_id"
        const val EXTRA_ONBOARDING = "extra_onboarding"
        // Aligned 1:1 with the string-array resources.
        private val CURRICULUM_CODES = listOf("chan_troi_sang_tao", "canh_dieu", "ket_noi_tri_thuc")
        private val RELATIONSHIP_CODES = listOf("father", "mother", "grandparent", "guardian", "other")
    }

    private lateinit var etName: EditText
    private lateinit var etGrade: EditText
    private lateinit var etDob: EditText
    private lateinit var etHometown: EditText
    private lateinit var etCurriculum: EditText
    private lateinit var etRelationship: EditText
    private lateinit var btnSave: Button
    private lateinit var tvSkip: TextView
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar

    private var childId: String? = null
    private var onboarding = false
    private var selectedGrade: String? = null
    private var dobIso: String? = null
    private var selectedCurriculumIndex = -1
    private var selectedRelationshipIndex = -1
    private var saving = false

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
        onboarding = intent.getBooleanExtra(EXTRA_ONBOARDING, false)

        bindViews()
        setupListeners()
        if (onboarding) tvSkip.visibility = View.VISIBLE

        if (childId != null) {
            findViewById<TextView>(R.id.tvChildHeader).setText(R.string.child_header_edit)
            loadChild(childId!!)
        }
    }

    private fun bindViews() {
        etName = findViewById(R.id.etChildName)
        etGrade = findViewById(R.id.etChildGrade)
        etDob = findViewById(R.id.etChildDob)
        etHometown = findViewById(R.id.etChildHometown)
        etCurriculum = findViewById(R.id.etChildCurriculum)
        etRelationship = findViewById(R.id.etChildRelationship)
        btnSave = findViewById(R.id.btnChildSave)
        tvSkip = findViewById(R.id.tvChildSkip)
        tvError = findViewById(R.id.tvChildError)
        progress = findViewById(R.id.progressChild)
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        tvSkip.setOnClickListener { finish() }
        etGrade.setOnClickListener { showGradeDialog() }
        etDob.setOnClickListener { showDatePicker() }
        etCurriculum.setOnClickListener { showCurriculumDialog() }
        etRelationship.setOnClickListener { showRelationshipDialog() }
        btnSave.setOnClickListener { attemptSave() }
    }

    private fun loadChild(id: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val c = ChildrenApiService.get(id)
            progress.visibility = View.GONE
            if (c != null) {
                etName.setText(c.fullName ?: "")
                etHometown.setText(c.hometown ?: "")
                c.grade?.takeIf { it.isNotBlank() }?.let {
                    selectedGrade = it; etGrade.setText(getString(R.string.student_grade_fmt, it))
                }
                c.dateOfBirth?.takeIf { it.isNotBlank() }?.let {
                    dobIso = it; etDob.setText(isoToDisplay(it))
                }
                c.curriculum?.let { code ->
                    val idx = CURRICULUM_CODES.indexOf(code)
                    if (idx >= 0) { selectedCurriculumIndex = idx; etCurriculum.setText(curriculumOptions()[idx]) }
                }
                c.relationship?.let { code ->
                    val idx = RELATIONSHIP_CODES.indexOf(code)
                    if (idx >= 0) { selectedRelationshipIndex = idx; etRelationship.setText(relationshipOptions()[idx]) }
                }
            } else {
                showError(getString(R.string.student_load_error))
            }
        }
    }

    // ── Pickers ─────────────────────────────────────────────────────────────
    private fun showGradeDialog() {
        val items = (1..12).map { getString(R.string.student_grade_fmt, it.toString()) }.toTypedArray()
        val checked = selectedGrade?.toIntOrNull()?.let { it - 1 } ?: -1
        AlertDialog.Builder(this)
            .setTitle(R.string.student_grade_dialog_title)
            .setSingleChoiceItems(items, checked) { d, which ->
                selectedGrade = (which + 1).toString(); etGrade.setText(items[which]); d.dismiss()
            }.show()
    }

    private fun showCurriculumDialog() {
        val items = curriculumOptions()
        AlertDialog.Builder(this)
            .setTitle(R.string.student_curriculum_dialog_title)
            .setSingleChoiceItems(items, selectedCurriculumIndex) { d, which ->
                selectedCurriculumIndex = which; etCurriculum.setText(items[which]); d.dismiss()
            }.show()
    }

    private fun showRelationshipDialog() {
        val items = relationshipOptions()
        AlertDialog.Builder(this)
            .setTitle(R.string.child_relationship_dialog_title)
            .setSingleChoiceItems(items, selectedRelationshipIndex) { d, which ->
                selectedRelationshipIndex = which; etRelationship.setText(items[which]); d.dismiss()
            }.show()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        dobIso?.split("-")?.takeIf { it.size == 3 }?.let { p ->
            p[0].toIntOrNull()?.let { y -> p[1].toIntOrNull()?.let { m -> p[2].toIntOrNull()?.let { d -> cal.set(y, m - 1, d) } } }
        } ?: cal.add(Calendar.YEAR, -10)
        val dlg = DatePickerDialog(this, { _, year, month, day ->
            dobIso = String.format("%04d-%02d-%02d", year, month + 1, day)
            etDob.setText(isoToDisplay(dobIso!!))
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
        dlg.datePicker.maxDate = System.currentTimeMillis()
        dlg.show()
    }

    // ── Save ──────────────────────────────────────────────────────────────────
    private fun attemptSave() {
        if (saving) return
        val name = etName.text.toString().trim()
        if (name.isEmpty()) { showError(getString(R.string.child_err_name)); return }
        hideError()
        setSaving(true)

        val body = ChildProfile(
            fullName = name,
            grade = selectedGrade ?: "",
            dateOfBirth = dobIso ?: "",
            hometown = etHometown.text.toString().trim(),
            curriculum = selectedCurriculumIndex.takeIf { it >= 0 }?.let { CURRICULUM_CODES[it] } ?: "",
            relationship = selectedRelationshipIndex.takeIf { it >= 0 }?.let { RELATIONSHIP_CODES[it] }
        )

        lifecycleScope.launch {
            val result = if (childId == null) ChildrenApiService.create(body)
            else ChildrenApiService.update(childId!!, body)

            when (result) {
                is ChildrenApiService.Result.Success -> {
                    // Make a newly-created child the active one if none chosen yet (or onboarding).
                    val saved = result.child
                    if (childId == null && saved?.id != null && saved.username != null &&
                        (onboarding || !ActiveChild.isSet())) {
                        ActiveChild.set(saved.id, saved.username, saved.fullName ?: name)
                    } else if (childId != null && ActiveChild.getId() == childId) {
                        // keep the active child's cached name fresh after an edit
                        ActiveChild.set(childId!!, ActiveChild.getUsername() ?: "", name)
                    }
                    Toast.makeText(this@ChildInfoActivity, getString(R.string.student_saved), Toast.LENGTH_SHORT).show()
                    finish()
                }
                is ChildrenApiService.Result.Error -> {
                    setSaving(false); showError(getString(R.string.student_save_error))
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private fun curriculumOptions() = resources.getStringArray(R.array.student_curriculum_options)
    private fun relationshipOptions() = resources.getStringArray(R.array.child_relationship_options)

    private fun isoToDisplay(iso: String): String {
        val p = iso.split("-")
        return if (p.size == 3) "${p[2]}/${p[1]}/${p[0]}" else iso
    }

    private fun setSaving(value: Boolean) {
        saving = value
        btnSave.isEnabled = !value
        btnSave.text = if (value) getString(R.string.student_saving) else getString(R.string.child_save)
        progress.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) { tvError.text = message; tvError.visibility = View.VISIBLE }
    private fun hideError() { tvError.visibility = View.GONE }
}
