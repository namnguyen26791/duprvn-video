package vn.vdpr.video.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Persist overlay bitmaps to disk cache.
 * Survives process kill — reload on next StreamActivity start.
 */
object OverlayCache {

    private const val TAG = "PB_OVERLAY_CACHE"
    private const val DIR = "overlay_cache"

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Save all current overlay bitmaps to disk */
    fun save(context: Context) {
        val dir = cacheDir(context)
        try {
            // Top-right logos
            ScoreboardOverlay.topRightLogos.forEachIndexed { i, bmp ->
                saveBitmap(bmp, File(dir, "top_right_$i.png"))
            }
            // Bottom-right logos
            ScoreboardOverlay.bottomRightLogos.forEachIndexed { i, bmp ->
                saveBitmap(bmp, File(dir, "bottom_right_$i.png"))
            }
            // Pause image
            ScoreboardOverlay.pauseImage?.let { saveBitmap(it, File(dir, "pause.png")) }
            // Marquee texts
            val marqueeFile = File(dir, "marquee.txt")
            marqueeFile.writeText(ScoreboardOverlay.marqueeTexts.joinToString("\n"))
            // Metadata: counts
            val meta = File(dir, "meta.txt")
            meta.writeText("${ScoreboardOverlay.topRightLogos.size}\n${ScoreboardOverlay.bottomRightLogos.size}")

            Log.d(TAG, "Saved overlay cache: top=${ScoreboardOverlay.topRightLogos.size}, bottom=${ScoreboardOverlay.bottomRightLogos.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save overlay cache: ${e.message}")
        }
    }

    /** Load overlay bitmaps from disk into ScoreboardOverlay static fields */
    fun load(context: Context): Boolean {
        val dir = cacheDir(context)
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return false

        try {
            val lines = meta.readText().split("\n")
            val topCount = lines.getOrNull(0)?.toIntOrNull() ?: 0
            val bottomCount = lines.getOrNull(1)?.toIntOrNull() ?: 0

            if (topCount == 0 && bottomCount == 0) return false

            // Load top-right logos
            val topList = mutableListOf<Bitmap>()
            for (i in 0 until topCount) {
                val f = File(dir, "top_right_$i.png")
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)?.let { topList.add(it) }
                }
            }
            if (topList.isNotEmpty()) {
                ScoreboardOverlay.topRightLogos = topList
                ScoreboardOverlay.pickbaseLogo = topList.first()
            }

            // Load bottom-right logos
            val bottomList = mutableListOf<Bitmap>()
            for (i in 0 until bottomCount) {
                val f = File(dir, "bottom_right_$i.png")
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)?.let { bottomList.add(it) }
                }
            }
            if (bottomList.isNotEmpty()) {
                ScoreboardOverlay.bottomRightLogos = bottomList
                ScoreboardOverlay.tournamentLogo = bottomList.first()
            }

            // Load pause image
            val pauseFile = File(dir, "pause.png")
            if (pauseFile.exists()) {
                ScoreboardOverlay.pauseImage = BitmapFactory.decodeFile(pauseFile.absolutePath)
            }

            // Load marquee texts
            val marqueeFile = File(dir, "marquee.txt")
            if (marqueeFile.exists()) {
                val texts = marqueeFile.readText().split("\n").filter { it.isNotBlank() }
                if (texts.isNotEmpty()) ScoreboardOverlay.marqueeTexts = texts
            }

            Log.d(TAG, "Loaded overlay cache: top=${topList.size}, bottom=${bottomList.size}")
            return topList.isNotEmpty() || bottomList.isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load overlay cache: ${e.message}")
            return false
        }
    }

    /** Clear disk cache */
    fun clear(context: Context) {
        val dir = cacheDir(context)
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun saveBitmap(bmp: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        }
    }
}
