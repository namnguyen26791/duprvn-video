package com.pickleball.video.stream

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtplibrary.view.OpenGlView
import com.pickleball.video.data.MatchState
import com.pickleball.video.overlay.ScoreboardOverlay

class StreamManager(
    private val context: Context,
    private val openGlView: OpenGlView,
    private val onStatusChange: (String) -> Unit,
) : ConnectCheckerRtmp {

    private var rtmpCamera: RtmpCamera2? = null
    private var imageFilter: ImageObjectFilterRender? = null
    var currentMatch: MatchState? = null; private set
    var courtName: String = ""; private set
    private val handler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    private var lastHash = 0
    private var filterReady = false

    fun init(courtName: String) {
        this.courtName = courtName
        rtmpCamera = RtmpCamera2(openGlView, this)
    }

    fun updateMatch(match: MatchState?) {
        currentMatch = match
        refreshOverlay()
    }

    private fun setupFilter() {
        if (filterReady) return
        try {
            imageFilter = ImageObjectFilterRender()
            rtmpCamera?.glInterface?.setFilter(imageFilter)
            imageFilter?.setScale(100f, 100f) // full screen overlay
            imageFilter?.setPosition(TranslateTo.CENTER)
            filterReady = true
        } catch (e: Exception) {
            onStatusChange("⚠️ Filter error: ${e.message}")
        }
    }

    private fun refreshOverlay() {
        if (!filterReady) return
        val m = currentMatch
        val hash = m.hashCode() + courtName.hashCode()
        if (hash == lastHash) return
        lastHash = hash

        val w = 1280; val h = 720 // full resolution for sharp text
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (m != null) {
            if (m.paused) ScoreboardOverlay.drawPaused(canvas, w, h, m)
            else ScoreboardOverlay.draw(canvas, w, h, m)
        }
        try {
            imageFilter?.setImage(bmp)
        } catch (_: Exception) {}
    }

    fun startStream(rtmpUrl: String, streamKey: String) {
        val camera = rtmpCamera ?: return
        val fullUrl = "$rtmpUrl/$streamKey"
        if (!camera.isStreaming) {
            if (camera.prepareVideo(1280, 720, 30, 2500 * 1024, 0) &&
                camera.prepareAudio(128 * 1024, 44100, true)) {
                camera.startStream(fullUrl)
                setupFilter()
                onStatusChange("🔴 Đang stream...")
                startOverlayLoop()
            } else {
                onStatusChange("❌ Không thể khởi tạo camera/audio")
            }
        }
    }

    fun startPreview() {
        rtmpCamera?.startPreview()
        // Setup filter after preview starts (GL context ready)
        handler.postDelayed({ setupFilter(); refreshOverlay() }, 1000)
    }

    private fun startOverlayLoop() {
        overlayRunnable = object : Runnable {
            override fun run() {
                refreshOverlay()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(overlayRunnable!!)
    }

    private fun stopOverlayLoop() { overlayRunnable?.let { handler.removeCallbacks(it) } }
    fun stopStream() { rtmpCamera?.stopStream(); stopOverlayLoop(); onStatusChange("⏹ Đã dừng") }
    fun isStreaming(): Boolean = rtmpCamera?.isStreaming == true
    fun release() {
        stopOverlayLoop()
        try { if (rtmpCamera?.isStreaming == true) rtmpCamera?.stopStream(); rtmpCamera?.stopPreview() }
        catch (_: Exception) {}
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) { onStatusChange("🔄 Kết nối...") }
    override fun onConnectionSuccessRtmp() { onStatusChange("🔴 LIVE") }
    override fun onConnectionFailedRtmp(reason: String) {
        onStatusChange("❌ $reason")
        handler.postDelayed({ rtmpCamera?.reTry(5000, reason) }, 5000)
    }
    override fun onNewBitrateRtmp(bitrate: Long) {}
    override fun onDisconnectRtmp() { onStatusChange("⚠️ Mất kết nối") }
    override fun onAuthErrorRtmp() { onStatusChange("❌ Auth error") }
    override fun onAuthSuccessRtmp() {}
}
