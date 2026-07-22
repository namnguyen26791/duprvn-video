package vn.vdpr.video.overlay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object BitmapUtils {

    /** Logo góc màn hình ~80–250px; pause giữa màn lớn hơn. */
    const val MAX_LOGO_EDGE = 512
    const val MAX_PAUSE_EDGE = 1280

    fun decodeSampled(input: InputStream, maxEdge: Int): Bitmap? {
        val bytes = input.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (w / sample > maxEdge || h / sample > maxEdge) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return clampToMaxEdge(decoded, maxEdge)
    }

    fun clampToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= maxEdge || src.width <= 0 || src.height <= 0) return src
        val scale = maxEdge.toFloat() / maxDim
        val nw = (src.width * scale).toInt().coerceAtLeast(1)
        val nh = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
        if (scaled !== src && !src.isRecycled) src.recycle()
        return scaled
    }

    fun loadUrl(imageUrl: String, maxEdge: Int = MAX_LOGO_EDGE): Bitmap? {
        return try {
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            if (connection is HttpsURLConnection) {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslCtx = SSLContext.getInstance("TLS")
                sslCtx.init(null, trustAll, java.security.SecureRandom())
                connection.sslSocketFactory = sslCtx.socketFactory
                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            }
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.inputStream.use { decodeSampled(it, maxEdge) }
        } catch (e: Exception) {
            android.util.Log.w("PB_VIDEO", "BitmapUtils.loadUrl failed: $imageUrl — ${e.message}")
            null
        }
    }
}
