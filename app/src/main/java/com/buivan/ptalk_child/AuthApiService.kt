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

// ── Auth Service ──────────────────────────────────────────────────────────────

object AuthApiService {

    private const val TAG = "AuthApiService"

    // Dashboard API for registration (creates user in Authentik)
    private const val DASHBOARD_URL = "https://dashboard.ctslab.net/"

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
