package com.laurenz.wordextremist.network

import com.laurenz.wordextremist.model.BackendToken
import com.laurenz.wordextremist.model.DeviceLoginRequestData
import com.laurenz.wordextremist.model.CancelMatchmakingRequestData
import com.laurenz.wordextremist.model.GetOrCreateUserRequestData
import com.laurenz.wordextremist.model.MatchmakingResponse
import com.laurenz.wordextremist.model.UserPublic // Your Pydantic UserPublic model mirrored in Kotlin
import com.laurenz.wordextremist.model.SentencePromptPublic // Your Pydantic model mirrored
import com.laurenz.wordextremist.model.WordVaultEntry
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
// Data class for the Google token request body


interface ApiService {

    // Use your actual backend URL. For local dev, it's often 10.0.2.2 for Android emulator.
    companion object {
        // FOR LOCAL DEVELOPMENT WITH ANDROID EMULATOR:
        const val BASE_URL ="http://10.0.2.2:8000/" // 10.0.2.2 is localhost for emulator
        // FOR PHYSICAL DEVICE ON SAME WIFI (replace with your PC's local IP):
        // const val BASE_URL = "http://192.168.1.100:8000/"
        // FOR PRODUCTION:
        // const val BASE_URL = "https://your-deployed-backend.com/"
    }

    // --- Keep Auth Endpoints (Commented out or active as needed) ---
    /*
    @POST("api/v1/auth/google/link-device")
    suspend fun linkDeviceWithGoogleIdToken(
        @Body tokenRequest: GoogleTokenRequestData
    ): Response<UserPublic>

    @POST("api/v1/auth/pgs-login")
    suspend fun loginWithPlayGamesServerAuthCode(
        @Body authCodeRequest: ServerAuthCodeRequestData
    ): Response<BackendToken>

    */
    @GET("api/v1/auth/users/me")
    suspend fun getMyProfile(): Response<UserPublic>

    @POST("api/v1/auth/device-login") // New endpoint
    suspend fun deviceLogin(
        @Body requestData: DeviceLoginRequestData
    ): Response<BackendToken> // Returns JWT

    /*
    @POST("api/v1/auth/user/get-or-create")
    suspend fun getOrCreateUser(
        @Body requestData: GetOrCreateUserRequestData
    ): Response<UserPublic> // Returns the UserPublic object (including DB ID)
    */
    // --- Keep Game Content Endpoint ---
    @GET("api/v1/game-content/sentence-prompt/random")
    suspend fun getRandomSentencePrompt(
        // Remove Auth header if this should also be unauthenticated for testing
        @Query("language") language: String? // Add language query parameter
        // @Header("Authorization") token: String
    ): Response<SentencePromptPublic>

    // Modify existing or add new findMatch - Use GET, no Auth header, add Query params
    @GET("api/v1/matchmaking/find") // Correct path and method
    suspend fun findMatch(
        @Query("requested_language") language: String? // Add language query parameter, ensure name matches backend
        // @Header("Authorization") token: String
        // @Query("user_id") userId: Int,
    ): Response<MatchmakingResponse>


    // Add endpoint for cancelling matchmaking
    @POST("api/v1/matchmaking/cancel") // Correct path and method
    suspend fun cancelMatchmaking(
        // @Body requestData: CancelMatchmakingRequestData // Send user_id in the body
    ): Response<Unit> // Returns no body on success

    @GET("api/v1/auth/users/me/words")
    suspend fun getMyWords(): Response<List<WordVaultEntry>>
}