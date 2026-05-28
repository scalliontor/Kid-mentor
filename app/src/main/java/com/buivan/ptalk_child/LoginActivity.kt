package com.buivan.ptalk_child

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.buivan.ptalk_child.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authentikManager: AuthentikAuthManager

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

    private fun setupUI() {
        authentikManager = AuthentikAuthManager(this)

        // Nút SSO (Authentik) - Primary login
        binding.btnSSO.setOnClickListener {
            authentikManager.login(this, AUTHENTIK_REQUEST_CODE)
        }

        // Nút Vào xem thử (guest)
        binding.btnGuest.setOnClickListener {
            goToMain(isGuest = true)
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

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::authentikManager.isInitialized) {
            authentikManager.dispose()
        }
    }
}
