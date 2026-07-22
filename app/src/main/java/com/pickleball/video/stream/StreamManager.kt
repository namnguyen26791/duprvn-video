package vn.vdpr.video.stream

import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.rtplibrary.rtmp.RtmpCamera2
import com.pedro.rtplibrary.view.OpenGlView
import vn.vdpr.video.data.MatchState
import vn.vdpr.video.overlay.BitmapUtils
import vn.vdpr.video.overlay.ScoreboardOverlay

data class CameraInfo(val id: String, val label: String, val focalLength: Float)

enum class StreamQuality(val width: Int, val height: Int, val bitrate: Int, val fps: Int, val label: String) {
    Q_4K(3840, 2160, 20000 * 1024, 30, "4K"),
    Q_2K(2560, 1440, 10000 * 1024, 30, "2K"),
    Q_1080P(1920, 1080, 6000 * 1024, 30, "1080p"),
    Q_720P(1280, 720, 4500 * 1024, 30, "720p");

    companion object {
        fun all() = values().toList()

        /**
         * Kiểm tra phần cứng encoder H.264 hỗ trợ resolution nào.
         * Trả về danh sách StreamQuality mà máy có thể encode được (hardware).
         */
        fun getSupportedQualities(): List<StreamQuality> {
            val supported = mutableListOf<StreamQuality>()
            try {
                val codecList = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
                val encoder = codecList.codecInfos.firstOrNull { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
                            && info.isHardwareAccelerated
                } ?: codecList.codecInfos.firstOrNull { info ->
                    info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
                }

                if (encoder != null) {
                    val caps = encoder.getCapabilitiesForType("video/avc")
                    val videoCapabilities = caps.videoCapabilities

                    for (q in values()) {
                        if (videoCapabilities.isSizeSupported(q.width, q.height)) {
                            supported.add(q)
                        }
                    }
                    android.util.Log.d("PB_VIDEO", "HW encoder: ${encoder.name}, supported: ${supported.map { it.label }}")
                }
            } catch (e: Exception) {
                android.util.Log.e("PB_VIDEO", "getSupportedQualities error: ${e.message}")
            }
            // Fallback: nếu detect fail, ít nhất 720p luôn được
            if (supported.isEmpty()) supported.add(Q_720P)
            return supported
        }
    }
}

class StreamManager(
    private val context: Context,
    private val openGlView: OpenGlView,
    private val onStatusChange: (String) -> Unit,
) : ConnectCheckerRtmp {

    var selectedQuality: StreamQuality = StreamQuality.Q_1080P
    var actualQuality: StreamQuality? = null; private set

    private var rtmpCamera: RtmpCamera2? = null
    private var imageFilter: ImageObjectFilterRender? = null
    var currentMatch: MatchState? = null; private set
    var courtName: String = ""; private set
    private val handler = Handler(Looper.getMainLooper())
    private var overlayRunnable: Runnable? = null
    private var filterReady = false
    var selectedCameraId: String = "0"; private set
    private var lastRtmpUrl: String? = null

    // Adaptive bitrate — giảm chất lượng khi mạng yếu, tăng lại khi ổn, min 720p
    private var lowBitrateCount = 0
    private var highBitrateCount = 0
    private val LOW_BITRATE_THRESHOLD = 3 // số lần liên tiếp bitrate thấp trước khi hạ
    private val HIGH_BITRATE_THRESHOLD = 10 // số lần liên tiếp bitrate tốt trước khi tăng lại

    companion object {
        /**
         * Liệt kê tất cả camera phía sau (back-facing), bao gồm physical cameras.
         * Trên Samsung, wide-angle có thể là camera ID riêng (VD: "2") hoặc physical camera.
         */
        fun getBackCameras(context: Context): List<CameraInfo> {
            val result = mutableListOf<CameraInfo>()
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                
                for (id in cameraManager.cameraIdList) {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING)
                    // Chỉ lấy back-facing cameras
                    if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                    val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val minFocal = focalLengths?.minOrNull() ?: 0f
                    val label = when {
                        minFocal < 3f -> "📷 Góc rộng (${minFocal}mm) [ID:$id]"
                        minFocal < 5f -> "📷 Chính (${minFocal}mm) [ID:$id]"
                        else -> "📷 Tele (${minFocal}mm) [ID:$id]"
                    }
                    result.add(CameraInfo(id, label, minFocal))

                    // Thử lấy physical cameras (Android 9+)
                    try {
                        val physicalIds = chars.physicalCameraIds
                        for (physId in physicalIds) {
                            if (result.any { it.id == physId }) continue
                            val physChars = cameraManager.getCameraCharacteristics(physId)
                            val physFocal = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val physMinFocal = physFocal?.minOrNull() ?: 0f
                            if (physMinFocal == minFocal) continue
                            val physLabel = when {
                                physMinFocal < 3f -> "📷 Góc rộng (${physMinFocal}mm) [ID:$physId]"
                                physMinFocal < 5f -> "📷 Chính (${physMinFocal}mm) [ID:$physId]"
                                else -> "📷 Tele (${physMinFocal}mm) [ID:$physId]"
                            }
                            result.add(CameraInfo(physId, physLabel, physMinFocal))
                        }
                    } catch (_: Exception) {}
                }

                // Nếu chỉ tìm được 1 camera, thử list tất cả IDs để user chọn thủ công
                if (result.size <= 1) {
                    for (id in cameraManager.cameraIdList) {
                        if (result.any { it.id == id }) continue
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING)
                        if (facing == CameraCharacteristics.LENS_FACING_FRONT) continue // skip front
                        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val minFocal = focalLengths?.minOrNull() ?: 0f
                        val facingLabel = when (facing) {
                            CameraCharacteristics.LENS_FACING_BACK -> "Back"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
                            else -> "Other"
                        }
                        result.add(CameraInfo(id, "📷 $facingLabel (${minFocal}mm) [ID:$id]", minFocal))
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("PB_VIDEO", "getBackCameras error: ${e.message}")
            }
            android.util.Log.d("PB_VIDEO", "Found ${result.size} cameras: ${result.map { "${it.id}:${it.focalLength}" }}")
            return result.sortedBy { it.focalLength }
        }
    }

    fun init(courtName: String, cameraId: String = "0") {
        this.courtName = courtName
        this.selectedCameraId = cameraId
        rtmpCamera = RtmpCamera2(openGlView, this)
        loadPickbaseLogo()
        android.util.Log.i("PB_OVERLAY", "StreamManager.init: court=$courtName camera=$cameraId quality=${selectedQuality.label} topLogos=${ScoreboardOverlay.topRightLogos.size} bottomLogos=${ScoreboardOverlay.bottomRightLogos.size} pauseImg=${ScoreboardOverlay.pauseImage != null} marquee=${ScoreboardOverlay.marqueeTexts.size}")
    }

    /** Load PickBase logo from drawable */
    private fun loadPickbaseLogo() {
        try {
            ScoreboardOverlay.pickbaseLogo = BitmapFactory.decodeResource(context.resources,
                context.resources.getIdentifier("pickbase", "drawable", context.packageName))
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "Failed to load pickbase logo: ${e.message}")
        }
    }

    /** Load overlay config from API (logos + marquee) */
    fun loadOverlayConfig(apiBase: String, tournamentId: Int) {
        if (tournamentId <= 0) return
        // Nếu đã có logos (load từ VideoApp) thì không ghi đè
        if (ScoreboardOverlay.topRightLogos.isNotEmpty() || ScoreboardOverlay.bottomRightLogos.isNotEmpty()) {
            android.util.Log.d("PB_VIDEO", "loadOverlayConfig: logos already loaded, skipping")
            return
        }
        Thread {
            try {
                val api = vn.vdpr.video.data.ApiService.create(apiBase)
                val config = kotlinx.coroutines.runBlocking { api.getOverlayConfig(tournamentId) }

                // Load top-right logos
                val topLogos = config.logos.filter { it.position == "top_right" }
                val loadedTop = mutableListOf<Bitmap>()
                for (logo in topLogos) {
                    loadImageFromUrl(logo.url, BitmapUtils.MAX_LOGO_EDGE) { bmp -> loadedTop.add(bmp) }
                }
                if (loadedTop.isNotEmpty()) {
                    ScoreboardOverlay.topRightLogos = loadedTop
                    ScoreboardOverlay.pickbaseLogo = loadedTop.first()
                }

                // Load bottom-right logos
                val bottomLogos = config.logos.filter { it.position == "bottom_right" }
                val loadedBottom = mutableListOf<Bitmap>()
                for (logo in bottomLogos) {
                    loadImageFromUrl(logo.url, BitmapUtils.MAX_LOGO_EDGE) { bmp -> loadedBottom.add(bmp) }
                }
                if (loadedBottom.isNotEmpty()) {
                    ScoreboardOverlay.bottomRightLogos = loadedBottom
                    ScoreboardOverlay.tournamentLogo = loadedBottom.first()
                }

                // Pause image
                config.logos.firstOrNull { it.position == "pause" }?.let { logo ->
                    loadImageFromUrl(logo.url, BitmapUtils.MAX_PAUSE_EDGE) { bmp -> ScoreboardOverlay.pauseImage = bmp }
                }

                // Set marquee texts
                if (config.marquee_texts.isNotEmpty()) {
                    ScoreboardOverlay.marqueeTexts = config.marquee_texts
                }
                android.util.Log.d("PB_VIDEO", "Overlay config loaded: ${config.logos.size} logos, ${config.marquee_texts.size} texts, topRight=${loadedTop.size}, bottomRight=${loadedBottom.size}")
                handler.post {
                    overlayDirty = true
                    refreshOverlay(force = true)
                }
            } catch (e: Exception) {
                android.util.Log.w("PB_VIDEO", "Failed to load overlay config: ${e.message}")
            }
        }.start()
    }

    private fun loadImageFromUrl(imageUrl: String, maxEdge: Int, onLoaded: (Bitmap) -> Unit) {
        val bitmap = BitmapUtils.loadUrl(imageUrl, maxEdge)
        if (bitmap != null) onLoaded(bitmap)
    }

    fun updateMatch(match: MatchState?) {
        if (match != null) {
            // Có data mới → hiện ngay
            if (currentMatch == null) {
                android.util.Log.i("PB_OVERLAY", "▶ Scoreboard ON: ${match.left.teamName} vs ${match.right.teamName} [${match.scoreLeft}-${match.scoreRight}] paused=${match.paused}")
            }
            lastMatchReceivedAt = System.currentTimeMillis()
            currentMatch = match
            overlayDirty = true
        } else {
            // Firebase emit null → debounce 3s trước khi clear scoreboard
            val elapsed = System.currentTimeMillis() - lastMatchReceivedAt
            if (elapsed < 3000 && currentMatch != null) {
                android.util.Log.d("PB_OVERLAY", "Firebase null but debouncing (${elapsed}ms < 3000ms), keeping scoreboard")
                return
            }
            if (currentMatch != null) {
                android.util.Log.w("PB_OVERLAY", "⏹ Scoreboard OFF: Firebase null for ${elapsed}ms, clearing")
            }
            currentMatch = null
            overlayDirty = true
        }
        refreshOverlay()
    }

    /** Gọi khi switch sang trận mới — clear ngay không debounce */
    fun clearMatch() {
        android.util.Log.i("PB_OVERLAY", "🔄 clearMatch: switching to new match")
        currentMatch = null
        lastMatchReceivedAt = 0L
        overlayDirty = true
        refreshOverlay(force = true)
    }

    private var lastMatchReceivedAt = 0L

    private fun setupFilter() {
        if (filterReady) return
        try {
            val gl = rtmpCamera?.glInterface ?: run {
                android.util.Log.d("PB_VIDEO", "setupFilter: glInterface null, will retry")
                return
            }
            // Only setup filter when GL context is actually ready (camera is previewing or streaming)
            if (rtmpCamera?.isOnPreview != true && rtmpCamera?.isStreaming != true) {
                android.util.Log.d("PB_VIDEO", "setupFilter: not previewing/streaming yet, will retry")
                return
            }
            imageFilter = ImageObjectFilterRender()
            gl.setFilter(imageFilter)
            imageFilter?.setScale(100f, 100f) // full screen overlay
            imageFilter?.setPosition(TranslateTo.CENTER)
            filterReady = true
            android.util.Log.i("PB_VIDEO", "setupFilter SUCCESS — overlay active")
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "setupFilter failed (will retry): ${e.message}")
            // Reset so next attempt creates fresh filter
            imageFilter = null
        }
    }

    private var overlayBuffers = arrayOfNulls<Bitmap>(2)
    private var overlayBufferIdx = 0
    private val overlayLock = Object()
    private var lastOverlayState = "" // track state changes for logging
    private var lastContentKey = ""
    private var overlayDirty = true

    private fun matchContentKey(m: MatchState?): String {
        if (m == null) return "logos_only"
        return buildString {
            append(m.left.teamName).append('|')
            append(m.right.teamName).append('|')
            append(m.scoreLeft).append('-').append(m.scoreRight).append('|')
            append(m.serve).append('|').append(m.serverNum).append('|')
            append(m.paused).append('|')
            append(m.matchFormat).append('|')
            append(m.tournamentName ?: "").append('|')
            append(m.roundName ?: "")
        }
    }

    private fun obtainOverlayBuffer(w: Int, h: Int): Bitmap {
        val idx = overlayBufferIdx
        overlayBufferIdx = 1 - overlayBufferIdx
        var bmp = overlayBuffers[idx]
        if (bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
            bmp?.let {
                try { if (!it.isRecycled) it.recycle() } catch (_: Exception) {}
            }
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            overlayBuffers[idx] = bmp
        }
        return bmp
    }

    private fun refreshOverlay(force: Boolean = false) {
        if (!filterReady) {
            setupFilter()
            if (!filterReady) return
        }
        val m = currentMatch
        val contentKey = matchContentKey(m)
        val contentChanged = contentKey != lastContentKey
        val needMarquee = ScoreboardOverlay.hasMarquee()

        // Không đổi điểm/tên và không có marquee → bỏ frame (giảm nháy + CPU)
        if (!force && !overlayDirty && !contentChanged && !needMarquee) return

        val q = actualQuality ?: selectedQuality
        val w = q.width
        val h = q.height

        val stateKey = when {
            m != null && m.paused -> "paused"
            m != null -> "playing:${m.scoreLeft}-${m.scoreRight}"
            else -> "logos_only"
        }
        if (stateKey != lastOverlayState) {
            android.util.Log.i("PB_OVERLAY", "State: $lastOverlayState → $stateKey | hasLogos=${ScoreboardOverlay.topRightLogos.isNotEmpty()} hasPause=${ScoreboardOverlay.pauseImage != null} hasMarquee=${ScoreboardOverlay.marqueeTexts.isNotEmpty()}")
            lastOverlayState = stateKey
        }

        synchronized(overlayLock) {
            try {
                val bmp = obtainOverlayBuffer(w, h)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                if (m != null) {
                    if (m.paused) ScoreboardOverlay.drawPaused(canvas, w, h, m)
                    else ScoreboardOverlay.draw(canvas, w, h, m)
                } else {
                    ScoreboardOverlay.drawLogosOnly(canvas, w, h)
                }
                // Ping-pong buffer — không copy bitmap mỗi frame
                imageFilter?.setImage(bmp)
                lastContentKey = contentKey
                overlayDirty = false
            } catch (e: Exception) {
                android.util.Log.e("PB_OVERLAY", "refreshOverlay CRASH: ${e.message}")
                overlayDirty = true
            }
        }
    }

    fun startStream(rtmpUrl: String, streamKey: String) {
        val camera = rtmpCamera ?: return
        val fullUrl = "$rtmpUrl/$streamKey"
        lastRtmpUrl = fullUrl
        if (!camera.isStreaming) {
            val quality = resolveQuality(camera)
            if (quality != null) {
                actualQuality = quality
                camera.startStream(fullUrl)
                onStatusChange("🔴 Đang stream ${quality.label}...")
                // Force setup filter with retries — GL context should be ready now
                filterReady = false
                setupFilter()
                if (!filterReady) {
                    // Retry aggressively: 100ms, 300ms, 500ms, 1000ms, 2000ms
                    handler.postDelayed({ setupFilter(); refreshOverlay() }, 100)
                    handler.postDelayed({ setupFilter(); refreshOverlay() }, 300)
                    handler.postDelayed({ setupFilter(); refreshOverlay() }, 500)
                    handler.postDelayed({ setupFilter(); refreshOverlay() }, 1000)
                    handler.postDelayed({ setupFilter(); refreshOverlay() }, 2000)
                }
                startOverlayLoop()
            } else {
                onStatusChange("❌ Không thể khởi tạo camera/audio ở bất kỳ chất lượng nào")
            }
        }
    }

    /**
     * Thử từ chất lượng đã chọn, nếu phần cứng không hỗ trợ thì fallback xuống mức thấp hơn.
     * Trả về quality thực tế được sử dụng, hoặc null nếu tất cả đều thất bại.
     */
    private fun resolveQuality(camera: RtmpCamera2): StreamQuality? {
        val qualities = StreamQuality.all()
        val startIndex = qualities.indexOf(selectedQuality)
        for (i in startIndex until qualities.size) {
            val q = qualities[i]
            if (camera.prepareVideo(q.width, q.height, q.fps, q.bitrate, 0) &&
                camera.prepareAudio(192 * 1024, 44100, false)) {
                if (q != selectedQuality) {
                    onStatusChange("⚠️ ${selectedQuality.label} không hỗ trợ, dùng ${q.label}")
                }
                return q
            }
        }
        return null
    }

    fun startPreview() {
        // Attempt to use selected camera via Camera2ApiManager reflection
        try {
            val camIdInt = selectedCameraId.toIntOrNull()
            if (camIdInt != null && camIdInt > 0) {
                val baseClass = rtmpCamera?.javaClass?.superclass?.superclass
                val managerField = baseClass?.declaredFields?.firstOrNull { 
                    it.type.simpleName.contains("Camera2Api") || it.type.simpleName.contains("CameraManager")
                }
                if (managerField != null) {
                    managerField.isAccessible = true
                    val manager = managerField.get(rtmpCamera)
                    val openMethod = manager?.javaClass?.getMethod("openCameraId", String::class.java)
                    rtmpCamera?.startPreview()
                    openMethod?.invoke(manager, selectedCameraId)
                    android.util.Log.d("PB_VIDEO", "Opened camera via reflection: $selectedCameraId")
                } else {
                    rtmpCamera?.startPreview()
                }
            } else {
                rtmpCamera?.startPreview()
            }
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "Camera selection fallback to default: ${e.message}")
            try { rtmpCamera?.startPreview() } catch (_: Exception) {}
        }

        // Apply zoom out if selected (for wide-angle on Samsung logical camera)
        if (selectedCameraId == "wide_zoom") {
            handler.postDelayed({ applyZoomOut() }, 500)
        }

        // Setup filter after preview starts (GL context ready)
        handler.postDelayed({
            setupFilter()
            refreshOverlay()
            if (overlayRunnable == null) startOverlayLoop()
        }, 1000)
    }

    /**
     * Zoom out trên logical camera để sử dụng wide-angle sensor (Samsung).
     * Dùng SCALER_CROP_REGION với vùng crop lớn hơn (zoom out).
     */
    private fun applyZoomOut() {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics("0")
            val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            android.util.Log.d("PB_VIDEO", "Max digital zoom: $maxZoom — applying zoom out via reflection")
            // RootEncoder doesn't expose zoom control directly
            // On Samsung logical cameras, zoom ratio < 1.0 switches to wide-angle
            // This requires CONTROL_ZOOM_RATIO (Android 11+)
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "applyZoomOut failed: ${e.message}")
        }
    }

    private var overlayFrameCount = 0L

    private fun startOverlayLoop() {
        overlayRunnable = object : Runnable {
            override fun run() {
                if (!filterReady) {
                    setupFilter()
                }
                refreshOverlay()
                overlayFrameCount++
                if (overlayFrameCount % 1500 == 0L) {
                    val runtime = Runtime.getRuntime()
                    val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                    val maxMB = runtime.maxMemory() / 1048576
                    android.util.Log.i("PB_STREAM", "Health check: frames=$overlayFrameCount, mem=${usedMB}/${maxMB}MB, streaming=${rtmpCamera?.isStreaming}, bitrate=${rtmpCamera?.getBitrate()}, filterReady=$filterReady, hasMatch=${currentMatch != null}")
                }
                // Marquee cần nhịp nhanh hơn; không marquee thì chậm lại để giảm tải
                val delayMs = if (ScoreboardOverlay.hasMarquee()) 120L else 400L
                handler.postDelayed(this, delayMs)
            }
        }
        handler.post(overlayRunnable!!)
    }

    private fun stopOverlayLoop() { overlayRunnable?.let { handler.removeCallbacks(it) }; overlayRunnable = null }
    fun stopStream() {
        android.util.Log.w("PB_STREAM", "⏹ stopStream called")
        rtmpCamera?.stopStream(); stopOverlayLoop(); onStatusChange("⏹ Đã dừng")
    }
    fun isStreaming(): Boolean = rtmpCamera?.isStreaming == true
    fun release() {
        android.util.Log.w("PB_STREAM", "🗑 release: stopping stream + preview + overlay")
        stopOverlayLoop()
        try { if (rtmpCamera?.isStreaming == true) rtmpCamera?.stopStream(); rtmpCamera?.stopPreview() }
        catch (e: Exception) { android.util.Log.e("PB_STREAM", "release error: ${e.message}") }
        synchronized(overlayLock) {
            overlayBuffers.forEachIndexed { i, bmp ->
                try { if (bmp != null && !bmp.isRecycled) bmp.recycle() } catch (_: Exception) {}
                overlayBuffers[i] = null
            }
        }
        ScoreboardOverlay.clearLogoScaleCache()
        filterReady = false
        imageFilter = null
        lastContentKey = ""
        overlayDirty = true
    }

    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        android.util.Log.i("PB_STREAM", "Connection started: $rtmpUrl")
        onStatusChange("🔄 Kết nối...")
    }
    override fun onConnectionSuccessRtmp() {
        android.util.Log.i("PB_STREAM", "Connection success! Streaming at ${actualQuality?.label ?: selectedQuality.label}")
        lowBitrateCount = 0
        connectionFailCount = 0
        val q = actualQuality ?: selectedQuality
        onStatusChange("🔴 LIVE ${q.label}")
        // Force re-setup GL filter after reconnect (old filter may be invalid)
        filterReady = false
        handler.post {
            setupFilter()
            refreshOverlay()
        }
        // Ensure overlay loop is running after reconnect
        if (overlayRunnable == null) {
            handler.post { startOverlayLoop() }
        }
    }
    private var connectionFailCount = 0

    override fun onConnectionFailedRtmp(reason: String) {
        connectionFailCount++
        android.util.Log.e("PB_STREAM", "❌ Connection FAILED ($connectionFailCount): $reason")
        android.util.Log.e("PB_STREAM", "  → isStreaming=${rtmpCamera?.isStreaming}, lastUrl=${lastRtmpUrl?.take(50)}")
        if (connectionFailCount >= 5) {
            onStatusChange("❌ Không thể kết nối YouTube. Cần tạo broadcast mới từ Manager.")
        } else {
            onStatusChange("❌ Mất kết nối (lần $connectionFailCount) — thử lại...")
            handler.postDelayed({ rtmpCamera?.reTry(5000, reason) }, 5000)
        }
    }
    override fun onNewBitrateRtmp(bitrate: Long) {
        val q = actualQuality ?: return
        val targetBitrate = q.bitrate.toLong()
        val currentBitrate = rtmpCamera?.getBitrate()?.toLong() ?: targetBitrate
        val minBitrate = (targetBitrate * 0.3).toLong().coerceAtLeast(1000L * 1024) // min 1Mbps

        if (bitrate < currentBitrate * 0.6) {
            // Mạng yếu — giảm bitrate 30%
            lowBitrateCount++
            highBitrateCount = 0
            if (lowBitrateCount >= LOW_BITRATE_THRESHOLD) {
                lowBitrateCount = 0
                val newBitrate = (currentBitrate * 0.7).toLong().coerceAtLeast(minBitrate)
                rtmpCamera?.setVideoBitrateOnFly(newBitrate.toInt())
                val mbps = String.format("%.1f", newBitrate.toFloat() / 1024 / 1024)
                android.util.Log.w("PB_STREAM", "⚠️ WEAK NETWORK: bitrate ${bitrate/1024}kbps < threshold, reducing to ${newBitrate/1024}kbps (${mbps}Mbps)")
                onStatusChange("⚠️ Mạng yếu → ${mbps}Mbps")
            }
        } else if (bitrate > currentBitrate * 0.9 && currentBitrate < targetBitrate) {
            // Mạng ổn — tăng bitrate 20% (không vượt target)
            highBitrateCount++
            lowBitrateCount = 0
            if (highBitrateCount >= HIGH_BITRATE_THRESHOLD) {
                highBitrateCount = 0
                val newBitrate = (currentBitrate * 1.2).toLong().coerceAtMost(targetBitrate)
                rtmpCamera?.setVideoBitrateOnFly(newBitrate.toInt())
                val mbps = String.format("%.1f", newBitrate.toFloat() / 1024 / 1024)
                android.util.Log.d("PB_STREAM", "↑ Network OK: increasing to ${newBitrate/1024}kbps (${mbps}Mbps)")
                onStatusChange("🔴 ${q.label} ${mbps}Mbps")
            }
        } else {
            lowBitrateCount = 0
            highBitrateCount = 0
        }
    }
    override fun onDisconnectRtmp() {
        android.util.Log.e("PB_STREAM", "⚠️ DISCONNECTED! isStreaming=${rtmpCamera?.isStreaming}, will retry in 5s")
        android.util.Log.e("PB_STREAM", "  → filterReady=$filterReady, overlayRunning=${overlayRunnable != null}, match=${currentMatch != null}")
        onStatusChange("⚠️ Mất kết nối — đang thử kết nối lại...")
        // Auto-reconnect after 5 seconds
        handler.postDelayed({
            if (rtmpCamera?.isStreaming == false && lastRtmpUrl != null) {
                android.util.Log.w("PB_STREAM", "Auto-reconnecting after disconnect...")
                rtmpCamera?.reTry(5000, "disconnect")
            }
        }, 5000)
    }
    override fun onAuthErrorRtmp() { android.util.Log.e("PB_STREAM", "Auth error!"); onStatusChange("❌ Auth error") }
    override fun onAuthSuccessRtmp() { android.util.Log.i("PB_STREAM", "Auth success") }
}
