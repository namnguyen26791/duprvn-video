package vn.vdpr.video

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.pedro.rtplibrary.view.OpenGlView
import vn.vdpr.video.stream.StreamManager
import vn.vdpr.video.stream.StreamQuality

/**
 * Test camera căn chỉnh lắp đặt.
 * Cam full màn; 2 dải trái/phải nằm TRÊN OpenGlView (SurfaceView dễ đè view bên cạnh).
 */
class CameraTestActivity : AppCompatActivity() {

    private var streamManager: StreamManager? = null
    private var previewStarted = false
    private var closing = false
    private lateinit var statusHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val cameraId = intent.getStringExtra("camera_id") ?: "0"
        // Dải chạm mỏng + trong suốt — không che khung hình như live
        val sidePx = (resources.displayMetrics.widthPixels * 0.12f).toInt().coerceAtLeast(dp(48))

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            keepScreenOn = true
        }

        val openGlView = OpenGlView(this).apply { keepScreenOn = true }
        root.addView(
            openGlView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Overlay trong suốt trên cam full — cùng khung hình với live
        val leftBack = makeBackStrip("←")
        val rightBack = makeBackStrip("←")
        root.addView(leftBack, FrameLayout.LayoutParams(sidePx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START))
        root.addView(rightBack, FrameLayout.LayoutParams(sidePx, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))

        statusHint = TextView(this).apply {
            text = "Đang bật camera…"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0x66000000.toInt())
            elevation = 24f
        }
        root.addView(
            statusHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(16) },
        )

        setContentView(root)
        leftBack.bringToFront()
        rightBack.bringToFront()
        statusHint.bringToFront()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeTest()
                }
            },
        )

        streamManager = StreamManager(this, openGlView) { msg ->
            runOnUiThread {
                if (!previewStarted || msg.contains("❌") || msg.contains("⚠️")) {
                    statusHint.text = msg
                }
            }
        }.also {
            it.selectedQuality = StreamQuality.Q_720P
            it.init("test", cameraId)
        }

        openGlView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (previewStarted || closing) return
                previewStarted = true
                try {
                    streamManager?.startPreview(previewOnly = true)
                    statusHint.text = "Cam full khung · chạm mép ← hoặc nút Back để thoát"
                    statusHint.postDelayed({ statusHint.alpha = 0.5f }, 2500)
                } catch (e: Exception) {
                    statusHint.text = "Không bật được camera: ${e.message}"
                    android.util.Log.e("PB_CAM_TEST", "startPreview failed", e)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
    }

    private fun makeBackStrip(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(0xEEFFFFFF.toInt())
            textSize = 28f
            gravity = Gravity.CENTER
            // Trong suốt gần như invisible — vẫn bắt touch, không che khung cam
            setBackgroundColor(0x33000000)
            elevation = 32f
            translationZ = 32f
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnTouchListener { v, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    v.performClick()
                    closeTest()
                    true
                } else {
                    true
                }
            }
            setOnClickListener { closeTest() }
        }
    }

    private fun closeTest() {
        if (closing) return
        closing = true
        android.util.Log.i("PB_CAM_TEST", "closeTest()")
        // finish trước — tránh release() block UI khiến nút “không ăn”
        finish()
        window.decorView.post {
            try {
                streamManager?.release()
            } catch (e: Exception) {
                android.util.Log.w("PB_CAM_TEST", "release: ${e.message}")
            }
            streamManager = null
        }
    }

    override fun onDestroy() {
        closing = true
        try {
            streamManager?.release()
        } catch (_: Exception) {}
        streamManager = null
        super.onDestroy()
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
