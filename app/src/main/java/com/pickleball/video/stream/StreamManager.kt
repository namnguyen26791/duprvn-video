package com.pickleball.video.stream

import android.content.Context
import com.pedro.encoder.input.gl.render.filters.NoFilterRender
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtplibrary.view.OpenGlView
import com.pickleball.video.data.MatchState
import com.pickleball.video.overlay.ScoreboardFilterRender

/**
 * Manages RTMP camera streaming with OpenGL overlay.
 * Uses OpenGlView so the scoreboard filter is encoded into the stream.
 */
class StreamManager(
    private val context: Context,
    private val openGlView: OpenGlView,
    private val onStatusChange: (String) -> Unit,
) : ConnectCheckerRtmp {

    private var rtmpCamera: RtmpCamera2? = null
    private val scoreboardFilter = ScoreboardFilterRender()
    var currentMatch: MatchState? = null; private set
    var courtName: String = ""; private set

    fun init(courtName: String) {
        this.courtName = courtName
        scoreboardFilter.courtName = courtName
        rtmpCamera = RtmpCamera2(openGlView, this)
        rtmpCamera?.glInterface?.setFilter(scoreboardFilter)
    }

    fun updateMatch(match: MatchState?) {
        currentMatch = match
        scoreboardFilter.matchState = match
    }

    fun startStream(rtmpUrl: String, streamKey: String) {
        val camera = rtmpCamera ?: return
        val fullUrl = "$rtmpUrl/$streamKey"
        if (!camera.isStreaming) {
            if (camera.prepareVideo(1280, 720, 30, 2500 * 1024, 0) &&
                camera.prepareAudio(128 * 1024, 44100, true)) {
                camera.startStream(fullUrl)
                onStatusChange("🔴 Đang stream...")
            } else {
                onStatusChange("❌ Không thể khởi tạo camera/audio")
            }
        }
    }

    fun stopStream() { rtmpCamera?.stopStream(); onStatusChange("⏹ Đã dừng") }
    fun startPreview() { rtmpCamera?.startPreview() }
    fun isStreaming(): Boolean = rtmpCamera?.isStreaming == true
    fun release() {
        try { if (rtmpCamera?.isStreaming == true) rtmpCamera?.stopStream(); rtmpCamera?.stopPreview() }
        catch (_: Exception) {}
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) { onStatusChange("🔄 Kết nối...") }
    override fun onConnectionSuccessRtmp() { onStatusChange("🔴 LIVE") }
    override fun onConnectionFailedRtmp(reason: String) {
        onStatusChange("❌ $reason")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            rtmpCamera?.reTry(5000, reason)
        }, 5000)
    }
    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() { onStatusChange("⚠️ Mất kết nối") }
    override fun onAuthErrorRtmp() { onStatusChange("❌ Auth error") }
    override fun onAuthSuccessRtmp() {}
}
