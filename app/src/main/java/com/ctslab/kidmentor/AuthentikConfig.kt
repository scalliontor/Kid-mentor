package com.ctslab.kidmentor

import android.net.Uri

/**
 * Authentik OIDC configuration for Kid Mentor app.
 * These values must match the Authentik blueprint (system-setup.yaml).
 */
object AuthentikConfig {
    // Authentik server URL
    const val ISSUER_URL = "https://auth.ctslab.net/application/o/kid-mentor/"
    const val AUTH_ENDPOINT = "https://auth.ctslab.net/application/o/authorize/"
    const val TOKEN_ENDPOINT = "https://auth.ctslab.net/application/o/token/"
    const val USERINFO_ENDPOINT = "https://auth.ctslab.net/application/o/userinfo/"
    const val END_SESSION_ENDPOINT = "https://auth.ctslab.net/application/o/kid-mentor/end-session/"

    // Client credentials (from Authentik blueprint)
    const val CLIENT_ID = "kid-mentor-client"
    const val CLIENT_SECRET = "kid-mentor-secret-key"

    // Redirect URI (must match Authentik provider config)
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
