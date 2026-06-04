package com.ctslab.ptalk_signature

import android.net.Uri

/**
 * Authentik OIDC configuration for P-Talk Signature.
 * These values must match the Authentik blueprint (system-setup.yaml → provider-ptalk-signature).
 * Both app modes (Kid Mentor / Elder Care) share this single client.
 */
object AuthentikConfig {
    // TEMP: repointed to the registered `kid-mentor` client because the `ptalk-signature`
    // OAuth2 provider/application does not yet exist in Authentik (authorize -> "Client ID Error").
    // Revert all five constants below to ptalk-signature/* once the dedicated client is
    // created in Authentik admin (provider client_id=ptalk-signature-client + app slug=ptalk-signature).
    // Authentik server URLs (issuer/end-session are slug-specific; auth/token/userinfo are global)
    const val ISSUER_URL = "https://auth.ctslab.net/application/o/kid-mentor/"
    const val AUTH_ENDPOINT = "https://auth.ctslab.net/application/o/authorize/"
    const val TOKEN_ENDPOINT = "https://auth.ctslab.net/application/o/token/"
    const val USERINFO_ENDPOINT = "https://auth.ctslab.net/application/o/userinfo/"
    const val END_SESSION_ENDPOINT = "https://auth.ctslab.net/application/o/kid-mentor/end-session/"

    // Client credentials — TEMP: reusing kid-mentor's registered confidential client
    const val CLIENT_ID = "kid-mentor-client"
    const val CLIENT_SECRET = "kid-mentor-secret-key"

    // Redirect URI (must match the kid-mentor provider's registered redirect)
    const val REDIRECT_URI = "app://kidmentor/callback"

    // Scopes to request
    val SCOPES = listOf("openid", "email", "profile", "roles", "user_type", "assigned_products")

    // Authentik endpoints as Uri objects for AppAuth
    val issuerUri: Uri get() = Uri.parse(ISSUER_URL)
    val authEndpointUri: Uri get() = Uri.parse(AUTH_ENDPOINT)
    val tokenEndpointUri: Uri get() = Uri.parse(TOKEN_ENDPOINT)
    val userinfoEndpointUri: Uri get() = Uri.parse(USERINFO_ENDPOINT)
    val endSessionEndpointUri: Uri get() = Uri.parse(END_SESSION_ENDPOINT)
    val redirectUri: Uri get() = Uri.parse(REDIRECT_URI)
}
