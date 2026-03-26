package com.pickleball.video

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pedro.rtplibrary.view.OpenGlView
import com.pickleball.video.data.FirebaseMatchListener
import com.pickleball.video.stream.StreamManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen streaming activity with OpenGL overlay.
 * Scoreboard is rendered into the video stream via OpenGL filter.
 */
class StreamActivity : AppCompatActivity() {

    private lateinit var openGlView: OpenGlView
    private lateinit var statusText: TextView
    private var streamManager: StreamManager? = null
    private var courtName = ""
    private var rtmpUrl = ""
    private var streamKey = ""

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

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // OpenGlView — camera preview WITH OpenGL filter pipeline
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

        // OpenGlView uses SurfaceHolder callback
        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                streamManager?.startPreview()
                if (rtmpUrl.isNotBlank() && streamKey.isNotBlank()) {
                    streamManager?.startStream(rtmpUrl, streamKey)
                } else {
                    statusText.text = "⚠️ Chưa có RTMP — chỉ preview"
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { streamManager?.release() }
        })

        // Listen Firebase for match on this court
        lifecycleScope.launch {
            FirebaseMatchListener.observeCourtMatch(courtName).collectLatest { match ->
                streamManager?.updateMatch(match)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        streamManager?.release()
    }
}
