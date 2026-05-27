package com.buivan.ptalk_child

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Handles the OAuth2 redirect from Authentik.
 *
 * When Authentik redirects to app://kidmentor/callback?code=xxx&state=yyy,
 * Android routes the intent to this activity. We pass the intent data
 * to AuthentikAuthManager to exchange the code for tokens.
 *
 * This activity is transparent - user never sees it.
 */
class AuthCallbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val authManager = AuthentikAuthManager(this)

        authManager.handleAuthorizationResponse(
            data = intent,
            onSuccess = { result ->
                // Save tokens using existing TokenManager
                TokenManager.init(this)
                TokenManager.saveTokens(
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                    expiresIn = result.expiresIn,
                    username = result.name
                )

                // Navigate to main screen
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    putExtra("is_guest", false)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(mainIntent)
                finish()
            },
            onError = { error ->
                // Go back to login with error
                val loginIntent = Intent(this, LoginActivity::class.java).apply {
                    putExtra("auth_error", error)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(loginIntent)
                finish()
            }
        )
    }
}
