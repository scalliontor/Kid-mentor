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
 * "Thông tin phụ huynh" — READ-ONLY view of the account owner's own profile
 * (Họ tên, SĐT, Email), fetched from Dashboard /api/v1/profile and displayed as plain
 * text. The parent edits this information on the Dashboard, not in the app — there is no
 * EditText and no save button here. Student info lives on children ([ChildrenActivity]).
 */
class ParentProfileActivity : AppCompatActivity() {

    private lateinit var tvName: TextView
    private lateinit var tvPhone: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvError: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_profile)

        TokenManager.init(this)
        if (!TokenManager.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.student_login_required), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvName = findViewById(R.id.tvParentName)
        tvPhone = findViewById(R.id.tvParentPhone)
        tvEmail = findViewById(R.id.tvParentEmail)
        tvError = findViewById(R.id.tvParentError)
        progress = findViewById(R.id.progressParent)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // Pre-fill the account email from the stored token while the network call runs.
        tvEmail.text = TokenManager.getEmail()?.takeIf { it.isNotBlank() }
            ?: getString(R.string.family_value_placeholder)

        loadProfile()
    }

    private fun loadProfile() {
        progress.visibility = View.VISIBLE
        hideError()
        lifecycleScope.launch {
            val p = ProfileApiService.getProfile()
            progress.visibility = View.GONE
            if (p != null) {
                tvName.text = p.fullName?.takeIf { it.isNotBlank() }
                    ?: p.displayName?.takeIf { it.isNotBlank() }
                    ?: p.username?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.family_value_placeholder)
                tvPhone.text = p.phone?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.family_value_placeholder)
                val email = p.email?.takeIf { it.isNotBlank() } ?: TokenManager.getEmail()
                tvEmail.text = email?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.family_value_placeholder)
            } else {
                showError(getString(R.string.student_load_error))
            }
        }
    }

    private fun showError(message: String) { tvError.text = message; tvError.visibility = View.VISIBLE }
    private fun hideError() { tvError.visibility = View.GONE }
}
