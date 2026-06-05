package com.ctslab.kidmentor

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

// ── Data classes ──────────────────────────────────────────────────────────────

/**
 * A child profile ("hồ sơ bé") owned by the logged-in parent. `username` is the
 * synthetic, no-login identifier the app sends as device_id when this child is active.
 * JSON keys mirror Dashboard /api/v1/children.
 */
data class ChildProfile(
    @SerializedName("id") val id: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("fullName") val fullName: String? = null,
    @SerializedName("grade") val grade: String? = null,            // "1".."12"
    @SerializedName("dateOfBirth") val dateOfBirth: String? = null, // "YYYY-MM-DD"
    @SerializedName("hometown") val hometown: String? = null,
    @SerializedName("curriculum") val curriculum: String? = null,   // chan_troi_sang_tao | canh_dieu | ket_noi_tri_thuc
    @SerializedName("relationship") val relationship: String? = null // father | mother | grandparent | guardian | other
)

data class ChildrenEnvelope(@SerializedName("children") val children: List<ChildProfile>?)
data class ChildEnvelope(@SerializedName("child") val child: ChildProfile?)

// ── Retrofit interface ────────────────────────────────────────────────────────

interface DashboardChildrenApi {
    @GET("api/v1/children")
    suspend fun list(): Response<ChildrenEnvelope>

    @POST("api/v1/children")
    suspend fun create(@Body body: ChildProfile): Response<ChildEnvelope>

    @GET("api/v1/children/{id}")
    suspend fun get(@Path("id") id: String): Response<ChildEnvelope>

    @PUT("api/v1/children/{id}")
    suspend fun update(@Path("id") id: String, @Body body: ChildProfile): Response<ChildEnvelope>

    @DELETE("api/v1/children/{id}")
    suspend fun delete(@Path("id") id: String): Response<Unit>
}

// ── Service ──────────────────────────────────────────────────────────────────

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

    sealed class Result {
        data class Success(val child: ChildProfile?) : Result()
        data class Error(val message: String) : Result()
    }

    /** List the parent's children (null on failure). */
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

    suspend fun get(id: String): ChildProfile? = withContext(Dispatchers.IO) {
        try {
            val response = api.get(id)
            if (response.isSuccessful) response.body()?.child else null
        } catch (e: Exception) {
            Log.e(TAG, "get error: ${e.message}"); null
        }
    }

    suspend fun create(body: ChildProfile): Result = withContext(Dispatchers.IO) {
        try {
            val response = api.create(body)
            if (response.isSuccessful) Result.Success(response.body()?.child)
            else Result.Error("HTTP ${response.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "create error: ${e.message}"); Result.Error(e.message ?: "network error")
        }
    }

    suspend fun update(id: String, body: ChildProfile): Result = withContext(Dispatchers.IO) {
        try {
            val response = api.update(id, body)
            if (response.isSuccessful) Result.Success(response.body()?.child)
            else Result.Error("HTTP ${response.code()}")
        } catch (e: Exception) {
            Log.e(TAG, "update error: ${e.message}"); Result.Error(e.message ?: "network error")
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            api.delete(id).isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "delete error: ${e.message}"); false
        }
    }
}
