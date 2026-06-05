package com.ctslab.kidmentor

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import java.util.concurrent.TimeUnit

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * Parent (account owner) self-profile. The student fields (lớp, bộ sách, ...) live on
 * child records — see [ChildrenApiService]. JSON keys mirror Dashboard /api/v1/profile.
 */
data class ParentProfile(
    @SerializedName("fullName") val fullName: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("email") val email: String? = null,      // read-only (account email)
    @SerializedName("username") val username: String? = null,
    @SerializedName("displayName") val displayName: String? = null
)

data class ParentProfileEnvelope(
    @SerializedName("profile") val profile: ParentProfile?
)

// ── Retrofit interface ────────────────────────────────────────────────────────

interface DashboardProfileApi {
    @GET("api/v1/profile")
    suspend fun getProfile(): Response<ParentProfileEnvelope>

    @PUT("api/v1/profile")
    suspend fun updateProfile(@Body body: ParentProfile): Response<ParentProfileEnvelope>
}

// ── Service ──────────────────────────────────────────────────────────────────

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

    sealed class Result {
        data class Success(val profile: ParentProfile?) : Result()
        data class Error(val message: String) : Result()
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

    /** Save the parent profile. */
    suspend fun updateProfile(body: ParentProfile): Result = withContext(Dispatchers.IO) {
        try {
            val response = api.updateProfile(body)
            if (response.isSuccessful) Result.Success(response.body()?.profile)
            else Result.Error("HTTP ${response.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "updateProfile error: ${e.message}")
            Result.Error(e.message ?: "network error")
        }
    }
}

/**
 * Shared authenticated HTTP client for the Dashboard api/v1 endpoints: attaches the
 * Authentik access token as Bearer (same pattern as [DashboardChatApi]). The server
 * resolves the user from the token.
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
