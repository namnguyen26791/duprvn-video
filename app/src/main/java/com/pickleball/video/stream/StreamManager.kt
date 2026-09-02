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
import vn.vdpr.video.R
import vn.vdpr.video.commentary.CommentaryEngine
import vn.vdpr.video.data.MatchState
import vn.vdpr.video.overlay.BitmapUtils
import vn.vdpr.video.overlay.OverlayCache
import vn.vdpr.video.overlay.ScoreboardOverlay

data class CameraInfo(val id: String, val label: String, val focalLength: Float)

enum class StreamQuality(val width: Int, val height: Int, val bitrate: Int, val fps: Int, val label: String) {
    Q_4K(3840, 2160, 20000 * 1024, 30, "4K"),
    Q_2K(2560, 1440, 10000 * 1024, 30, "2K"),
    Q_1080P(1920, 1080, 6000 * 1024, 30, "1080p"),
    Q_720P(1280, 720, 4500 * 1024, 30, "720p");

    companion object {
        fun all() = values().toList()

        /** Mặc định 720p nếu hỗ trợ — máy ít nóng hơn; user vẫn chọn 4K được. */
        fun preferredDefault(supported: List<StreamQuality>): StreamQuality {
            if (supported.isEmpty()) return Q_720P
            if (supported.contains(Q_720P)) return Q_720P
            return supported.minByOrNull { it.height } ?: Q_720P
        }

        fun displayOrder() = listOf(Q_720P, Q_1080P, Q_2K, Q_4K)

        /**
         * Theo encoder H.264 như trước — không chặn 4K theo heap/camera
         * (camera preview size ≠ encode size; trước đây 4K vẫn live được).
         */
        fun getSupportedQualities(context: Context? = null): List<StreamQuality> {
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

    var selectedQuality: StreamQuality = StreamQuality.Q_720P
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
    private val commentary = CommentaryEngine(context)

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
        ThermalHelper.start(context)
        loadPickbaseLogo()
        // Flags TTS sẽ restore sau init với đúng tids (StreamActivity.restoreOverlayFlags)
        android.util.Log.i("PB_OVERLAY", "StreamManager.init: court=$courtName camera=$cameraId quality=${selectedQuality.label} topLogos=${ScoreboardOverlay.topRightLogos.size} bottomLogos=${ScoreboardOverlay.bottomRightLogos.size} pauseImg=${ScoreboardOverlay.pauseImage != null} marquee=${ScoreboardOverlay.marqueeTexts.size}")
    }

    /** Áp flags đã lưu (thoát app / process kill) — không chờ API. */
    fun restoreOverlayFlags(expectedTids: Set<Int> = emptySet()) {
        val flags = OverlayCache.loadFlags(context, expectedTids)
        ScoreboardOverlay.introEnabled = flags.introScorebug
        commentary.density = when (flags.commentaryDensity.lowercase()) {
            "low" -> CommentaryEngine.Density.LOW
            "high" -> CommentaryEngine.Density.HIGH
            else -> CommentaryEngine.Density.MEDIUM
        }
        commentary.applyEnabled(flags.autoCommentary)
        android.util.Log.i(
            "PB_OVERLAY",
            "restoreOverlayFlags: commentary=${flags.autoCommentary} intro=${flags.introScorebug} density=${flags.commentaryDensity}",
        )
    }

    /** Logo brand trên bảng tỉ số / scorebug — luôn từ res/drawable/logo_overlay.png */
    private fun loadPickbaseLogo() {
        reloadBrandLogo()
    }

    /** Ép nạp logo_overlay (sau clearAll / swap API / init). */
    private fun reloadBrandLogo() {
        try {
            val decoded = BitmapFactory.decodeResource(context.resources, R.drawable.logo_overlay)
            if (decoded != null) {
                val old = ScoreboardOverlay.pickbaseLogo
                ScoreboardOverlay.pickbaseLogo = decoded
                if (old != null && !old.isRecycled &&
                    old !== decoded &&
                    ScoreboardOverlay.topRightLogos.none { it === old } &&
                    ScoreboardOverlay.bottomRightLogos.none { it === old } &&
                    ScoreboardOverlay.tournamentLogo !== old &&
                    ScoreboardOverlay.pauseImage !== old
                ) {
                    try { old.recycle() } catch (_: Exception) {}
                }
                android.util.Log.i("PB_VIDEO", "Brand logo_overlay.png loaded for scoreboard/scorebug")
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "reloadBrandLogo logo_overlay: ${e.message}")
        }
        try {
            ScoreboardOverlay.pickbaseLogo =
                BitmapFactory.decodeResource(context.resources, R.drawable.logo)
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "reloadBrandLogo fallback logo: ${e.message}")
        }
    }

    /**
     * Load overlay config from API (logos + marquee) — một hoặc nhiều giải.
     * @param force luôn gọi API (bỏ qua RAM) — dùng khi mở StreamActivity để chắc logo đúng giải.
     */
    fun loadOverlayConfig(
        apiBase: String,
        tournamentId: Int,
        expectedTournamentIds: Set<Int> = emptySet(),
        force: Boolean = false,
    ) {
        val tids = when {
            expectedTournamentIds.isNotEmpty() -> expectedTournamentIds
            tournamentId > 0 -> setOf(tournamentId)
            else -> return
        }

        if (!force &&
            ScoreboardOverlay.loadedTournamentIds == tids &&
            ScoreboardOverlay.hasCornerLogos()
        ) {
            android.util.Log.d("PB_VIDEO", "loadOverlayConfig: already loaded for tids=$tids — skip")
            return
        }

        Thread {
            try {
                loadOverlayFromApi(apiBase, tids.sorted())
            } catch (e: Exception) {
                android.util.Log.w("PB_VIDEO", "Failed to load overlay config: ${e.message}")
            }
        }.start()
    }

    private fun loadOverlayFromApi(apiBase: String, tournamentIds: List<Int>) {
        val api = vn.vdpr.video.data.ApiService.create(apiBase)

        val loadedTop = mutableListOf<Bitmap>()
        val loadedBottom = mutableListOf<Bitmap>()
        val mergedMarquee = mutableListOf<String>()
        var mergedPause: Bitmap? = null
        val seenTopUrls = mutableSetOf<String>()
        val seenBottomUrls = mutableSetOf<String>()
        var autoCommentary = false
        var introScorebug = false
        var density = CommentaryEngine.Density.MEDIUM
        var gotAnyConfig = false

        for (tid in tournamentIds) {
            try {
                val config = kotlinx.coroutines.runBlocking { api.getOverlayConfig(tid) }
                gotAnyConfig = true

                // Flag từ giải đầu tiên (hoặc OR: bật nếu bất kỳ giải nào bật)
                if (tid == tournamentIds.first()) {
                    autoCommentary = config.auto_commentary
                    introScorebug = config.intro_scorebug
                    density = when (config.commentary_density.lowercase()) {
                        "low" -> CommentaryEngine.Density.LOW
                        "high" -> CommentaryEngine.Density.HIGH
                        else -> CommentaryEngine.Density.MEDIUM
                    }
                } else {
                    autoCommentary = autoCommentary || config.auto_commentary
                    introScorebug = introScorebug || config.intro_scorebug
                }

                for (logo in config.logos.filter { it.position == "top_right" }) {
                    if (!seenTopUrls.add(logo.url)) continue
                    loadImageFromUrl(logo.url, BitmapUtils.MAX_LOGO_EDGE) { bmp -> loadedTop.add(bmp) }
                }
                for (logo in config.logos.filter { it.position == "bottom_right" }) {
                    if (!seenBottomUrls.add(logo.url)) continue
                    loadImageFromUrl(logo.url, BitmapUtils.MAX_LOGO_EDGE) { bmp -> loadedBottom.add(bmp) }
                }
                if (mergedPause == null) {
                    config.logos.firstOrNull { it.position == "pause" }?.let { logo ->
                        loadImageFromUrl(logo.url, BitmapUtils.MAX_PAUSE_EDGE) { bmp -> mergedPause = bmp }
                    }
                }
                for (text in config.marquee_texts) {
                    if (text.isNotBlank() && text !in mergedMarquee) mergedMarquee.add(text)
                }
            } catch (e: Exception) {
                android.util.Log.w("PB_VIDEO", "Overlay tid=$tid failed: ${e.message}")
            }
        }

        // Swap trên main + overlayLock — tránh recycle logo đang vẽ / GL còn đọc
        handler.post {
            synchronized(overlayLock) {
                val apiHasVisual =
                    loadedTop.isNotEmpty() || loadedBottom.isNotEmpty() ||
                        mergedPause != null || mergedMarquee.isNotEmpty()
                val hadVisual =
                    ScoreboardOverlay.hasCornerLogos() ||
                        ScoreboardOverlay.pickbaseLogo != null ||
                        ScoreboardOverlay.pauseImage != null ||
                        ScoreboardOverlay.hasMarquee()

                if (apiHasVisual) {
                    // Có data mới → swap; giữ intro đang chạy
                    ScoreboardOverlay.clearAll(recycleBitmaps = false, clearIntroState = false)
                    ScoreboardOverlay.topRightLogos = loadedTop
                    ScoreboardOverlay.bottomRightLogos = loadedBottom
                    ScoreboardOverlay.marqueeTexts = mergedMarquee
                    ScoreboardOverlay.pauseImage = mergedPause
                    // Header scoreboard/scorebug luôn logo_overlay — không dùng logo góc API
                    reloadBrandLogo()
                    if (loadedBottom.isNotEmpty()) ScoreboardOverlay.tournamentLogo = loadedBottom.first()
                } else if (!hadVisual) {
                    // API trống và RAM trống → logo brand app
                    reloadBrandLogo()
                    android.util.Log.w("PB_VIDEO", "Overlay API empty — keep/fallback brand logo_overlay")
                } else {
                    // API trống nhưng VideoApp/cache đã có logo góc → KHÔNG xóa
                    if (ScoreboardOverlay.pickbaseLogo == null || ScoreboardOverlay.pickbaseLogo?.isRecycled == true) {
                        reloadBrandLogo()
                    }
                    android.util.Log.w(
                        "PB_VIDEO",
                        "Overlay API returned no logos/marquee — preserving in-memory overlay",
                    )
                }

                ScoreboardOverlay.loadedTournamentIds = tournamentIds.toSet()
                if (gotAnyConfig) {
                    ScoreboardOverlay.introEnabled = introScorebug
                    commentary.density = density
                    commentary.applyEnabled(autoCommentary)
                } else {
                    android.util.Log.w("PB_VIDEO", "Overlay API all failed — keep restored commentary/intro flags")
                }
                overlayDirty = true
                lastContentKey = ""
            }
            // Lưu disk + prefs — thoát/vào lại không mất logo & TTS
            if (gotAnyConfig) {
                OverlayCache.save(
                    context,
                    tournamentIds.toSet(),
                    autoCommentary = autoCommentary,
                    introScorebug = introScorebug,
                    commentaryDensity = when (density) {
                        CommentaryEngine.Density.LOW -> "low"
                        CommentaryEngine.Density.HIGH -> "high"
                        else -> "medium"
                    },
                )
            } else if (ScoreboardOverlay.hasCornerLogos() || ScoreboardOverlay.hasMarquee()) {
                // API lỗi nhưng RAM/cache có logo — vẫn ghi bitmap cache
                OverlayCache.save(context, tournamentIds.toSet())
            }
            android.util.Log.d(
                "PB_VIDEO",
                "Overlay loaded from API tids=$tournamentIds top=${loadedTop.size} bottom=${loadedBottom.size} marquee=${mergedMarquee.size} commentary=$autoCommentary intro=$introScorebug density=$density pickbase=${ScoreboardOverlay.pickbaseLogo != null} gotConfig=$gotAnyConfig",
            )
            refreshOverlay(force = true)
        }
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
            } else if (currentMatch?.scoreLeft != match.scoreLeft || currentMatch?.scoreRight != match.scoreRight) {
                android.util.Log.d("PB_OVERLAY", "Score update: ${currentMatch?.scoreLeft}-${currentMatch?.scoreRight} → ${match.scoreLeft}-${match.scoreRight}")
            }
            lastMatchReceivedAt = System.currentTimeMillis()
            // Intro khi cặp VĐV mới (Davis Cup: teamName = tên VĐV trên sân)
            ScoreboardOverlay.maybeStartIntro(match)
            commentary.onMatchUpdate(match)
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
            commentary.onMatchUpdate(null)
            currentMatch = null
            overlayDirty = true
        }
        // Gộp về main handler — tránh race với overlay loop khi trọng tài ấn điểm liên tục
        handler.removeCallbacks(matchOverlayRefreshRunnable)
        handler.post(matchOverlayRefreshRunnable)
    }

    private val matchOverlayRefreshRunnable = Runnable {
        refreshOverlay(force = true)
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
        if (filterReady && imageFilter != null) return
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
            val reattach = imageFilter != null
            if (imageFilter == null) {
                imageFilter = ImageObjectFilterRender()
            }
            // Luôn setFilter lại khi filterReady=false — startStream/reconnect có thể drop
            // filter khỏi GL pipeline; lúc đó setImage vẫn “chạy” nhưng không hiện trên video.
            gl.setFilter(imageFilter)
            imageFilter?.setScale(100f, 100f) // full screen overlay
            imageFilter?.setPosition(TranslateTo.CENTER)
            filterReady = true
            // Filter mới/reattach trống — vẽ ngay trong cùng lần gọi (tránh 1 frame nháy)
            overlayDirty = true
            lastContentKey = ""
            android.util.Log.i(
                "PB_VIDEO",
                "setupFilter SUCCESS — overlay active (reattach=$reattach, force redraw)",
            )
            refreshOverlay(force = true)
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "setupFilter failed (will retry): ${e.message}")
            // Reset so next attempt creates fresh filter
            imageFilter = null
            filterReady = false
        }
    }

    /** Ép gắn lại filter lên GL (sau startStream / khi nghi mất overlay). */
    private fun forceReattachFilter() {
        filterReady = false
        setupFilter()
    }

    /** Chỉ reattach khi chưa sẵn; nếu đã sẵn thì chỉ vẽ lại (không nháy setFilter). */
    private fun ensureOverlayVisible() {
        if (!filterReady || imageFilter == null) {
            forceReattachFilter()
        } else {
            refreshOverlay(force = true)
        }
    }

    /**
     * Buffer chỉ để Canvas vẽ — KHÔNG đưa thẳng vào setImage.
     * RootEncoder TextureLoader.load() gọi bitmap.recycle() sau texImage2D
     * → nếu setImage(poolBitmap) sẽ recycle buffer đang giữ → FATAL "bitmap is recycled".
     */
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
            append(m.roundName ?: "").append('|')
            append(if (ScoreboardOverlay.isIntroActive()) "intro" else "nointro")
        }
    }

    /**
     * Overlay vẽ tối đa 1080p rồi GL scale full màn — tránh OOM khi stream 2K/4K
     * (2 buffer ARGB 4K ≈ 66MB → dễ crash thoát app).
     */
    private fun overlayDrawSize(q: StreamQuality): Pair<Int, Int> {
        val maxH = 1080
        if (q.height <= maxH) return q.width to q.height
        val scale = maxH.toFloat() / q.height
        return (q.width * scale).toInt().coerceAtLeast(1) to maxH
    }

    private fun obtainOverlayBuffer(w: Int, h: Int): Bitmap? {
        overlayBufferIdx = (overlayBufferIdx + 1) % overlayBuffers.size
        val idx = overlayBufferIdx
        var bmp = overlayBuffers[idx]
        if (bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
            // Không recycle buffer cũ tại đây — phòng trường hợp còn reference lạ
            bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("PB_OVERLAY", "OOM create overlay ${w}x${h}: ${e.message}")
                System.gc()
                try {
                    Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
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
        val needIntro = ScoreboardOverlay.isIntroActive()

        // Không đổi điểm/tên và không có marquee/intro → bỏ frame (giảm nháy + CPU)
        if (!force && !overlayDirty && !contentChanged && !needMarquee && !needIntro) return

        val q = actualQuality ?: selectedQuality
        val (w, h) = overlayDrawSize(q)

        val stateKey = when {
            m != null && m.paused -> "paused"
            m != null -> "playing:${m.scoreLeft}-${m.scoreRight}"
            else -> "logos_only"
        }
        if (stateKey != lastOverlayState) {
            android.util.Log.i("PB_OVERLAY", "State: $lastOverlayState → $stateKey | hasLogos=${ScoreboardOverlay.topRightLogos.isNotEmpty()} pickbase=${ScoreboardOverlay.pickbaseLogo?.takeIf { !it.isRecycled } != null} hasPause=${ScoreboardOverlay.pauseImage != null} hasMarquee=${ScoreboardOverlay.marqueeTexts.isNotEmpty()}")
            lastOverlayState = stateKey
        }

        synchronized(overlayLock) {
            try {
                val bmp = obtainOverlayBuffer(w, h) ?: return
                if (bmp.isRecycled) {
                    android.util.Log.w("PB_OVERLAY", "overlay buffer recycled — skip frame")
                    overlayDirty = true
                    return
                }
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                // Đảm bảo luôn có logo header scoreboard
                if (ScoreboardOverlay.pickbaseLogo == null || ScoreboardOverlay.pickbaseLogo?.isRecycled == true) {
                    loadPickbaseLogo()
                }
                if (m != null) {
                    if (m.paused) ScoreboardOverlay.drawPaused(canvas, bmp.width, bmp.height, m)
                    else ScoreboardOverlay.draw(canvas, bmp.width, bmp.height, m)
                } else {
                    ScoreboardOverlay.drawLogosOnly(canvas, bmp.width, bmp.height)
                }
                // Bắt buộc copy: Pedro TextureLoader recycle bitmap sau khi upload GL
                val forGl = try {
                    bmp.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e("PB_OVERLAY", "OOM copy for GL: ${e.message}")
                    null
                }
                if (forGl == null || forGl.isRecycled) {
                    overlayDirty = true
                    return
                }
                imageFilter?.setImage(forGl)
                lastContentKey = contentKey
                overlayDirty = false
            } catch (e: Exception) {
                android.util.Log.e("PB_OVERLAY", "refreshOverlay CRASH: ${e.message}", e)
                overlayDirty = true
            } catch (e: OutOfMemoryError) {
                android.util.Log.e("PB_OVERLAY", "refreshOverlay OOM: ${e.message}")
                overlayDirty = true
            }
        }
    }

    /** @return true nếu đã gọi startStream (có thể đã fallback xuống chất lượng thấp hơn). */
    fun startStream(rtmpUrl: String, streamKey: String): Boolean {
        val camera = rtmpCamera ?: return false
        val fullUrl = "$rtmpUrl/$streamKey"
        lastRtmpUrl = fullUrl
        if (camera.isStreaming) return true
        return try {
            val quality = resolveQuality(camera)
            if (quality == null) {
                onStatusChange("❌ Không thể khởi tạo camera/audio ở bất kỳ chất lượng nào")
                return false
            }
            actualQuality = quality
            camera.startStream(fullUrl)
            onStatusChange("🔴 Đang stream ${quality.label}...")
            // startStream có thể drop filter — gắn lại 1 lần, rồi chỉ refresh (tránh nháy nhiều lần)
            forceReattachFilter()
            handler.postDelayed({ ensureOverlayVisible() }, 250)
            handler.postDelayed({ ensureOverlayVisible() }, 800)
            handler.postDelayed({
                if (!filterReady) forceReattachFilter()
                else refreshOverlay(force = true)
            }, 1600)
            startOverlayLoop()
            true
        } catch (e: Exception) {
            android.util.Log.e("PB_STREAM", "startStream failed: ${e.message}", e)
            onStatusChange("❌ Lỗi start stream: ${e.message ?: "unknown"}")
            try { if (camera.isStreaming) camera.stopStream() } catch (_: Exception) {}
            false
        } catch (e: OutOfMemoryError) {
            android.util.Log.e("PB_STREAM", "startStream OOM — thử lại với 720p")
            onStatusChange("❌ Máy thiếu RAM cho ${selectedQuality.label} — chọn 720p/1080p")
            try { if (camera.isStreaming) camera.stopStream() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Thử từ chất lượng đã chọn, nếu phần cứng không hỗ trợ thì fallback xuống mức thấp hơn.
     * Trả về quality thực tế được sử dụng, hoặc null nếu tất cả đều thất bại.
     */
    private fun resolveQuality(camera: RtmpCamera2): StreamQuality? {
        val qualities = StreamQuality.all()
        val startIndex = qualities.indexOf(selectedQuality).coerceAtLeast(0)
        for (i in startIndex until qualities.size) {
            val q = qualities[i]
            try {
                val okVideo = camera.prepareVideo(q.width, q.height, q.fps, q.bitrate, 0)
                val okAudio = okVideo && camera.prepareAudio(192 * 1024, 44100, false)
                if (okVideo && okAudio) {
                    if (q != selectedQuality) {
                        onStatusChange("⚠️ ${selectedQuality.label} không hỗ trợ, dùng ${q.label}")
                    }
                    android.util.Log.i("PB_STREAM", "resolveQuality: selected=${selectedQuality.label} → actual=${q.label}")
                    return q
                }
                android.util.Log.w("PB_STREAM", "prepare failed for ${q.label} (video=$okVideo)")
            } catch (e: Exception) {
                android.util.Log.w("PB_STREAM", "prepare exception ${q.label}: ${e.message}")
            }
        }
        return null
    }

    private fun scaledBitrate(base: Int): Int {
        // 4K/2K: không hạ bitrate theo nhiệt lúc prepare/cap nhẹ — tránh live bị "không ổn"
        val q = actualQuality ?: selectedQuality
        if (q == StreamQuality.Q_4K || q == StreamQuality.Q_2K) {
            return base
        }
        return (base * ThermalHelper.bitrateScale()).toInt().coerceAtLeast(1500 * 1024)
    }

    private fun applyThermalBitrateCap() {
        val q = actualQuality ?: return
        // Giữ nguyên bitrate cao khi user chọn 4K/2K
        if (q == StreamQuality.Q_4K || q == StreamQuality.Q_2K) return
        val camera = rtmpCamera ?: return
        if (!camera.isStreaming) return
        val target = scaledBitrate(q.bitrate)
        val current = camera.getBitrate()
        if (current > target * 1.1) {
            camera.setVideoBitrateOnFly(target)
            android.util.Log.i("PB_THERMAL", "Cap bitrate ${current / 1024}→${target / 1024} kbps (thermal=${ThermalHelper.level})")
        }
    }

    fun startPreview(previewOnly: Boolean = false) {
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

        // Màn test camera: chỉ preview, không gắn overlay/TTS loop
        if (previewOnly) {
            android.util.Log.i("PB_VIDEO", "startPreview previewOnly — skip overlay filter")
            return
        }

        // Setup filter after preview starts (GL context ready)
        handler.postDelayed({
            setupFilter() // đã vẽ overlay ngay trong setupFilter
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
                if (ThermalHelper.shouldThrottle()) {
                    applyThermalBitrateCap()
                }
                if (overlayFrameCount % 1500 == 0L) {
                    val runtime = Runtime.getRuntime()
                    val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
                    val maxMB = runtime.maxMemory() / 1048576
                    android.util.Log.i("PB_STREAM", "Health check: frames=$overlayFrameCount, mem=${usedMB}/${maxMB}MB, streaming=${rtmpCamera?.isStreaming}, bitrate=${rtmpCamera?.getBitrate()}, filterReady=$filterReady, hasMatch=${currentMatch != null}, hasLogos=${ScoreboardOverlay.topRightLogos.isNotEmpty()}, pickbase=${ScoreboardOverlay.pickbaseLogo != null}")
                    // Chỉ refresh — không setFilter lại (tránh nháy overlay)
                    if (rtmpCamera?.isStreaming == true && filterReady) {
                        refreshOverlay(force = true)
                    } else if (rtmpCamera?.isStreaming == true) {
                        forceReattachFilter()
                    }
                }
                // Intro/marquee ~10–15fps đủ mượt; tránh setImage nhanh hơn GL (leak copy trước khi Pedro recycle)
                val baseDelay = when {
                    ScoreboardOverlay.isIntroActive() -> 80L
                    ScoreboardOverlay.hasMarquee() -> 100L
                    else -> 400L
                }
                val delayMs = baseDelay * ThermalHelper.overlayDelayMultiplier()
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
    private var released = false

    fun release() {
        if (released) {
            android.util.Log.w("PB_STREAM", "🗑 release skipped (already released)")
            return
        }
        released = true
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
        ScoreboardOverlay.stopIntroVisual()
        commentary.release()
        ThermalHelper.stop(context)
        filterReady = false
        imageFilter = null
        lastContentKey = ""
        overlayDirty = true
        handler.removeCallbacks(matchOverlayRefreshRunnable)
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
        handler.post { forceReattachFilter() }
        handler.postDelayed({ ensureOverlayVisible() }, 400)
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
        // 4K/2K: chỉ hạ khi thật sự yếu (min 50% target), tránh nhấp nháy bitrate
        val isHighRes = q == StreamQuality.Q_4K || q == StreamQuality.Q_2K
        val minBitrate = if (isHighRes) {
            (targetBitrate * 0.5).toLong()
        } else {
            (targetBitrate * 0.3).toLong().coerceAtLeast(1000L * 1024)
        }
        val weakThreshold = if (isHighRes) 0.45 else 0.6
        val weakNeedCount = if (isHighRes) LOW_BITRATE_THRESHOLD + 2 else LOW_BITRATE_THRESHOLD

        if (bitrate < currentBitrate * weakThreshold) {
            lowBitrateCount++
            highBitrateCount = 0
            if (lowBitrateCount >= weakNeedCount) {
                lowBitrateCount = 0
                val newBitrate = (currentBitrate * 0.7).toLong().coerceAtLeast(minBitrate)
                if (newBitrate < currentBitrate) {
                    rtmpCamera?.setVideoBitrateOnFly(newBitrate.toInt())
                    val mbps = String.format("%.1f", newBitrate.toFloat() / 1024 / 1024)
                    android.util.Log.w("PB_STREAM", "⚠️ WEAK NETWORK: bitrate ${bitrate/1024}kbps < threshold, reducing to ${newBitrate/1024}kbps (${mbps}Mbps)")
                    onStatusChange("⚠️ Mạng yếu → ${mbps}Mbps")
                }
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
