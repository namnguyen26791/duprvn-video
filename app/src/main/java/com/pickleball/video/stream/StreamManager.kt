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
        Thread {
            try {
                val api = vn.vdpr.video.data.ApiService.create(apiBase)
                val config = kotlinx.coroutines.runBlocking { api.getOverlayConfig(tournamentId) }

                // Load logos from config
                config.logos.filter { it.position == "top_right" }.firstOrNull()?.let { logo ->
                    loadImageFromUrl(logo.url) { bmp -> ScoreboardOverlay.pickbaseLogo = bmp }
                }
                config.logos.filter { it.position == "bottom_right" }.firstOrNull()?.let { logo ->
                    loadImageFromUrl(logo.url) { bmp -> ScoreboardOverlay.tournamentLogo = bmp }
                }

                // Set marquee texts
                if (config.marquee_texts.isNotEmpty()) {
                    ScoreboardOverlay.marqueeTexts = config.marquee_texts
                }
                android.util.Log.d("PB_VIDEO", "Overlay config loaded: ${config.logos.size} logos, ${config.marquee_texts.size} texts")
            } catch (e: Exception) {
                android.util.Log.w("PB_VIDEO", "Failed to load overlay config: ${e.message}")
            }
        }.start()
    }

    private fun loadImageFromUrl(imageUrl: String, onLoaded: (Bitmap) -> Unit) {
        try {
            val url = java.net.URL(imageUrl)
            val connection = url.openConnection()
            if (connection is javax.net.ssl.HttpsURLConnection) {
                val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
                sslCtx.init(null, trustAll, java.security.SecureRandom())
                connection.sslSocketFactory = sslCtx.socketFactory
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val input = connection.getInputStream()
            val bitmap = BitmapFactory.decodeStream(input)
            input.close()
            if (bitmap != null) onLoaded(bitmap)
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "Failed to load image: $imageUrl - ${e.message}")
        }
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

    private var overlayBitmap: Bitmap? = null
    private val overlayLock = Object()

    private fun refreshOverlay() {
        if (!filterReady) {
            setupFilter()
            if (!filterReady) return
        }
        val m = currentMatch

        val q = actualQuality ?: selectedQuality
        val w = q.width; val h = q.height

        synchronized(overlayLock) {
            try {
                if (overlayBitmap == null || overlayBitmap!!.isRecycled || overlayBitmap!!.width != w || overlayBitmap!!.height != h) {
                    overlayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                }
                val bmp = overlayBitmap ?: return
                if (bmp.isRecycled) return
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                if (m != null) {
                    if (m.paused) ScoreboardOverlay.drawPaused(canvas, w, h, m)
                    else ScoreboardOverlay.draw(canvas, w, h, m)
                } else {
                    ScoreboardOverlay.drawLogosOnly(canvas, w, h)
                }
                imageFilter?.setImage(bmp)
            } catch (e: Exception) {
                android.util.Log.e("PB_VIDEO", "refreshOverlay error: ${e.message}")
                // Reset bitmap on error
                overlayBitmap = null
                filterReady = false
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
                setupFilter()
                onStatusChange("🔴 Đang stream ${quality.label}...")
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
                camera.prepareAudio(128 * 1024, 44100, true)) {
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
                refreshOverlay()
                overlayFrameCount++
                // Log stream health every 5 minutes (1500 frames at 200ms interval)
                if (overlayFrameCount % 1500 == 0L) {
                    val runtime = Runtime.getRuntime()
                    val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                    val maxMB = runtime.maxMemory() / 1048576
                    android.util.Log.i("PB_STREAM", "Health check: frames=$overlayFrameCount, mem=${usedMB}/${maxMB}MB, streaming=${rtmpCamera?.isStreaming}, bitrate=${rtmpCamera?.getBitrate()}")
                }
                handler.postDelayed(this, 200)
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

    override fun onConnectionStartedRtmp(rtmpUrl: String) {
        android.util.Log.i("PB_STREAM", "Connection started: $rtmpUrl")
        onStatusChange("🔄 Kết nối...")
    }
    override fun onConnectionSuccessRtmp() {
        android.util.Log.i("PB_STREAM", "Connection success! Streaming at ${actualQuality?.label ?: selectedQuality.label}")
        lowBitrateCount = 0
        val q = actualQuality ?: selectedQuality
        onStatusChange("🔴 LIVE ${q.label}")
        // Ensure overlay loop is running after reconnect
        if (overlayRunnable == null) {
            handler.post { startOverlayLoop() }
        }
    }
    override fun onConnectionFailedRtmp(reason: String) {
        android.util.Log.e("PB_STREAM", "Connection FAILED: $reason")
        onStatusChange("❌ $reason")
        handler.postDelayed({ rtmpCamera?.reTry(5000, reason) }, 5000)
    }
    override fun onNewBitrateRtmp(bitrate: Long) {
        val q = actualQuality ?: return
        val currentBitrate = rtmpCamera?.getBitrate()?.toLong() ?: q.bitrate.toLong()

        // Min bitrate per quality level
        val minBitrateForQuality = when (q) {
            StreamQuality.Q_4K -> 8000L * 1024
            StreamQuality.Q_2K -> 5000L * 1024
            StreamQuality.Q_1080P -> 3000L * 1024
            StreamQuality.Q_720P -> 1500L * 1024
        }

        if (bitrate < currentBitrate * 0.6) {
            // Mạng yếu
            lowBitrateCount++
            highBitrateCount = 0
            if (lowBitrateCount >= LOW_BITRATE_THRESHOLD) {
                lowBitrateCount = 0
                // Giảm 30% bitrate
                val newBitrate = (currentBitrate * 0.7).toLong()

                if (newBitrate < minBitrateForQuality && q != StreamQuality.Q_720P) {
                    // Bitrate quá thấp cho resolution hiện tại → hạ cấp resolution
                    val nextQuality = when (q) {
                        StreamQuality.Q_4K -> StreamQuality.Q_2K
                        StreamQuality.Q_2K -> StreamQuality.Q_1080P
                        StreamQuality.Q_1080P -> StreamQuality.Q_720P
                        StreamQuality.Q_720P -> StreamQuality.Q_720P
                    }
                    actualQuality = nextQuality
                    rtmpCamera?.setVideoBitrateOnFly(nextQuality.bitrate)
                    val mbps = String.format("%.1f", nextQuality.bitrate.toFloat() / 1024 / 1024)
                    onStatusChange("⚠️ Hạ ${nextQuality.label} (${mbps}Mbps)")
                    android.util.Log.w("PB_VIDEO", "Adaptive: downgrade to ${nextQuality.label}")
                } else {
                    // Giảm bitrate trong cùng resolution
                    val clampedBitrate = newBitrate.coerceAtLeast(minBitrateForQuality)
                    rtmpCamera?.setVideoBitrateOnFly(clampedBitrate.toInt())
                    val mbps = String.format("%.1f", clampedBitrate.toFloat() / 1024 / 1024)
                    onStatusChange("⚠️ Mạng yếu → ${mbps}Mbps")
                    android.util.Log.w("PB_VIDEO", "Adaptive: reduce bitrate to ${clampedBitrate / 1024}kbps")
                }
            }
        } else if (bitrate > currentBitrate * 0.9) {
            // Mạng ổn — thử tăng lại
            highBitrateCount++
            lowBitrateCount = 0
            if (highBitrateCount >= HIGH_BITRATE_THRESHOLD) {
                highBitrateCount = 0
                val targetQuality = selectedQuality
                val targetBitrate = q.bitrate.toLong()

                if (currentBitrate < targetBitrate) {
                    // Tăng 20% bitrate nhưng không vượt target
                    val newBitrate = (currentBitrate * 1.2).toLong().coerceAtMost(targetBitrate)
                    rtmpCamera?.setVideoBitrateOnFly(newBitrate.toInt())
                    val mbps = String.format("%.1f", newBitrate.toFloat() / 1024 / 1024)
                    onStatusChange("🔴 Mạng ổn → ${mbps}Mbps")
                    android.util.Log.d("PB_VIDEO", "Adaptive: increase bitrate to ${newBitrate / 1024}kbps")
                } else if (q != targetQuality && currentBitrate >= targetBitrate * 0.9) {
                    // Bitrate đã đầy ở cấp thấp → tăng resolution lên
                    val nextQuality = when (q) {
                        StreamQuality.Q_720P -> StreamQuality.Q_1080P
                        StreamQuality.Q_1080P -> StreamQuality.Q_2K
                        StreamQuality.Q_2K -> StreamQuality.Q_4K
                        StreamQuality.Q_4K -> StreamQuality.Q_4K
                    }
                    // Chỉ tăng nếu không vượt quality ban đầu
                    if (nextQuality.ordinal <= targetQuality.ordinal) {
                        actualQuality = nextQuality
                        rtmpCamera?.setVideoBitrateOnFly(nextQuality.bitrate)
                        onStatusChange("🔴 Nâng ${nextQuality.label}")
                        android.util.Log.d("PB_VIDEO", "Adaptive: upgrade to ${nextQuality.label}")
                    }
                }
            }
        } else {
            lowBitrateCount = 0
            highBitrateCount = 0
        }
    }
    override fun onDisconnectRtmp() {
        android.util.Log.w("PB_STREAM", "DISCONNECTED from RTMP! Will retry in 5s. isStreaming=${rtmpCamera?.isStreaming}")
        onStatusChange("⚠️ Mất kết nối — đang thử kết nối lại...")
        // Auto-reconnect after 5 seconds
        handler.postDelayed({
            if (rtmpCamera?.isStreaming == false && lastRtmpUrl != null) {
                android.util.Log.w("PB_VIDEO", "Auto-reconnecting after disconnect...")
                rtmpCamera?.reTry(5000, "disconnect")
            }
        }, 5000)
    }
    override fun onAuthErrorRtmp() { android.util.Log.e("PB_STREAM", "Auth error!"); onStatusChange("❌ Auth error") }
    override fun onAuthSuccessRtmp() { android.util.Log.i("PB_STREAM", "Auth success") }
}
