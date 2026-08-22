package vn.vdpr.video

import android.content.Intent
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pedro.rtplibrary.view.OpenGlView
import vn.vdpr.video.data.ApiService
import vn.vdpr.video.data.DeviceStatusRequest
import vn.vdpr.video.data.StreamConfirmedRequest
import vn.vdpr.video.data.FirebaseMatchListener
import vn.vdpr.video.stream.StreamManager
import vn.vdpr.video.stream.StreamKeepAliveService
import vn.vdpr.video.stream.StreamQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts

class StreamActivity : AppCompatActivity() {

    private lateinit var openGlView: OpenGlView
    private lateinit var statusText: TextView
    private var streamManager: StreamManager? = null
    private var courtName = ""
    private var matchId = 0
    private var matchType = "bracket"
    private var apiBase = ""
    private var tournamentId = 0
    private val handler = Handler(Looper.getMainLooper())
    private var configCheckRunnable: Runnable? = null
    private var isStreaming = false

    companion object {
        private const val BATTERY_REPORT_MS = 60_000L
    }

    private fun applyStreamMeta(tournamentIdFromApi: Int?, courtFromApi: String?) {
        if (tournamentIdFromApi != null && tournamentIdFromApi > 0) {
            tournamentId = tournamentIdFromApi
        }
        if (!courtFromApi.isNullOrBlank()) {
            courtName = courtFromApi.trim()
        }
    }

    private fun reportBatteryNow() {
        lifecycleScope.launch {
            try {
                val api = ApiService.create(apiBase)
                reportBattery(api)
            } catch (e: Exception) {
                android.util.Log.w("PB_VIDEO", "Battery report failed: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force IPv4 to avoid RTMP connection failures on IPv6-only networks
        System.setProperty("java.net.preferIPv4Stack", "true")

        // Load overlay from disk cache if static fields are empty (process was killed)
        // Chỉ load khi cache khớp đúng set giải đang chọn — tránh logo giải khác
        if (vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos.isEmpty() &&
            vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos.isEmpty()) {
            val prefs = getSharedPreferences("video_app", MODE_PRIVATE)
            val expectedTids = prefs.getStringSet("tids", emptySet())
                ?.mapNotNull { it.toIntOrNull() }
                ?.toSet()
                ?: emptySet()
            val loaded = vn.vdpr.video.overlay.OverlayCache.load(this, expectedTids)
            android.util.Log.i("PB_OVERLAY", "Cache load: $loaded tids=$expectedTids | topLogos=${vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos.size} bottomLogos=${vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos.size}")
        } else {
            android.util.Log.i("PB_OVERLAY", "Overlay already in memory: topLogos=${vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos.size} bottomLogos=${vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos.size}")
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        matchId = intent.getIntExtra("match_id", 0)
        matchType = intent.getStringExtra("match_type") ?: "bracket"
        apiBase = intent.getStringExtra("api_base") ?: ""
        tournamentId = intent.getIntExtra("tournament_id", 0)
        courtName = intent.getStringExtra("court_name") ?: "Sân"
        val cameraId = intent.getStringExtra("camera_id") ?: "0"
        val qualityName = intent.getStringExtra("stream_quality") ?: "Q_720P"
        val quality = try { StreamQuality.valueOf(qualityName) } catch (_: Exception) { StreamQuality.Q_720P }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        openGlView = OpenGlView(this)
        root.addView(openGlView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#80000000"))
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ))

        setContentView(root)

        streamManager = StreamManager(this, openGlView) { status ->
            runOnUiThread { statusText.text = status }
        }
        streamManager?.selectedQuality = quality
        streamManager?.init(courtName, cameraId)
        streamManager?.loadOverlayConfig(apiBase, tournamentId)

        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                streamManager?.startPreview()
                statusText.text = "Đang chờ cấu hình..."
                // Tránh double-poll khi surface recreate
                if (configCheckRunnable == null) startConfigCheck()
                if (batteryRunnable == null) startBatteryReport()
                reportBatteryNow()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface có thể recreate tạm thời — chỉ full release khi activity đang đóng
                if (isFinishing) {
                    stopKeepAlive()
                    streamManager?.release()
                }
            }
        })

        // Listen Firebase for match by ID (scoreboard overlay)
        lifecycleScope.launch {
            android.util.Log.i("PB_OVERLAY", "Firebase listener started: matchType=$matchType matchId=$matchId")
            FirebaseMatchListener.observeMatchById(matchType, matchId).collectLatest { match ->
                android.util.Log.d("PB_OVERLAY", "Firebase emit: ${if (match != null) "${match.left.teamName} ${match.scoreLeft}-${match.scoreRight} ${match.right.teamName} paused=${match.paused}" else "NULL"}")
                streamManager?.updateMatch(match)
            }
        }
    }

    private var streamEndedConfirmCount = 0
    private var keepAliveStarted = false
    private var lastRtmpUrl: String? = null
    private var lastStreamKey: String? = null

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* foreground service vẫn chạy; notification có thể bị ẩn nếu từ chối */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startKeepAlive() {
        if (keepAliveStarted) return
        try {
            keepAliveStarted = true
            ensureNotificationPermission()
            StreamKeepAliveService.start(this, courtName, matchId)
        } catch (e: Exception) {
            keepAliveStarted = false
            android.util.Log.e("PB_VIDEO", "startKeepAlive failed: ${e.message}", e)
        }
    }

    private fun stopKeepAlive() {
        if (!keepAliveStarted) return
        keepAliveStarted = false
        try {
            StreamKeepAliveService.stop(this)
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "stopKeepAlive: ${e.message}")
        }
    }

    private fun startConfigCheck() {
        configCheckRunnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    try {
                        val api = ApiService.create(apiBase)
                        val config = api.getMatchStreamConfig(matchId, matchType)
                        applyStreamMeta(config.tournament_id, config.court)

                        // Tạo lại broadcast / stop-live chỉ set stream_ended_at (status vẫn playing).
                        // Trước Jul 4 chỉ cần stream_ended_at → đóng activity → Live mở lại bình thường.
                        // Sau khi thêm && status=="done", recreate không đóng được → không auto live.
                        if (!config.stream_ended_at.isNullOrBlank()) {
                            streamEndedConfirmCount++
                            if (streamEndedConfirmCount >= 2) {
                                android.util.Log.w("PB_VIDEO", "configCheck: stream_ended_at='${config.stream_ended_at}' → finishing")
                                runOnUiThread {
                                    stopKeepAlive()
                                    streamManager?.release()
                                    setResult(RESULT_OK)
                                    finish()
                                }
                                return@launch
                            }
                        } else {
                            streamEndedConfirmCount = 0
                        }

                        val rtmp = config.rtmp_url
                        val key = config.stream_key
                        if (!rtmp.isNullOrBlank() && !key.isNullOrBlank()) {
                            val urlChanged = isStreaming && (rtmp != lastRtmpUrl || key != lastStreamKey)
                            if (urlChanged) {
                                android.util.Log.w("PB_VIDEO", "configCheck: RTMP/key changed → restart stream")
                                isStreaming = false
                                withContext(Dispatchers.Main) {
                                    stopKeepAlive()
                                    streamManager?.stopStream()
                                }
                            }
                            if (!isStreaming) {
                                isStreaming = true
                                lastRtmpUrl = rtmp
                                lastStreamKey = key
                                val started = withContext(Dispatchers.Main) {
                                    startKeepAlive()
                                    streamManager?.startStream(rtmp, key) == true
                                }
                                if (!started) {
                                    isStreaming = false
                                    withContext(Dispatchers.Main) { stopKeepAlive() }
                                } else {
                                    reportBatteryNow()
                                    try {
                                        api.streamConfirmed(StreamConfirmedRequest(
                                            match_id = matchId,
                                            match_type = matchType,
                                            tournament_id = tournamentId,
                                            court_name = courtName,
                                        ))
                                    } catch (_: Exception) {}
                                }
                            }
                        } else {
                            // Reset broadcast: key bị xóa → cho phép start lại khi có key mới
                            if (isStreaming) {
                                isStreaming = false
                                lastRtmpUrl = null
                                lastStreamKey = null
                                runOnUiThread {
                                    stopKeepAlive()
                                    streamManager?.stopStream()
                                }
                            }
                            runOnUiThread {
                                statusText.text = "Đang chờ cấu hình..."
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PB_VIDEO", "configCheck failed: ${e.message}")
                        runOnUiThread {
                            statusText.text = "⚠️ Mất kết nối..."
                        }
                    }
                }
                handler.postDelayed(this, 5000) // poll every 5s
            }
        }
        handler.post(configCheckRunnable!!)
    }

    private var batteryRunnable: Runnable? = null

    private fun startBatteryReport() {
        batteryRunnable = object : Runnable {
            override fun run() {
                reportBatteryNow()
                handler.postDelayed(this, BATTERY_REPORT_MS)
            }
        }
        handler.postDelayed(batteryRunnable!!, BATTERY_REPORT_MS)
    }

    private suspend fun reportBattery(api: ApiService) {
        if (tournamentId <= 0) return
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0 || level > 100) return
        api.reportDeviceStatus(DeviceStatusRequest(
            tournament_id = tournamentId,
            court_name = courtName.trim(),
            battery_level = level,
            is_streaming = isStreaming && (streamManager?.isStreaming() == true),
        ))
    }

    override fun onDestroy() {
        super.onDestroy()
        configCheckRunnable?.let { handler.removeCallbacks(it) }
        batteryRunnable?.let { handler.removeCallbacks(it) }
        stopKeepAlive()
        streamManager?.release()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Single-instance: khi VideoApp launch lại cho trận mới, dừng stream cũ và restart
        val newMatchId = intent.getIntExtra("match_id", 0)
        if (newMatchId > 0 && newMatchId != matchId) {
            android.util.Log.w("PB_VIDEO", "onNewIntent: switching from match $matchId → $newMatchId")
            // Stop old stream + config polling
            configCheckRunnable?.let { handler.removeCallbacks(it) }
            configCheckRunnable = null
            batteryRunnable?.let { handler.removeCallbacks(it) }
            batteryRunnable = null
            stopKeepAlive()
            streamManager?.release()
            isStreaming = false
            streamEndedConfirmCount = 0
            lastRtmpUrl = null
            lastStreamKey = null
            // Restart with new intent
            setIntent(intent)
            recreate()
        }
    }
}
