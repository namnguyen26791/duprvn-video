package com.pickleball.video.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

data class StreamConfigResponse(
    val courts: List<String>,
    val stream_config: Map<String, CourtStreamConfig>,
)

data class CourtStreamConfig(
    val rtmp_url: String?,
    val stream_key: String?,
    val youtube_video_id: String?,
)

data class TournamentListItem(
    val id: Int,
    val name: String,
)

data class TournamentListResponse(
    val data: List<TournamentListItem>,
)

interface ApiService {
    @GET("public/tournaments")
    suspend fun getTournaments(): TournamentListResponse

    @GET("public/tournaments/{id}/stream-config")
    suspend fun getStreamConfig(@Path("id") tournamentId: Int): StreamConfigResponse

    companion object {
        fun create(baseUrl: String): ApiService {
            val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return Retrofit.Builder()
                .baseUrl(url)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
