package com.laurenz.wordextremist.network

import android.content.Context
import com.laurenz.wordextremist.BuildConfig // If you want to enable logging only for debug builds
import com.laurenz.wordextremist.util.TokenManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    private var applicationContext: Context? = null
    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    private val okHttpClient: OkHttpClient by lazy {
        if (applicationContext == null) {
            throw IllegalStateException("ApiClient not initialized. Call ApiClient.initialize(context) first.")
        }

        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        val authInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val token = TokenManager.getToken(applicationContext!!) // Use TokenManager

            // Define paths that require authentication
            val requiresAuth = originalRequest.url.encodedPath.startsWith("/api/v1/matchmaking/") ||
                    originalRequest.url.encodedPath.startsWith("/api/v1/auth/users/me") // Add other auth paths

            if (token != null && requiresAuth) {
                val newRequest = originalRequest.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
                chain.proceed(newRequest)
            } else {
                chain.proceed(originalRequest)
            }
        }

        OkHttpClient.Builder()
            .addInterceptor(authInterceptor) // Add auth interceptor
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val instance: ApiService by lazy {
        if (applicationContext == null) {
            throw IllegalStateException("ApiClient not initialized. Call ApiClient.initialize(context) first.")
        }
        val retrofit = Retrofit.Builder()
            .baseUrl(ApiService.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(ApiService::class.java)
    }
}