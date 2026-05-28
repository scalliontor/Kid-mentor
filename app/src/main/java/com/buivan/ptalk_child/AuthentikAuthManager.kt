package com.buivan.ptalk_child

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.EndSessionRequest
import net.openid.appauth.EndSessionResponse
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import net.openid.appauth.ClientSecretBasic
import org.json.JSONObject

/**
 * Manages Authentik OIDC authentication flow using AppAuth library.
 *
 * Flow:
 * 1. User taps "Login with SSO" → buildAuthorizationRequest()
 * 2. App opens Authentik login page in Custom Tab
 * 3. User enters credentials on Authentik
 * 4. Authentik redirects to app://kidmentor/callback with auth code
 * 5. App exchanges code for tokens → handleAuthorizationResponse()
 * 6. Tokens stored in EncryptedSharedPreferences via TokenManager
 */
class AuthentikAuthManager(private val context: Context) {

    // AppAuth service configuration with explicit endpoints
    private val serviceConfig = AuthorizationServiceConfiguration(
        AuthentikConfig.authEndpointUri,   // Authorization endpoint
        AuthentikConfig.tokenEndpointUri,  // Token endpoint
        null,                               // Registration endpoint (not used)
        AuthentikConfig.endSessionEndpointUri // End session endpoint
    )

    private val authService = AuthorizationService(context)

    /**
     * Build the authorization request for Authentik OIDC.
     * Uses PKCE (Proof Key for Code Exchange) for security.
     */
    fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            serviceConfig,
            AuthentikConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            AuthentikConfig.redirectUri
        ).apply {
            setScopes(AuthentikConfig.SCOPES)
            // PKCE is enabled by default in AppAuth
        }.build()
    }

    /**
     * Get the authorization intent for launching with ActivityResult API.
     * Returns an Intent that opens Chrome Custom Tab with the Authentik login page.
     * The result comes back to the calling activity via onActivityResult.
     */
    fun getAuthorizationIntent(): Intent {
        val authRequest = buildAuthorizationRequest()
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    /**
     * Launch the Authentik login flow (legacy, use getAuthorizationIntent instead).
     */
    fun login(activity: android.app.Activity, requestCode: Int) {
        val authRequest = buildAuthorizationRequest()
        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
        activity.startActivityForResult(authIntent, requestCode)
    }

    /**
     * Handle the authorization response after Authentik redirects back.
     * Exchanges the authorization code for tokens.
     */
    fun handleAuthorizationResponse(
        data: Intent,
        onSuccess: (AuthResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val response = AuthorizationResponse.fromIntent(data)
        val exception = AuthorizationException.fromIntent(data)

        if (exception != null) {
            onError("Authentik error: ${exception.errorDescription ?: exception.error}")
            return
        }

        if (response == null) {
            onError("No response from Authentik")
            return
        }

        // Exchange authorization code for tokens
        // Must include client_secret for confidential client authentication
        val tokenRequest = response.createTokenExchangeRequest()
        val clientAuth = ClientSecretBasic(AuthentikConfig.CLIENT_SECRET)
        authService.performTokenRequest(tokenRequest, clientAuth) { tokenResponse, tokenException ->
            if (tokenException != null) {
                onError("Token exchange failed: ${tokenException.errorDescription ?: tokenException.error}")
                return@performTokenRequest
            }

            if (tokenResponse == null) {
                onError("No token response")
                return@performTokenRequest
            }

            onSuccess(parseTokenResponse(tokenResponse))
        }
    }

    /**
     * Parse the token response and extract user info.
     */
    private fun parseTokenResponse(response: TokenResponse): AuthResult {
        val accessToken = response.accessToken ?: ""
        val refreshToken = response.refreshToken ?: ""
        val idToken = response.idToken ?: ""
        val expiresIn = response.accessTokenExpirationTime?.let {
            ((it - System.currentTimeMillis()) / 1000).toInt()
        } ?: 3600

        // Parse ID token claims (JWT payload)
        val claims = parseJwtClaims(idToken)
        val userId = claims.optString("sub", "")
        val email = claims.optString("email", "")
        val name = claims.optString("name", claims.optString("preferred_username", ""))
        val groups = claims.optJSONArray("groups")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
        val userType = claims.optString("user_type", "child")
        val assignedProducts = claims.optJSONArray("assigned_products")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return AuthResult(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            expiresIn = expiresIn,
            userId = userId,
            email = email,
            name = name,
            groups = groups,
            userType = userType,
            assignedProducts = assignedProducts
        )
    }

    /**
     * Parse JWT payload (middle part) without verification.
     * AppAuth already verifies the token signature.
     */
    private fun parseJwtClaims(jwt: String): JSONObject {
        val parts = jwt.split(".")
        if (parts.size < 2) return JSONObject()
        val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE)
        return JSONObject(String(payload))
    }

    /**
     * Refresh the access token using the refresh token.
     */
    fun refreshToken(
        refreshToken: String,
        onSuccess: (AuthResult) -> Unit,
        onError: (String) -> Unit
    ) {
        val tokenRequest = TokenRequest.Builder(serviceConfig, AuthentikConfig.CLIENT_ID)
            .setGrantType("refresh_token")
            .setRefreshToken(refreshToken)
            .setScopes(AuthentikConfig.SCOPES)
            .build()

        val clientAuth = ClientSecretBasic(AuthentikConfig.CLIENT_SECRET)
        authService.performTokenRequest(tokenRequest, clientAuth) { response, exception ->
            if (exception != null) {
                onError("Refresh failed: ${exception.errorDescription ?: exception.error}")
                return@performTokenRequest
            }
            if (response == null) {
                onError("No refresh response")
                return@performTokenRequest
            }
            onSuccess(parseTokenResponse(response))
        }
    }

    /**
     * Build end-session request for logout.
     */
    fun buildEndSessionRequest(idToken: String): EndSessionRequest {
        return EndSessionRequest.Builder(serviceConfig)
            .setIdTokenHint(idToken)
            .setPostLogoutRedirectUri(AuthentikConfig.redirectUri)
            .build()
    }

    fun dispose() {
        authService.dispose()
    }

    /**
     * Data class holding authentication result from Authentik.
     */
    data class AuthResult(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val expiresIn: Int,
        val userId: String,
        val email: String,
        val name: String,
        val groups: List<String>,
        val userType: String,
        val assignedProducts: List<String>
    )
}
