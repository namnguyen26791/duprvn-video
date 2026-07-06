package vn.vdpr.video

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
import vn.vdpr.video.stream.StreamQuality
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force IPv4 to avoid RTMP connection failures on IPv6-only networks
        System.setProperty("java.net.preferIPv4Stack", "true")

        // Load overlay from disk cache if static fields are empty (process was killed)
        if (vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos.isEmpty() &&
            vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos.isEmpty()) {
            val loaded = vn.vdpr.video.overlay.OverlayCache.load(this)
            android.util.Log.i("PB_OVERLAY", "Cache load: $loaded | topLogos=${vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos.size} bottomLogos=${vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos.size}")
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
        val qualityName = intent.getStringExtra("stream_quality") ?: "Q_1080P"
        val quality = try { StreamQuality.valueOf(qualityName) } catch (_: Exception) { StreamQuality.Q_1080P }

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
                // Don't start stream immediately — wait for polling to get config
                statusText.text = "Đang chờ cấu hình..."
                startConfigCheck()
                startBatteryReport()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { streamManager?.release() }
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

    private fun startConfigCheck() {
        configCheckRunnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    try {
                        val api = ApiService.create(apiBase)
                        val config = api.getMatchStreamConfig(matchId, matchType)

                        // If stream_ended_at is set AND status is done → confirm twice then finish
                        if (!config.stream_ended_at.isNullOrBlank() && config.status == "done") {
                            streamEndedConfirmCount++
                            if (streamEndedConfirmCount >= 2) {
                                android.util.Log.w("PB_VIDEO", "configCheck: stream_ended_at='${config.stream_ended_at}', status=done (confirmed) → finishing")
                                runOnUiThread {
                                    streamManager?.release()
                                    finish()
                                }
                                return@launch
                            }
                        } else {
                            streamEndedConfirmCount = 0
                        }

                        // If rtmp_url + stream_key available and not currently streaming → start
                        if (!config.rtmp_url.isNullOrBlank() && !config.stream_key.isNullOrBlank() && !isStreaming) {
                            isStreaming = true
                            runOnUiThread {
                                streamManager?.startStream(config.rtmp_url, config.stream_key)
                            }
                            // Report stream confirmed to backend
                            try {
                                api.streamConfirmed(StreamConfirmedRequest(
                                    match_id = matchId,
                                    match_type = matchType,
                                    tournament_id = tournamentId,
                                    court_name = courtName,
                                ))
                            } catch (_: Exception) {}
                        } else if (config.rtmp_url.isNullOrBlank() || config.stream_key.isNullOrBlank()) {
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
                lifecycleScope.launch {
                    val api = ApiService.create(apiBase)
                    reportBattery(api)
                }
                handler.postDelayed(this, 180_000) // every 3 minutes
            }
        }
        handler.post(batteryRunnable!!)
    }

    private suspend fun reportBattery(api: ApiService) {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            api.reportDeviceStatus(DeviceStatusRequest(
                tournament_id = tournamentId,
                court_name = courtName,
                battery_level = level,
                is_streaming = isStreaming,
            ))
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        configCheckRunnable?.let { handler.removeCallbacks(it) }
        batteryRunnable?.let { handler.removeCallbacks(it) }
        streamManager?.release()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Single-instance: khi VideoApp launch lại cho trận mới, dừng stream cũ và restart
        val newMatchId = intent?.getIntExtra("match_id", 0) ?: 0
        if (newMatchId > 0 && newMatchId != matchId) {
            android.util.Log.w("PB_VIDEO", "onNewIntent: switching from match $matchId → $newMatchId")
            // Stop old stream + config polling
            configCheckRunnable?.let { handler.removeCallbacks(it) }
            streamManager?.release()
            isStreaming = false
            streamEndedConfirmCount = 0
            // Restart with new intent
            setIntent(intent)
            recreate()
        }
    }
}

