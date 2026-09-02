package vn.vdpr.video.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Persist overlay bitmaps + flags TTS/intro to disk.
 * Survives process kill — reload on next StreamActivity start.
 */
object OverlayCache {

    private const val TAG = "PB_OVERLAY_CACHE"
    private const val DIR = "overlay_cache"
    private const val PREFS = "overlay_flags"

    data class Flags(
        val autoCommentary: Boolean = false,
        val introScorebug: Boolean = false,
        val commentaryDensity: String = "medium",
    )

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveFlags(
        context: Context,
        autoCommentary: Boolean,
        introScorebug: Boolean,
        commentaryDensity: String,
        tournamentIds: Set<Int> = emptySet(),
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("auto_commentary", autoCommentary)
            .putBoolean("intro_scorebug", introScorebug)
            .putString("commentary_density", commentaryDensity)
            .putString("tids", tournamentIds.sorted().joinToString(","))
            .apply()
        ScoreboardOverlay.introEnabled = introScorebug
    }

    fun loadFlags(context: Context, expectedTournamentIds: Set<Int> = emptySet()): Flags {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedTids = prefs.getString("tids", "")
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?: emptySet()
        if (expectedTournamentIds.isNotEmpty() && savedTids.isNotEmpty() && savedTids != expectedTournamentIds) {
            Log.w(TAG, "Flags tids mismatch: saved=$savedTids expected=$expectedTournamentIds — ignore")
            return Flags()
        }
        return Flags(
            autoCommentary = prefs.getBoolean("auto_commentary", false),
            introScorebug = prefs.getBoolean("intro_scorebug", false),
            commentaryDensity = prefs.getString("commentary_density", "medium") ?: "medium",
        )
    }

    /** Save all current overlay bitmaps to disk (xóa cache cũ trước). */
    fun save(
        context: Context,
        tournamentIds: Set<Int> = emptySet(),
        autoCommentary: Boolean? = null,
        introScorebug: Boolean? = null,
        commentaryDensity: String? = null,
    ) {
        // Không ghi cache rỗng — lần live sau sẽ mất logo
        if (!ScoreboardOverlay.hasCornerLogos() &&
            ScoreboardOverlay.pauseImage == null &&
            !ScoreboardOverlay.hasMarquee()
        ) {
            Log.w(TAG, "Skip save: overlay empty (keep previous cache if any)")
            // Vẫn lưu flags nếu có
            if (autoCommentary != null && introScorebug != null && commentaryDensity != null) {
                saveFlags(context, autoCommentary, introScorebug, commentaryDensity, tournamentIds)
            }
            return
        }
        clear(context)
        val dir = cacheDir(context)
        try {
            ScoreboardOverlay.topRightLogos.forEachIndexed { i, bmp ->
                saveBitmap(bmp, File(dir, "top_right_$i.png"))
            }
            ScoreboardOverlay.bottomRightLogos.forEachIndexed { i, bmp ->
                saveBitmap(bmp, File(dir, "bottom_right_$i.png"))
            }
            ScoreboardOverlay.pauseImage?.let { saveBitmap(it, File(dir, "pause.png")) }

            val marqueeFile = File(dir, "marquee.txt")
            marqueeFile.writeText(ScoreboardOverlay.marqueeTexts.joinToString("\n"))

            val tids = tournamentIds.sorted().joinToString(",")
            File(dir, "meta.txt").writeText(
                "tids:$tids\n${ScoreboardOverlay.topRightLogos.size}\n${ScoreboardOverlay.bottomRightLogos.size}"
            )

            if (autoCommentary != null && introScorebug != null && commentaryDensity != null) {
                saveFlags(context, autoCommentary, introScorebug, commentaryDensity, tournamentIds)
            }

            Log.d(
                TAG,
                "Saved overlay cache: tids=$tids top=${ScoreboardOverlay.topRightLogos.size} bottom=${ScoreboardOverlay.bottomRightLogos.size}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save overlay cache: ${e.message}")
        }
    }

    /**
     * Load overlay nếu cache khớp đúng set tournament đã chọn.
     * @return false nếu không có cache / lệch giải / lỗi
     */
    fun load(context: Context, expectedTournamentIds: Set<Int>): Boolean {
        val dir = cacheDir(context)
        val meta = File(dir, "meta.txt")
        if (!meta.exists()) return false

        try {
            val lines = meta.readText().split("\n")
            val tidLine = lines.getOrNull(0)?.trim().orEmpty()
            if (!tidLine.startsWith("tids:")) {
                Log.w(TAG, "Legacy overlay cache without tids — clearing")
                clear(context)
                return false
            }
            val cachedTids = tidLine.removePrefix("tids:")
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .toSet()

            if (expectedTournamentIds.isNotEmpty() && cachedTids != expectedTournamentIds) {
                Log.w(TAG, "Overlay cache tids mismatch: cached=$cachedTids expected=$expectedTournamentIds — clear")
                clear(context)
                ScoreboardOverlay.clearAll()
                return false
            }

            val topCount = lines.getOrNull(1)?.toIntOrNull() ?: 0
            val bottomCount = lines.getOrNull(2)?.toIntOrNull() ?: 0
            if (topCount <= 0 && bottomCount <= 0 && !File(dir, "pause.png").exists()) {
                Log.w(TAG, "Overlay cache empty logos — ignore")
                return false
            }

            val topList = mutableListOf<Bitmap>()
            for (i in 0 until topCount) {
                val f = File(dir, "top_right_$i.png")
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)?.let {
                        topList.add(BitmapUtils.clampToMaxEdge(it, BitmapUtils.MAX_LOGO_EDGE))
                    }
                }
            }
            ScoreboardOverlay.topRightLogos = topList
            // pickbaseLogo = brand logo_overlay — không lấy từ cache góc trên
            // (StreamManager.reloadBrandLogo sẽ nạp lại)

            val bottomList = mutableListOf<Bitmap>()
            for (i in 0 until bottomCount) {
                val f = File(dir, "bottom_right_$i.png")
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)?.let {
                        bottomList.add(BitmapUtils.clampToMaxEdge(it, BitmapUtils.MAX_LOGO_EDGE))
                    }
                }
            }
            ScoreboardOverlay.bottomRightLogos = bottomList
            if (bottomList.isNotEmpty()) {
                ScoreboardOverlay.tournamentLogo = bottomList.first()
            }

            val pauseFile = File(dir, "pause.png")
            ScoreboardOverlay.pauseImage = if (pauseFile.exists()) {
                BitmapFactory.decodeFile(pauseFile.absolutePath)?.let {
                    BitmapUtils.clampToMaxEdge(it, BitmapUtils.MAX_PAUSE_EDGE)
                }
            } else null

            val marqueeFile = File(dir, "marquee.txt")
            ScoreboardOverlay.marqueeTexts = if (marqueeFile.exists()) {
                marqueeFile.readText().split("\n").filter { it.isNotBlank() }
            } else emptyList()

            ScoreboardOverlay.loadedTournamentIds = cachedTids
            val flags = loadFlags(context, expectedTournamentIds)
            ScoreboardOverlay.introEnabled = flags.introScorebug
            Log.d(TAG, "Loaded overlay cache: tids=$cachedTids top=${topList.size} bottom=${bottomList.size} commentary=${flags.autoCommentary}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load overlay cache: ${e.message}")
            return false
        }
    }

    /** Clear disk cache (giữ SharedPreferences flags). */
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
