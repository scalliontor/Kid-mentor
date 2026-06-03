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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ctslab.ptalk_signature.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var appMode: AppMode = AppMode.KID_MENTOR

    // ── Tài khoản giả (demo) ─────────────────────────────────────────────
    companion object {
        private const val DEMO_USERNAME = "ptalk"
        private const val DEMO_PASSWORD = "ptit2025"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Nhận AppMode từ ModeSelectActivity
        val modeName = intent.getStringExtra(ModeSelectActivity.EXTRA_APP_MODE)
        appMode = modeName?.let { AppMode.valueOf(it) } ?: AppMode.KID_MENTOR

        applyModeUI()
        setupUI()
    }

    /** Cập nhật giao diện Login theo chế độ đã chọn */
    private fun applyModeUI() {
        // Brand title trong header
        val brandTitle = binding.loginBrandHeader.findViewWithTag<android.widget.TextView>("brandTitle")
        // Nếu không dùng tag thì sửa trực tiếp text ở XML, hoặc tìm theo thứ tự con
        // Ở đây ta sẽ duyệt header tìm TextView đầu tiên có text "PTALK"
        findTextViewWithText(binding.loginBrandHeader, "PTALK")?.text = appMode.brandTitle

        // Headline & subheadline
        binding.tvLoginHeadline.text = appMode.loginHeadline
        binding.tvLoginSubheadline.text = appMode.loginSubheadline

        // Login button color theo mode
        if (appMode == AppMode.ELDER_CARE) {
            binding.btnLogin.setBackgroundResource(R.drawable.bg_login_button_elder)
        }
    }

    /** Tìm TextView chứa text cụ thể trong ViewGroup (depth-first) */
    private fun findTextViewWithText(parent: android.view.ViewGroup, targetText: String): android.widget.TextView? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child is android.widget.TextView && child.text.toString() == targetText) {
                return child
            }
            if (child is android.view.ViewGroup) {
                findTextViewWithText(child, targetText)?.let { return it }
            }
        }
        return null
    }

    private fun setupUI() {
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

    // ── Xử lý đăng nhập ─────────────────────────────────────────────────
    private fun attemptLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        // Validate không để trống
        if (username.isEmpty() || password.isEmpty()) {
            showError("Vui lòng nhập đầy đủ tên đăng nhập và mật khẩu.")
            shakeButton(binding.btnLogin)
            return
        }

        // Kiểm tra credentials giả
        if (username == DEMO_USERNAME && password == DEMO_PASSWORD) {
            hideError()
            animateSuccess()
        } else {
            showError("Tên đăng nhập hoặc mật khẩu không đúng.")
            shakeButton(binding.btnLogin)
        }
    }

    // ── Animation ────────────────────────────────────────────────────────
    private fun animateSuccess() {
        // Scale nút login → hiệu ứng "bấm được"
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
            putExtra(ModeSelectActivity.EXTRA_APP_MODE, appMode.name)
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
}
