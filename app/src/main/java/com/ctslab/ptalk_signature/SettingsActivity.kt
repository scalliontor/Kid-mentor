package com.ctslab.ptalk_signature

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ctslab.ptalk_signature.databinding.ActivitySettingsBinding

/**
 * Shared settings screen for both modes. Account / subscription / about are global;
 * a mode-specific section (e.g. Elder Care emergency number) shows only when the
 * caller passes [ModeSelectActivity.EXTRA_APP_MODE]. Opened from the mode-select
 * header (global only) and from each main screen's top bar (with its mode).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var mode: AppMode? = null

    private val privacyUrl = "https://dashboard.ctslab.net/privacy"
    private val termsUrl = "https://dashboard.ctslab.net/terms"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        TokenManager.init(this)
        mode = intent.getStringExtra(ModeSelectActivity.EXTRA_APP_MODE)
            ?.let { runCatching { AppMode.valueOf(it) }.getOrNull() }

        applyAccent()
        bindAccount()
        bindTheme()
        bindAbout()
        bindModeSection()
        wireActions()
    }

    // ── Theme picker (Sáng / Tối / Theo hệ thống) ────────────────────────
    private fun bindTheme() {
        binding.rowThemeLight.setOnClickListener { selectTheme(ThemePrefs.LIGHT) }
        binding.rowThemeDark.setOnClickListener { selectTheme(ThemePrefs.DARK) }
        binding.rowThemeSystem.setOnClickListener { selectTheme(ThemePrefs.SYSTEM) }
        updateThemeChecks(ThemePrefs.read(this))
    }

    private fun selectTheme(mode: String) {
        if (ThemePrefs.read(this) == mode) return
        updateThemeChecks(mode)
        // Persisting + applying night mode recreates the activity to repaint in the new theme.
        ThemePrefs.set(this, mode)
    }

    private fun updateThemeChecks(current: String) {
        binding.checkThemeLight.visibility =
            if (current == ThemePrefs.LIGHT) View.VISIBLE else View.INVISIBLE
        binding.checkThemeDark.visibility =
            if (current == ThemePrefs.DARK) View.VISIBLE else View.INVISIBLE
        binding.checkThemeSystem.visibility =
            if (current == ThemePrefs.SYSTEM) View.VISIBLE else View.INVISIBLE
    }

    // ── Theming ──────────────────────────────────────────────────────────
    private fun accentColor(): Int {
        val res = when (mode) {
            AppMode.KID_MENTOR -> R.color.accent_kid_dark
            AppMode.ELDER_CARE -> R.color.accent_elder_dark
            else -> R.color.text_secondary
        }
        return ContextCompat.getColor(this, res)
    }

    private fun applyAccent() {
        val accent = accentColor()
        binding.tvSectionAccount.setTextColor(accent)
        binding.tvSectionAbout.setTextColor(accent)
        binding.tvSectionElder.setTextColor(accent)
    }

    // ── Sections ─────────────────────────────────────────────────────────
    private fun bindAccount() {
        if (TokenManager.isLoggedIn()) {
            val name = TokenManager.getUsername()?.takeIf { it.isNotBlank() }
                ?: TokenManager.getEmail()?.substringBefore("@")?.takeIf { it.isNotBlank() }
                ?: "Người dùng"
            binding.tvAccountName.text = name
            binding.tvAccountSub.text = TokenManager.getEmail()?.takeIf { it.isNotBlank() }
                ?: (TokenManager.getUserType() ?: "")
            styleAuthAsLogout()
        } else {
            binding.tvAccountName.text = getString(R.string.settings_guest_name)
            binding.tvAccountSub.text = getString(R.string.settings_guest_sub)
            styleAuthAsLogin()
        }
    }

    private fun bindAbout() {
        binding.tvVersion.text = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    private fun bindModeSection() {
        val isElder = mode == AppMode.ELDER_CARE
        binding.tvSectionElder.visibility = if (isElder) View.VISIBLE else View.GONE
        binding.cardElderSettings.visibility = if (isElder) View.VISIBLE else View.GONE
        if (isElder) {
            binding.etEmergencyNumber.setText(AppSettings.getEmergencyNumber(this))
            binding.etEmergencyNumber.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    persistEmergency(showToast = true)
                    true
                } else false
            }
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────
    private fun wireActions() {
        binding.btnSettingsBack.setOnClickListener { finish() }

        binding.rowSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java).apply {
                putExtra(ModeSelectActivity.EXTRA_APP_MODE, (mode ?: AppMode.KID_MENTOR).name)
            })
        }

        binding.rowPrivacy.setOnClickListener { openUrl(privacyUrl) }
        binding.rowTerms.setOnClickListener { openUrl(termsUrl) }

        binding.btnAuthAction.setOnClickListener {
            if (TokenManager.isLoggedIn()) confirmLogout() else goToLogin(clear = false)
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_logout_title)
            .setMessage(R.string.settings_logout_message)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_logout) { _, _ -> goToLogin(clear = true) }
            .show()
    }

    private fun goToLogin(clear: Boolean) {
        if (clear) TokenManager.clearTokens()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    // ── Button styling (logout = red outline, login = black pill) ────────
    private fun styleAuthAsLogout() {
        binding.btnAuthAction.apply {
            text = getString(R.string.settings_logout)
            setTextColor(0xFFD30005.toInt())
            setBackgroundResource(R.drawable.bg_logout_button)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_logout, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(0xFFD30005.toInt())
        }
    }

    private fun styleAuthAsLogin() {
        binding.btnAuthAction.apply {
            text = getString(R.string.settings_login)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(R.drawable.bg_login_button)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_key, 0, 0, 0)
            compoundDrawableTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
    }

    // ── Emergency number persistence ─────────────────────────────────────
    private fun persistEmergency(showToast: Boolean) {
        AppSettings.setEmergencyNumber(this, binding.etEmergencyNumber.text?.toString().orEmpty())
        binding.etEmergencyNumber.setText(AppSettings.getEmergencyNumber(this))
        if (showToast) {
            hideKeyboard()
            binding.etEmergencyNumber.clearFocus()
            Toast.makeText(this, R.string.settings_emergency_saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        // Persist silently so an edit is never lost on navigate-away.
        if (mode == AppMode.ELDER_CARE) {
            AppSettings.setEmergencyNumber(this, binding.etEmergencyNumber.text?.toString().orEmpty())
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }
}
