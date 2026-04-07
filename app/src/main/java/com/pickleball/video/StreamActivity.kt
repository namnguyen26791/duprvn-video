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
    private var matchId = 0
    private var matchType = "bracket"
    private var apiBase = ""
    private var tournamentId = 0
    private val handler = Handler(Looper.getMainLooper())
    private var configCheckRunnable: Runnable? = null
    private var isStreaming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                // Don't start stream immediately — wait for polling to get config
                statusText.text = "Đang chờ cấu hình..."
                startConfigCheck()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { streamManager?.release() }
        })

        // Listen Firebase for match on this court (scoreboard overlay)
        lifecycleScope.launch {
            FirebaseMatchListener.observeCourtMatch(courtName, tournamentId).collectLatest { match ->
                streamManager?.updateMatch(match)
            }
        }
    }

    private fun startConfigCheck() {
        configCheckRunnable = object : Runnable {
            override fun run() {
                lifecycleScope.launch {
                    try {
                        val api = ApiService.create(apiBase)
                        val config = api.getMatchStreamConfig(matchId, matchType)

                        // If stream_ended_at is set → stop stream and finish
                        if (!config.stream_ended_at.isNullOrBlank()) {
                            runOnUiThread {
                                streamManager?.release()
                                finish()
                            }
                            return@launch
                        }

                        // If rtmp_url + stream_key available and not currently streaming → start
                        if (!config.rtmp_url.isNullOrBlank() && !config.stream_key.isNullOrBlank() && !isStreaming) {
                            isStreaming = true
                            runOnUiThread {
                                streamManager?.startStream(config.rtmp_url, config.stream_key)
                            }
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

    override fun onDestroy() {
        super.onDestroy()
        configCheckRunnable?.let { handler.removeCallbacks(it) }
        streamManager?.release()
    }
}
