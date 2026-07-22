package vn.vdpr.video.stream

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Theo dõi nhiệt máy — giảm tải encode/overlay khi nóng.
 */
object ThermalHelper {

    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4
    const val STATUS_EMERGENCY = 5
    const val STATUS_SHUTDOWN = 6

    @Volatile
    var level: Int = STATUS_NONE
        private set

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        level = pm.currentThermalStatus
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            level = status
            android.util.Log.i("PB_THERMAL", "Thermal status → $status")
        }
        listener = l
        pm.addThermalStatusListener(l)
    }

    fun stop(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        listener?.let { pm.removeThermalStatusListener(it) }
        listener = null
        level = STATUS_NONE
    }

    /** true khi nên giảm bitrate / overlay */
    fun shouldThrottle(): Boolean = level >= STATUS_MODERATE

    /** Hệ số nhân delay overlay (1 = bình thường) */
    fun overlayDelayMultiplier(): Int = when {
        level >= STATUS_SEVERE -> 4
        level >= STATUS_MODERATE -> 2
        level >= STATUS_LIGHT -> 1
        else -> 1
    }

    /** Giảm bitrate target khi nóng (0.0–1.0) */
    fun bitrateScale(): Float = when {
        level >= STATUS_CRITICAL -> 0.45f
        level >= STATUS_SEVERE -> 0.60f
        level >= STATUS_MODERATE -> 0.75f
        level >= STATUS_LIGHT -> 0.90f
        else -> 1.0f
    }
}
