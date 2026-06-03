package com.ctslab.kidmentor

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ctslab.kidmentor.databinding.ActivityRegisterBinding
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)
        setupUI()
    }

    private fun setupUI() {
        // Khi nhấn Done trên bàn phím → tự submit
        binding.etRegConfirmPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptRegister()
                true
            } else false
        }

        // Nút Đăng ký
        binding.btnRegister.setOnClickListener {
            hideKeyboard()
            attemptRegister()
        }

        // Link về trang Login
        binding.tvLoginLink.setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun attemptRegister() {
        val username = binding.etRegUsername.text.toString().trim()
        val email = binding.etRegEmail.text.toString().trim()
        val password = binding.etRegPassword.text.toString()
        val confirmPassword = binding.etRegConfirmPassword.text.toString()

        // Validate
        if (username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
            showError(getString(R.string.register_err_empty))
            shakeButton(binding.btnRegister)
            return
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError(getString(R.string.register_err_email_invalid))
            shakeButton(binding.btnRegister)
            return
        }

        if (password.length < 8) {
            showError(getString(R.string.register_err_password_short))
            shakeButton(binding.btnRegister)
            return
        }

        if (password != confirmPassword) {
            showError(getString(R.string.register_err_password_mismatch))
            shakeButton(binding.btnRegister)
            return
        }

        // Call API
        setLoading(true)
        hideError()

        lifecycleScope.launch {
            val result = AuthApiService.register(username, email, password)

            when (result) {
                is AuthApiService.AuthResult.Success -> {
                    showSuccess(getString(R.string.register_success))
                    // Registration succeeded — redirect to SSO login
                    goToLogin()
                }
                is AuthApiService.AuthResult.Error -> {
                    setLoading(false)
                    showError(result.message)
                    shakeButton(binding.btnRegister)
                }
            }
        }
    }

    // ── Navigation ───────────────────────────────────────────────────────
    private fun goToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── UI Helpers ────────────────────────────────────────────────────────
    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.btnRegister.text = if (loading)
            getString(R.string.register_registering)
        else
            getString(R.string.register_btn_text)
    }

    private fun showError(message: String) {
        binding.tvRegisterSuccess.visibility = View.GONE
        binding.tvRegisterError.text = message
        binding.tvRegisterError.visibility = View.VISIBLE
    }

    private fun showSuccess(message: String) {
        binding.tvRegisterError.visibility = View.GONE
        binding.tvRegisterSuccess.text = message
        binding.tvRegisterSuccess.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvRegisterError.visibility = View.GONE
        binding.tvRegisterSuccess.visibility = View.GONE
    }

    private fun shakeButton(view: View) {
        view.animate()
            .translationX(-12f).setDuration(60)
            .withEndAction {
                view.animate().translationX(12f).setDuration(60)
                    .withEndAction {
                        view.animate().translationX(-8f).setDuration(50)
                            .withEndAction {
                                view.animate().translationX(0f)
                                    .setDuration(50)
                                    .setInterpolator(AccelerateDecelerateInterpolator())
                                    .start()
                            }.start()
                    }.start()
            }.start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
