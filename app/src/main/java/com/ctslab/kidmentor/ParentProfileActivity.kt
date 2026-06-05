package com.ctslab.kidmentor

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * "Thông tin phụ huynh" — the account owner's own profile (Họ tên, SĐT; email read-only).
 * Reads/writes Dashboard /api/v1/profile. Student info lives on children ([ChildrenActivity]).
 */
class ParentProfileActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etPhone: EditText
    private lateinit var etEmail: EditText
    private lateinit var btnSave: Button
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_profile)

        TokenManager.init(this)
        if (!TokenManager.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.student_login_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        etName = findViewById(R.id.etParentName)
        etPhone = findViewById(R.id.etParentPhone)
        etEmail = findViewById(R.id.etParentEmail)
        btnSave = findViewById(R.id.btnParentSave)
        tvError = findViewById(R.id.tvParentError)
        progress = findViewById(R.id.progressParent)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        btnSave.setOnClickListener { attemptSave() }

        etEmail.setText(TokenManager.getEmail() ?: "")
        loadProfile()
    }

    private fun loadProfile() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val p = ProfileApiService.getProfile()
            progress.visibility = View.GONE
            if (p != null) {
                etName.setText(p.fullName ?: "")
                etPhone.setText(p.phone ?: "")
                if (!p.email.isNullOrBlank()) etEmail.setText(p.email)
            }
        }
    }

    private fun attemptSave() {
        if (saving) return
        hideError()
        setSaving(true)
        val body = ParentProfile(
            fullName = etName.text.toString().trim(),
            phone = etPhone.text.toString().trim()
        )
        lifecycleScope.launch {
            when (ProfileApiService.updateProfile(body)) {
                is ProfileApiService.Result.Success -> {
                    Toast.makeText(this@ParentProfileActivity, getString(R.string.student_saved), Toast.LENGTH_SHORT).show()
                    finish()
                }
                is ProfileApiService.Result.Error -> {
                    setSaving(false); showError(getString(R.string.student_save_error))
                }
            }
        }
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
