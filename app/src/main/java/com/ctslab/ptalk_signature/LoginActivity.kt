package com.ctslab.ptalk_signature

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ctslab.ptalk_signature.databinding.ActivityLoginBinding
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authentikManager: AuthentikAuthManager

    // AppAuth: nhận kết quả authorization qua ActivityResult API
    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val ex = AuthorizationException.fromIntent(data)
        val resp = AuthorizationResponse.fromIntent(data)

        if (ex != null) {
            showError("Lỗi Authentik: ${ex.errorDescription ?: ex.error}")
            return@registerForActivityResult
        }
        if (resp == null) {
            showError("Không nhận được phản hồi từ Authentik")
            return@registerForActivityResult
        }

        // Đổi code lấy token
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
                goToModeSelect(isGuest = false)
            },
            onError = { error -> showError(error) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)

        // Login đứng TRƯỚC bước chọn chế độ → màn này KHÔNG theo Kid/Elder,
        // dùng giao diện mặc định (1 lần đăng nhập mở khoá cả hai chế độ).
        // Nếu đã đăng nhập → sang thẳng màn chọn chế độ.
        if (TokenManager.isLoggedIn()) {
            goToModeSelect(isGuest = false)
            return
        }

        authentikManager = AuthentikAuthManager(this)

        setupUI()
    }

    private fun setupUI() {
        // Nút Đăng nhập → khởi chạy Authentik SSO
        binding.btnLogin.setOnClickListener {
            hideKeyboard()
            hideError()
            authLauncher.launch(authentikManager.getAuthorizationIntent())
        }

        // Nút Vào xem thử (guest)
        binding.btnGuest.setOnClickListener {
            hideKeyboard()
            goToModeSelect(isGuest = true)
        }

        // Privacy/Terms consent — phải đồng ý mới được đăng nhập / vào xem thử.
        binding.btnLogin.isEnabled = binding.cbAgreeTerms.isChecked
        binding.btnGuest.isEnabled = binding.cbAgreeTerms.isChecked
        binding.tvTermsError.visibility = if (binding.cbAgreeTerms.isChecked) View.GONE else View.VISIBLE
        binding.cbAgreeTerms.setOnCheckedChangeListener { _, checked ->
            binding.btnLogin.isEnabled = checked
            binding.btnGuest.isEnabled = checked
            binding.tvTermsError.visibility = if (checked) View.GONE else View.VISIBLE
        }
        setupConsentText()
    }

    private fun openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }

    /**
     * Build the consent sentence with the two links bold/blue/underlined and tappable
     * INLINE: "Tôi đồng ý với <Chính sách bảo mật> và <Điều khoản>".
     */
    private fun setupConsentText() {
        // trim + explicit spaces (Android trims edge whitespace from string resources)
        // + dynamic offsets.
        val prefix = getString(R.string.consent_prefix).trim()
        val privacy = getString(R.string.consent_privacy).trim()
        val mid = getString(R.string.consent_and).trim()
        val terms = getString(R.string.consent_terms).trim()
        val sb = SpannableStringBuilder()
        sb.append(prefix).append(" ")
        val pStart = sb.length; sb.append(privacy); val pEnd = sb.length
        sb.append(" ").append(mid).append(" ")
        val tStart = sb.length; sb.append(terms); val tEnd = sb.length
        val blue = ContextCompat.getColor(this, R.color.color_link_blue)

        fun linkify(start: Int, end: Int, url: String) {
            sb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = openUrl(url)
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(blue), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        linkify(pStart, pEnd, "https://dashboard.ctslab.net/privacy")
        linkify(tStart, tEnd, "https://dashboard.ctslab.net/terms")

        binding.tvConsent.text = sb
        binding.tvConsent.movementMethod = LinkMovementMethod.getInstance()
    }

    // ── Navigation ───────────────────────────────────────────────────────
    /** Sau khi đăng nhập (hoặc vào xem thử) → sang màn chọn chế độ Kid/Elder. */
    private fun goToModeSelect(isGuest: Boolean) {
        val intent = Intent(this, ModeSelectActivity::class.java).apply {
            putExtra(EXTRA_IS_GUEST, isGuest)
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        const val EXTRA_IS_GUEST = "is_guest"
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
