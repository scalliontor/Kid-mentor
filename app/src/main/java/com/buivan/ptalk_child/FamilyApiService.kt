package com.buivan.ptalk_child

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import java.util.concurrent.TimeUnit

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * Parent (account owner) self-profile. JSON keys mirror Dashboard /api/v1/profile.
 * Read-only in this app — KidMentor (child app) only displays the family info.
 */
data class ParentProfile(
    @SerializedName("fullName") val fullName: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("displayName") val displayName: String? = null,
    @SerializedName("dateOfBirth") val dateOfBirth: String? = null,
    // Subscription tier is the DB source of truth (users.subscription_tier on the PARENT
    // account) — NOT the stored login user_type / JWT. "basic"|"pro"|"ultra".
    @SerializedName("subscriptionTier") val subscriptionTier: String? = null,
    @SerializedName("isSuperuser") val isSuperuser: Boolean = false
)

data class ParentProfileEnvelope(
    @SerializedName("profile") val profile: ParentProfile?
)

/**
 * A child profile ("hồ sơ bé") owned by the logged-in parent.
 * JSON keys mirror Dashboard /api/v1/children.
 */
data class ChildProfile(
    @SerializedName("id") val id: String? = null,
    @SerializedName("username") val username: String? = null,       // "child_xxx" — sent as device_id to scope the backend to this child
    @SerializedName("fullName") val fullName: String? = null,
    @SerializedName("grade") val grade: String? = null,            // "1".."12"
    @SerializedName("dateOfBirth") val dateOfBirth: String? = null, // "YYYY-MM-DD"
    @SerializedName("curriculum") val curriculum: String? = null,   // chan_troi_sang_tao | canh_dieu | ket_noi_tri_thuc
    @SerializedName("relationship") val relationship: String? = null
)

data class ChildrenEnvelope(@SerializedName("children") val children: List<ChildProfile>?)

// ── Retrofit interfaces ─────────────────────────────────────────────────────────

interface DashboardProfileApi {
    @GET("api/v1/profile")
    suspend fun getProfile(): Response<ParentProfileEnvelope>
}

interface DashboardChildrenApi {
    @GET("api/v1/children")
    suspend fun list(): Response<ChildrenEnvelope>
}

// ── Services ───────────────────────────────────────────────────────────────────

/** Fetches the logged-in parent's own profile (read-only). */
object ProfileApiService {

    private const val TAG = "ProfileApiService"

    private val api: DashboardProfileApi by lazy {
        Retrofit.Builder()
            .baseUrl(DashboardHttp.BASE_URL)
            .client(DashboardHttp.client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DashboardProfileApi::class.java)
    }

    /** Fetch the logged-in parent's profile (null on any failure). */
    suspend fun getProfile(): ParentProfile? = withContext(Dispatchers.IO) {
        try {
            val response = api.getProfile()
            if (response.isSuccessful) response.body()?.profile else {
                Log.w(TAG, "getProfile failed: ${response.code()}"); null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getProfile error: ${e.message}"); null
        }
    }
}

/** Fetches the parent's children (read-only). */
object ChildrenApiService {

    private const val TAG = "ChildrenApiService"

    private val api: DashboardChildrenApi by lazy {
        Retrofit.Builder()
            .baseUrl(DashboardHttp.BASE_URL)
            .client(DashboardHttp.client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DashboardChildrenApi::class.java)
    }

    /** List the parent's children (null on failure, empty list when none). */
    suspend fun list(): List<ChildProfile>? = withContext(Dispatchers.IO) {
        try {
            val response = api.list()
            if (response.isSuccessful) response.body()?.children ?: emptyList() else {
                Log.w(TAG, "list failed: ${response.code()}"); null
            }
        } catch (e: Exception) {
            Log.e(TAG, "list error: ${e.message}"); null
        }
    }
}

/**
 * Shared authenticated HTTP client for the Dashboard api/v1 endpoints: attaches the
 * Authentik access token as Bearer (same pattern as [DashboardChatApi]). The server
 * resolves the user from the token via [TokenManager].
 */
object DashboardHttp {
    const val BASE_URL = "https://dashboard.ctslab.net/"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val token = TokenManager.getAccessToken()
            val request = if (token != null) {
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        .build()
}
