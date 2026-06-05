package com.buivan.ptalk_child

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.buivan.ptalk_child.databinding.ActivityLoginBinding
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authentikManager: AuthentikAuthManager

    // AppAuth: handle authorization result via ActivityResult API
    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val resp = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)

        if (ex != null) {
            showError("Lỗi Authentik: ${ex.errorDescription ?: ex.error}")
            return@registerForActivityResult
        }
        if (resp == null) {
            showError("Không nhận được phản hồi từ Authentik")
            return@registerForActivityResult
        }

        // Exchange code for tokens
        authentikManager.handleAuthorizationResponse(
            data = data,
            onSuccess = { authResult ->
                TokenManager.init(this)
                TokenManager.saveTokens(
                    accessToken = authResult.accessToken,
                    refreshToken = authResult.refreshToken,
                    expiresIn = authResult.expiresIn,
                    username = authResult.name,
                    userType = authResult.userType,
                    email = authResult.email
                )
                goToMain(isGuest = false)
            },
            onError = { error ->
                showError(error)
            }
        )
    }

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
            val authIntent = authentikManager.getAuthorizationIntent()
            authLauncher.launch(authIntent)
        }

        // Nút Vào xem thử (guest)
        binding.btnGuest.setOnClickListener {
            goToMain(isGuest = true)
        }

        // Link đăng ký
        binding.tvRegisterLink.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Check for auth error from callback
        intent.getStringExtra("auth_error")?.let { error ->
            showError(error)
        }
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
