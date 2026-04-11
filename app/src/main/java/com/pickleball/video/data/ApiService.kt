package com.pickleball.video.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class StreamConfigResponse(
    val courts: List<String>,
    val stream_config: Map<String, CourtStreamConfig>,
    val court_channels: Map<String, Int>? = null,
)

data class CourtStreamConfig(
    val rtmp_url: String?,
    val stream_key: String?,
    val youtube_video_id: String?,
    val stopped: Boolean? = false,
)

data class TournamentListItem(
    val id: Int,
    val name: String,
)

data class TournamentListResponse(
    val data: List<TournamentListItem>,
)

data class MatchStreamConfigResponse(
    val rtmp_url: String?,
    val stream_key: String?,
    val stream_ended_at: String?,
    val status: String?,
    val youtube_video_id: String?,
    val court: String?,
    val tournament_id: Int?,
)

data class CourtMatchItem(
    val id: Int,
    val match_type: String,
    val match_order: Int?,
    val team1: String,
    val team2: String,
    val status: String,
    val rtmp_url: String?,
    val stream_key: String?,
    val youtube_video_id: String?,
    val stream_started_at: String?,
    val stream_ended_at: String?,
)

interface ApiService {
    @GET("public/tournaments")
    suspend fun getTournaments(): TournamentListResponse

    @GET("public/tournaments/{id}/stream-config")
    suspend fun getStreamConfig(@Path("id") tournamentId: Int): StreamConfigResponse

    @GET("public/matches/{matchId}/stream-config")
    suspend fun getMatchStreamConfig(
        @Path("matchId") matchId: Int,
        @Query("match_type") matchType: String
    ): MatchStreamConfigResponse

    @GET("public/tournaments/{id}/court-matches")
    suspend fun getCourtMatches(
        @Path("id") tournamentId: Int,
        @Query("court") court: String
    ): List<CourtMatchItem>

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
