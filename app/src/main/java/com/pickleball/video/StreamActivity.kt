package com.pickleball.video

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pedro.rtplibrary.view.OpenGlView
import okhttp3.MediaType.Companion.toMediaType
import com.pickleball.video.data.ApiService
import com.pickleball.video.data.FirebaseMatchListener
import com.pickleball.video.stream.StreamManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StreamActivity : AppCompatActivity() {

    private lateinit var openGlView: OpenGlView
    private lateinit var statusText: TextView
    private var streamManager: StreamManager? = null
    private var courtName = ""
    private var rtmpUrl = ""
    private var streamKey = ""
    private var apiBase = ""
    private var tournamentId = 0
    private val handler = Handler(Looper.getMainLooper())
    private var configCheckRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        courtName = intent.getStringExtra("court_name") ?: "Sân"
        rtmpUrl = intent.getStringExtra("rtmp_url") ?: ""
        streamKey = intent.getStringExtra("stream_key") ?: ""
        apiBase = intent.getStringExtra("api_base") ?: ""
        tournamentId = intent.getIntExtra("tournament_id", 0)

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
        streamManager?.init(courtName)

        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                streamManager?.startPreview()
                if (rtmpUrl.isNotBlank() && streamKey.isNotBlank()) {
                    streamManager?.startStream(rtmpUrl, streamKey)
                    reportStreamStarted()
                } else {
                    statusText.text = "⚠️ Chưa có RTMP — chỉ preview"
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { streamManager?.release() }
        })

        // Listen Firebase for match on this court
        lifecycleScope.launch {
            FirebaseMatchListener.observeCourtMatch(courtName, tournamentId).collectLatest { match ->
                streamManager?.updateMatch(match)
            }
        }

        // Poll stream config every 10s — if court removed, stop and go back
        startConfigCheck()
    }

    private fun reportStreamStarted() {
        if (apiBase.isBlank() || tournamentId <= 0) return
        lifecycleScope.launch {
            try {
                val client = okhttp3.OkHttpClient()
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    """{"court_name":"$courtName"}"""
                )
                val req = okhttp3.Request.Builder()
                    .url("$apiBase/public/tournaments/$tournamentId/stream-started")
                    .post(body).build()
                client.newCall(req).execute()
            } catch (_: Exception) {}
        }
    }

    private fun startConfigCheck() {
        configCheckRunnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    try {
                        val api = ApiService.create(apiBase)
                        val config = api.getStreamConfig(tournamentId)
                        val courtCfg = config.stream_config[courtName]
                        if (courtCfg == null || courtCfg.rtmp_url.isNullOrBlank() || courtCfg.stopped == true) {
                            // Court config removed — stop stream and go back
                            runOnUiThread {
                                streamManager?.release()
                                finish()
                            }
                            return@launch
                        }
                    } catch (_: Exception) {}
                }
                handler.postDelayed(this, 10000) // check every 10s
            }
        }
        handler.postDelayed(configCheckRunnable!!, 10000)
    }

    override fun onDestroy() {
        super.onDestroy()
        configCheckRunnable?.let { handler.removeCallbacks(it) }
        streamManager?.release()
    }
}
