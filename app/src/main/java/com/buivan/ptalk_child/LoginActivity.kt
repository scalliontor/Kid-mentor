package com.buivan.ptalk_child

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.buivan.ptalk_child.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)

        // Nếu đã đăng nhập → vào thẳng Main
        if (TokenManager.isLoggedIn()) {
            goToMain(isGuest = false)
            return
        }

        setupUI()
        setupLanguageToggle()
    }

    private lateinit var authentikManager: AuthentikAuthManager

    private fun setupUI() {
        authentikManager = AuthentikAuthManager(this)

        // Khi nhấn Done trên bàn phím → tự submit login
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }

        // Nút Đăng nhập chính
        binding.btnLogin.setOnClickListener {
            hideKeyboard()
            attemptLogin()
        }

        // Nút Vào xem thử (guest)
        binding.btnGuest.setOnClickListener {
            hideKeyboard()
            goToMain(isGuest = true)
        }

        // Nút SSO (Authentik)
        binding.btnSSO?.setOnClickListener {
            hideKeyboard()
            authentikManager.login(this, AUTHENTIK_REQUEST_CODE)
        }

        // Link Đăng ký
        binding.tvRegisterLink?.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        // Check for auth error from callback
        intent.getStringExtra("auth_error")?.let { error ->
            showError(error)
        }
    }

    companion object {
        private const val AUTHENTIK_REQUEST_CODE = 1001
    }

    private fun setupLanguageToggle() {
        val currentLocale = AppCompatDelegate.getApplicationLocales()
        val isEnglish = currentLocale.toLanguageTags().startsWith("en")
        updateLanguageToggleUI(isEnglish)

        binding.btnLanguageToggle.setOnClickListener {
            val isCurrentEng = AppCompatDelegate.getApplicationLocales().toLanguageTags().startsWith("en")
            val newLang = if (isCurrentEng) "vi" else "en"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLang))
        }
    }

    private fun updateLanguageToggleUI(isEnglish: Boolean) {
        if (isEnglish) {
            binding.tvLangVIE.setBackgroundResource(0)
            binding.tvLangVIE.setTextColor(android.graphics.Color.parseColor("#707072"))

            binding.tvLangENG.setBackgroundResource(R.drawable.bg_lang_selected)
            binding.tvLangENG.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
        } else {
            binding.tvLangVIE.setBackgroundResource(R.drawable.bg_lang_selected)
            binding.tvLangVIE.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))

            binding.tvLangENG.setBackgroundResource(0)
            binding.tvLangENG.setTextColor(android.graphics.Color.parseColor("#707072"))
        }
    }

    // ── Xử lý đăng nhập ─────────────────────────────────────────────────
    private fun attemptLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validate không để trống
        if (username.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.login_err_empty))
            shakeButton(binding.btnLogin)
            return
        }

        // Gọi API đăng nhập
        setLoading(true)
        hideError()

        lifecycleScope.launch {
            val result = AuthApiService.login(
                username = username,
                password = password,
                deviceInfo = "android-${android.os.Build.MODEL}"
            )

            when (result) {
                is AuthApiService.AuthResult.Success -> {
                    // Lưu token an toàn
                    TokenManager.saveTokens(
                        accessToken = result.data.accessToken,
                        refreshToken = result.data.refreshToken,
                        expiresIn = result.data.expiresIn,
                        username = username
                    )
                    hideError()
                    animateSuccess()
                }
                is AuthApiService.AuthResult.Error -> {
                    setLoading(false)
                    showError(result.message)
                    shakeButton(binding.btnLogin)
                }
            }
        }
    }

    // ── Loading state ────────────────────────────────────────────────────
    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.text = if (loading)
            getString(R.string.login_logging_in)
        else
            getString(R.string.login_btn_text)
    }

    // ── Animation ────────────────────────────────────────────────────────
    private fun animateSuccess() {
        binding.btnLogin.animate()
            .scaleX(0.96f).scaleY(0.96f)
            .setDuration(100)
            .withEndAction {
                binding.btnLogin.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(100)
                    .withEndAction { goToMain(isGuest = false) }
                    .start()
            }
            .start()
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

    // ── Navigation ───────────────────────────────────────────────────────
    private fun goToMain(isGuest: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("is_guest", isGuest)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private fun showError(message: String) {
        binding.tvLoginError.text = message
        binding.tvLoginError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvLoginError.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::authentikManager.isInitialized) {
            authentikManager.dispose()
        }
    }
}
