package com.example.gym.sync

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.Retrofit

/**
 * Retrofit/OkHttp singleton for TrainHub. The server address is user-configurable (Settings
 * screen) and can change at runtime, so Retrofit is built against a placeholder base URL and a
 * request interceptor rewrites the scheme/host/port to whatever [updateConfig] last set, adding
 * the X-API-Key header at the same time. Call [updateConfig] whenever settings are read/changed —
 * TreeViewModel and TrainHubSyncWorker both do this before making a request.
 */
object TrainHubClient {
    @Volatile
    private var config: SyncConfig? = null

    fun updateConfig(newConfig: SyncConfig?) {
        config = newConfig
    }

    private val authInterceptor = Interceptor { chain ->
        val current = config ?: throw IOException("TrainHub is not configured (set server URL + API key in Settings)")
        val configuredUrl = current.baseUrl.toHttpUrlOrNull()
            ?: throw IOException("Invalid TrainHub server URL: ${current.baseUrl}")
        val original = chain.request()
        val rewrittenUrl = original.url.newBuilder()
            .scheme(configuredUrl.scheme)
            .host(configuredUrl.host)
            .port(configuredUrl.port)
            .build()
        val rewrittenRequest = original.newBuilder()
            .url(rewrittenUrl)
            .header("X-API-Key", current.apiKey)
            .build()
        chain.proceed(rewrittenRequest)
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            // Never actually dialed — authInterceptor rewrites host/port/scheme per request.
            .baseUrl("http://trainhub.invalid/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    val api: TrainHubApi by lazy { retrofit.create(TrainHubApi::class.java) }
}
