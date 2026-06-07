package vn.vdpr.video.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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
    val banner_image: String? = null,
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
    val broadcast_id: String?,
    val stream_started_at: String?,
    val stream_ended_at: String?,
)

data class DeviceStatusRequest(
    val tournament_id: Int,
    val court_name: String,
    val battery_level: Int,
    val is_streaming: Boolean = false,
)

data class OverlayConfig(
    val logos: List<OverlayLogo> = emptyList(),
    val marquee_texts: List<String> = emptyList(),
)

data class OverlayLogo(
    val url: String,
    val position: String, // "top_right" or "bottom_right"
)

interface ApiService {
    @GET("public/tournaments")
    suspend fun getTournaments(@Query("active") active: Int = 1): TournamentListResponse

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

    @GET("public/tournaments/{id}/overlay")
    suspend fun getOverlayConfig(@Path("id") tournamentId: Int): OverlayConfig

    @POST("public/device-status")
    suspend fun reportDeviceStatus(@Body body: DeviceStatusRequest): Unit

    companion object {
        private fun getUnsafeOkHttpClient(): OkHttpClient {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }

        fun create(baseUrl: String): ApiService {
            val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            return Retrofit.Builder()
                .baseUrl(url)
                .client(getUnsafeOkHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ApiService::class.java)
        }
    }
}
