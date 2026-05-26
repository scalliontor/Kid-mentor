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
    @SerializedName("user_type") val userType: String = "account_owner"
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
    @SerializedName("user_type") val userType: String,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("created_at") val createdAt: String
)

data class ErrorResponse(
    @SerializedName("detail") val detail: String?
)

// ── Retrofit Interface ────────────────────────────────────────────────────────

interface AuthApi {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterBody): Response<UserResponse>

    @POST("auth/login")
    suspend fun login(@Body body: LoginBody): Response<TokenResponse>

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshBody): Response<TokenResponse>

    @GET("auth/me")
    suspend fun getMe(@Header("Authorization") bearer: String): Response<UserResponse>

    @GET("auth/health")
    suspend fun health(): Response<Map<String, String>>
}

// ── Auth Service ──────────────────────────────────────────────────────────────

object AuthApiService {

    private const val TAG = "AuthApiService"

    // Auth service base URL — goes through nginx gateway
    private const val AUTH_BASE_URL = "${ServerConfig.HTTP_BASE_URL}../auth/"

    // Fallback direct URL if gateway doesn't have /auth/ yet
    private const val AUTH_DIRECT_URL = "http://171.226.10.121:8003/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val api: AuthApi by lazy {
        Retrofit.Builder()
            .baseUrl(AUTH_DIRECT_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AuthApi::class.java)
    }

    // ── Register ──────────────────────────────────────────────────────────

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
            val response = api.register(RegisterBody(username, email, password))
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Register success: ${response.body()!!.username}")
                AuthResult.Success(response.body()!!)
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

    // ── Login ─────────────────────────────────────────────────────────────

    suspend fun login(
        username: String,
        password: String,
        deviceInfo: String? = null
    ): AuthResult<TokenResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.login(LoginBody(username, password, deviceInfo))
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
                val response = api.refresh(RefreshBody(refreshToken))
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
                val response = api.getMe("Bearer $accessToken")
                if (response.isSuccessful && response.body() != null) {
                    AuthResult.Success(response.body()!!)
                } else {
                    AuthResult.Error("Token không hợp lệ")
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
            error.detail ?: "Lỗi không xác định ($code)"
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
