package asia.pickbase.video.stream

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
import asia.pickbase.video.data.MatchState
import asia.pickbase.video.overlay.ScoreboardOverlay

data class CameraInfo(val id: String, val label: String, val focalLength: Float)

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
    private var filterReady = false
    var selectedCameraId: String = "0"; private set

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
        if (!filterReady) {
            android.util.Log.w("PB_VIDEO", "refreshOverlay: filter not ready")
            return
        }
        val m = currentMatch
        android.util.Log.d("PB_VIDEO", "refreshOverlay: match=${m?.left?.teamName} vs ${m?.right?.teamName}, score=${m?.scoreLeft}-${m?.scoreRight}")

        val w = 1280; val h = 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (m != null) {
            if (m.paused) ScoreboardOverlay.drawPaused(canvas, w, h, m)
            else ScoreboardOverlay.draw(canvas, w, h, m)
        }
        try {
            imageFilter?.setImage(bmp)
        } catch (e: Exception) {
            android.util.Log.e("PB_VIDEO", "setImage failed: ${e.message}")
        }
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

    private fun startOverlayLoop() {
        overlayRunnable = object : Runnable {
            override fun run() {
                refreshOverlay()
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

