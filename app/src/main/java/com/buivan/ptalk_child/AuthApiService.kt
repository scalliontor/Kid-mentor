package com.buivan.ptalk_child

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

// ── Data classes ──────────────────────────────────────────────────────────────

data class RegisterBody(
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("password") val password: String,
    @SerializedName("confirmPassword") val confirmPassword: String
)

data class LoginBody(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("device_info") val deviceInfo: String? = null
)

data class RefreshBody(
    @SerializedName("refresh_token") val refreshToken: String
)

data class TokenResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("expires_in") val expiresIn: Int
)

data class UserResponse(
    @SerializedName("id") val id: String,
    @SerializedName("username") val username: String,
    @SerializedName("email") val email: String,
    @SerializedName("user_type") val userType: String = "account_owner",
    @SerializedName("display_name") val displayName: String? = null,
    @SerializedName("subscription_tier") val subscriptionTier: String = "basic",
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("is_superuser") val isSuperuser: Boolean = false,
    @SerializedName("created_at") val createdAt: String = ""
)

data class RegisterResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("user") val user: UserResponse?,
    @SerializedName("error") val error: String? = null
)

data class QuotaResponse(
    @SerializedName("tier") val tier: String,
    @SerializedName("is_admin") val isAdmin: Boolean,
    @SerializedName("daily_limit") val dailyLimit: Int,
    @SerializedName("used_today") val usedToday: Int,
    @SerializedName("remaining") val remaining: Int
)

data class ErrorResponse(
    @SerializedName("detail") val detail: String?,
    @SerializedName("error") val error: String?
)

// ── Retrofit Interfaces ──────────────────────────────────────────────────────

/**
 * Dashboard API for registration (creates user in both Authentik + Dashboard DB)
 */
interface DashboardAuthApi {
    @POST("api/auth/signup")
    suspend fun register(@Body body: RegisterBody): Response<RegisterResponse>
}

/**
 * CloudPTalk Auth API for login/refresh/quota (JWT-based)
 */
interface CloudAuthApi {
    @POST("auth/login")
    suspend fun login(@Body body: LoginBody): Response<TokenResponse>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshBody): Response<TokenResponse>

    @GET("auth/me")
    suspend fun getMe(@Header("Authorization") bearer: String): Response<UserResponse>

    @GET("auth/quota")
    suspend fun getQuota(@Header("Authorization") bearer: String): Response<QuotaResponse>

    @POST("auth/quota/use")
    suspend fun useQuota(@Header("Authorization") bearer: String): Response<QuotaResponse>

    @GET("auth/health")
    suspend fun health(): Response<Map<String, String>>
}

// ── Auth Service ──────────────────────────────────────────────────────────────

object AuthApiService {

    private const val TAG = "AuthApiService"

    // Dashboard API for registration (creates user in Authentik)
    private const val DASHBOARD_URL = "https://dashboard.ctslab.net/"

    // CloudPTalk Auth for login/refresh/quota (via nginx gateway)
    private const val CLOUD_AUTH_URL = "http://171.226.10.121:8000/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val dashboardApi: DashboardAuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(DASHBOARD_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DashboardAuthApi::class.java)
    }

    private val cloudAuthApi: CloudAuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(CLOUD_AUTH_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CloudAuthApi::class.java)
    }

    // ── Register (via Dashboard → Authentik) ──────────────────────────────

    sealed class AuthResult<out T> {
        data class Success<T>(val data: T) : AuthResult<T>()
        data class Error(val message: String) : AuthResult<Nothing>()
    }

    suspend fun register(
        username: String,
        email: String,
        password: String
    ): AuthResult<UserResponse> = withContext(Dispatchers.IO) {
        try {
            val response = dashboardApi.register(
                RegisterBody(username, email, password, password)
            )
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.success && body.user != null) {
                    Log.d(TAG, "Register success: ${body.user.username}")
                    AuthResult.Success(body.user)
                } else {
                    AuthResult.Error(body.error ?: "Đăng ký thất bại")
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val message = parseErrorMessage(errorBody, response.code())
                Log.w(TAG, "Register failed: $message")
                AuthResult.Error(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register error: ${e.message}")
            AuthResult.Error("Không kết nối được server. Kiểm tra mạng.")
        }
    }

    // ── Login (via CloudPTalk Auth) ───────────────────────────────────────

    suspend fun login(
        username: String,
        password: String,
        deviceInfo: String? = null
    ): AuthResult<TokenResponse> = withContext(Dispatchers.IO) {
        try {
            val response = cloudAuthApi.login(LoginBody(username, password, deviceInfo))
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Login success")
                AuthResult.Success(response.body()!!)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                val message = parseErrorMessage(errorBody, response.code())
                Log.w(TAG, "Login failed: $message")
                AuthResult.Error(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error: ${e.message}")
            AuthResult.Error("Không kết nối được server. Kiểm tra mạng.")
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    suspend fun refreshToken(refreshToken: String): AuthResult<TokenResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = cloudAuthApi.refresh(RefreshBody(refreshToken))
                if (response.isSuccessful && response.body() != null) {
                    AuthResult.Success(response.body()!!)
                } else {
                    AuthResult.Error("Phiên đăng nhập hết hạn")
                }
            } catch (e: Exception) {
                AuthResult.Error("Không kết nối được server")
            }
        }

    // ── Get current user ──────────────────────────────────────────────────

    suspend fun getMe(accessToken: String): AuthResult<UserResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = cloudAuthApi.getMe("Bearer $accessToken")
                if (response.isSuccessful && response.body() != null) {
                    AuthResult.Success(response.body()!!)
                } else {
                    AuthResult.Error("Token không hợp lệ")
                }
            } catch (e: Exception) {
                AuthResult.Error("Không kết nối được server")
            }
        }

    // ── Quota ──────────────────────────────────────────────────────────────

    suspend fun getQuota(accessToken: String): AuthResult<QuotaResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = cloudAuthApi.getQuota("Bearer $accessToken")
                if (response.isSuccessful && response.body() != null) {
                    AuthResult.Success(response.body()!!)
                } else {
                    AuthResult.Error("Không lấy được thông tin quota")
                }
            } catch (e: Exception) {
                AuthResult.Error("Không kết nối được server")
            }
        }

    suspend fun useQuota(accessToken: String): AuthResult<QuotaResponse> =
        withContext(Dispatchers.IO) {
            try {
                val response = cloudAuthApi.useQuota("Bearer $accessToken")
                if (response.isSuccessful && response.body() != null) {
                    AuthResult.Success(response.body()!!)
                } else if (response.code() == 429) {
                    AuthResult.Error("Đã hết lượt hôm nay!")
                } else {
                    AuthResult.Error("Lỗi quota")
                }
            } catch (e: Exception) {
                AuthResult.Error("Không kết nối được server")
            }
        }

    // ── Helper ────────────────────────────────────────────────────────────

    private fun parseErrorMessage(errorBody: String, code: Int): String {
        return try {
            val gson = com.google.gson.Gson()
            val error = gson.fromJson(errorBody, ErrorResponse::class.java)
            error.detail ?: error.error ?: "Lỗi không xác định ($code)"
        } catch (_: Exception) {
            when (code) {
                401 -> "Sai tên đăng nhập hoặc mật khẩu"
                409 -> "Tên đăng nhập hoặc email đã tồn tại"
                429 -> "Quá nhiều lần thử. Vui lòng chờ."
                else -> "Lỗi server ($code)"
            }
        }
    }
}
