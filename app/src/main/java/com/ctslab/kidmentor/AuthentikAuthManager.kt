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
 */
class AuthentikAuthManager(private val context: Context) {

    private val serviceConfig = AuthorizationServiceConfiguration(
        AuthentikConfig.authEndpointUri,
        AuthentikConfig.tokenEndpointUri,
        null,
        AuthentikConfig.endSessionEndpointUri
    )

    private val authService = AuthorizationService(context)

    fun buildAuthorizationRequest(): AuthorizationRequest {
        return AuthorizationRequest.Builder(
            serviceConfig,
            AuthentikConfig.CLIENT_ID,
            ResponseTypeValues.CODE,
            AuthentikConfig.redirectUri
        ).apply {
            setScopes(AuthentikConfig.SCOPES)
        }.build()
    }

    fun getAuthorizationIntent(): Intent {
        val authRequest = buildAuthorizationRequest()
        return authService.getAuthorizationRequestIntent(authRequest)
    }

    fun login(activity: android.app.Activity, requestCode: Int) {
        val authRequest = buildAuthorizationRequest()
        val authIntent = authService.getAuthorizationRequestIntent(authRequest)
        activity.startActivityForResult(authIntent, requestCode)
    }

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

    private fun parseTokenResponse(response: TokenResponse): AuthResult {
        val accessToken = response.accessToken ?: ""
        val refreshToken = response.refreshToken ?: ""
        val idToken = response.idToken ?: ""
        val expiresIn = response.accessTokenExpirationTime?.let {
            ((it - System.currentTimeMillis()) / 1000).toInt()
        } ?: 3600

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

    private fun parseJwtClaims(jwt: String): JSONObject {
        val parts = jwt.split(".")
        if (parts.size < 2) return JSONObject()
        val payload = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE)
        return JSONObject(String(payload))
    }

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

    fun buildEndSessionRequest(idToken: String): EndSessionRequest {
        return EndSessionRequest.Builder(serviceConfig)
            .setIdTokenHint(idToken)
            .setPostLogoutRedirectUri(AuthentikConfig.redirectUri)
            .build()
    }

    fun dispose() {
        authService.dispose()
    }

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
