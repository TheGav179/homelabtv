package dev.homelabtv.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

interface HomelabApiService {
    @GET("api/guide")
    suspend fun getEnrichedGuide(): List<ChannelGuide>
}

object RetrofitClient {
    private var service: HomelabApiService? = null
    private var currentUrl: String = ""

    // The first guide build after fresh XML data can take minutes while the
    // server enriches new titles against TMDB — don't flag it offline for that.
    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .build()

    fun getInstance(baseUrl: String): HomelabApiService {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val cached = service
        if (cached == null || currentUrl != url) {
            currentUrl = url
            service =
                Retrofit.Builder()
                    .baseUrl(url)
                    .client(httpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(HomelabApiService::class.java)
        }
        return service!!
    }
}
