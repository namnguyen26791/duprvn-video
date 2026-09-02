package vn.vdpr.video.overlay

import android.graphics.*
import vn.vdpr.video.data.MatchState

/**
 * Overlay layout:
 * - Scoreboard + tournament name: TOP-LEFT corner (one block)
 * - Logos top-right: horizontal row from right edge
 * - Logos bottom-right: horizontal row from right edge
 * - Pause image: center of screen when paused
 * - Marquee text: scrolling at BOTTOM of screen
 */
object ScoreboardOverlay {

    // Broadcast-style palette
    private const val COLOR_BG = 0xF00F172A.toInt()           // slate-900 ~94%
    private const val COLOR_BORDER = 0xFF475569.toInt()       // slate-600
    private const val COLOR_HEADER = 0xFF047857.toInt()       // emerald-700
    private const val COLOR_HEADER_TEXT = 0xFFF0FDF4.toInt()
    private const val COLOR_ROW = 0xF01E293B.toInt()            // slate-800
    private const val COLOR_ROW_SERVE = 0xFF134E4A.toInt()     // teal-900 highlight
    private const val COLOR_SCORE_BG = 0xFF0F172A.toInt()
    private const val COLOR_SCORE_SERVE = 0xFF059669.toInt()    // emerald-600
    private const val COLOR_TEXT = 0xFFF8FAFC.toInt()
    private const val COLOR_TEXT_DIM = 0xFF94A3B8.toInt()
    private const val COLOR_SERVE = 0xFFFBBF24.toInt()          // amber-400
    private const val COLOR_DIVIDER = 0xFF334155.toInt()
    private const val COLOR_GRID = 0xFF475569.toInt()         // slate-600 grid lines
    private const val COLOR_SCORE_COL = 0xF0111827.toInt()     // cột tỉ số tối hơn

    var pickbaseLogo: Bitmap? = null
    var tournamentLogo: Bitmap? = null
    var topRightLogos: List<Bitmap> = emptyList()
        set(value) {
            field = value
            clearLogoScaleCache()
        }
    var bottomRightLogos: List<Bitmap> = emptyList()
        set(value) {
            field = value
            clearLogoScaleCache()
        }
    var marqueeTexts: List<String> = emptyList()
    var pauseImage: Bitmap? = null
    /** Giải nào đã load logo (khớp SharedPreferences tids hoặc API đơn giải). */
    var loadedTournamentIds: Set<Int> = emptySet()
    private var marqueeOffset = 0f

    /** Cache logo đã scale theo kích thước vẽ — tránh createScaledBitmap mỗi frame. */
    private val logoScaleCache = HashMap<String, Bitmap>()

    /**
     * Xóa toàn bộ overlay trong RAM — gọi khi đổi giải / trước reload cache hoặc API.
     * @param clearIntroState false khi chỉ swap logo giữa chừng live (giữ scorebug intro).
     */
    fun clearAll(recycleBitmaps: Boolean = true, clearIntroState: Boolean = true) {
        clearLogoScaleCache()
        if (recycleBitmaps) {
            // pickbaseLogo/tournamentLogo thường alias phần tử trong list — chỉ recycle 1 lần
            val unique = LinkedHashSet<Bitmap>()
            topRightLogos.forEach { unique.add(it) }
            bottomRightLogos.forEach { unique.add(it) }
            pickbaseLogo?.let { unique.add(it) }
            tournamentLogo?.let { unique.add(it) }
            pauseImage?.let { unique.add(it) }
            unique.forEach { b ->
                if (!b.isRecycled) {
                    try {
                        b.recycle()
                    } catch (_: Exception) {}
                }
            }
        }
        topRightLogos = emptyList()
        bottomRightLogos = emptyList()
        pickbaseLogo = null
        tournamentLogo = null
        pauseImage = null
        marqueeTexts = emptyList()
        loadedTournamentIds = emptySet()
        if (clearIntroState) clearIntro()
    }

    fun hasCornerLogos(): Boolean =
        topRightLogos.isNotEmpty() || bottomRightLogos.isNotEmpty()

    fun clearLogoScaleCache() {
        logoScaleCache.values.forEach { bmp ->
            try {
                if (!bmp.isRecycled) bmp.recycle()
            } catch (_: Exception) {}
        }
        logoScaleCache.clear()
    }

    fun hasMarquee(): Boolean = marqueeTexts.isNotEmpty()

    /** Intro scorebug: hiện ~10s ở đáy khi bắt đầu live / đổi trận. */
    @Volatile var introEnabled: Boolean = false
    private var introUntilMs: Long = 0L
    private var introMatch: MatchState? = null
    private var introShownKey: String? = null

    fun isIntroActive(): Boolean =
        introEnabled && System.currentTimeMillis() < introUntilMs && introMatch != null

    fun introAlpha(): Float {
        if (!isIntroActive()) return 0f
        val now = System.currentTimeMillis()
        val remain = introUntilMs - now
        val elapsed = INTRO_DURATION_MS - remain
        val fadeIn = 400L
        val fadeOut = 600L
        return when {
            elapsed < fadeIn -> (elapsed / fadeIn.toFloat()).coerceIn(0f, 1f)
            remain < fadeOut -> (remain / fadeOut.toFloat()).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    /**
     * Gọi khi có trận mới trên live (đổi cặp VĐV / lần đầu có match).
     * Davis Cup: teamName từ Firebase vẫn là tên VĐV/cặp — đúng yêu cầu.
     */
    fun maybeStartIntro(match: MatchState) {
        if (!introEnabled) return
        val key = "${match.left.teamName}|${match.right.teamName}|${match.roundName}"
        if (key == introShownKey) return
        introShownKey = key
        // Vào lại giữa trận (đã có điểm) — không chạy intro scorebug lại từ đầu
        if (match.scoreLeft + match.scoreRight > 0) {
            android.util.Log.i("PB_OVERLAY", "Intro scorebug SKIP mid-match: ${match.scoreLeft}-${match.scoreRight}")
            return
        }
        introMatch = match.copy(
            scoreLeft = match.scoreLeft,
            scoreRight = match.scoreRight,
        )
        introUntilMs = System.currentTimeMillis() + INTRO_DURATION_MS
        android.util.Log.i("PB_OVERLAY", "Intro scorebug START: ${match.left.teamName} vs ${match.right.teamName}")
    }

    /** Chỉ tắt hình intro đang hiện — giữ introShownKey để không phát lại khi reconnect. */
    fun stopIntroVisual() {
        introUntilMs = 0L
        introMatch = null
    }

    fun clearIntro() {
        introUntilMs = 0L
        introMatch = null
        introShownKey = null
    }

    private const val INTRO_DURATION_MS = 10_000L

    fun draw(canvas: Canvas, width: Int, height: Int, match: MatchState) {
        val s = height / 720f
        val margin = 12f * s
        drawScoreboard(canvas, match, s, margin, width)
        drawLogos(canvas, width, height, s, margin)
        drawMarquee(canvas, width, height, s)
        if (isIntroActive()) {
            drawIntroScorebug(canvas, width, height, introMatch ?: match, introAlpha())
        }
    }

    private fun headerLogo(): Bitmap? {
        // Chỉ brand logo_overlay (pickbaseLogo) — không lấy logo góc giải
        return pickbaseLogo?.takeIf { !it.isRecycled }
    }

    private fun drawScoreboard(canvas: Canvas, match: MatchState, s: Float, margin: Float, screenW: Int) {
        val pad = 10f * s
        val cornerR = 8f * s
        val boxW = screenW * 0.33f // +10% so với 0.30
        val boxX = margin
        val rowH = 32f * s
        val scoreColW = 46f * s

        val row1Serving = match.serve == "left"
        val row2Serving = match.serve == "right"
        val isSingles = match.matchFormat == "singles"

        val topLabel = buildString {
            if (!match.tournamentName.isNullOrEmpty()) append(match.tournamentName)
            if (!match.roundName.isNullOrEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(match.roundName)
            }
        }
        val hasLogo = headerLogo() != null
        val headerH = if (topLabel.isNotEmpty()) 28f * s else 0f
        val boxH = headerH + rowH * 2
        val boxY = margin
        val rect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)

        // Logo cột trái — cao bằng cả scoreboard
        var logoColW = 0f
        headerLogo()?.let { logo ->
            if (logo.width > 0 && logo.height > 0) {
                logoColW = (boxH * logo.width / logo.height).coerceIn(28f * s, boxW * 0.38f)
            }
        }
        val contentX = boxX + logoColW
        val scoreColLeft = boxX + boxW - scoreColW
        val nameMaxW = scoreColLeft - contentX - pad * 2 - 16f * s

        val row1Name = shortenName(match.left.teamName, namePaint(s), nameMaxW.coerceAtLeast(40f * s))
        val row2Name = shortenName(match.right.teamName, namePaint(s), nameMaxW.coerceAtLeast(40f * s))

        // Shadow
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66000000
            setShadowLayer(6f * s, 0f, 2f * s, 0x99000000.toInt())
        }
        canvas.drawRoundRect(rect, cornerR, cornerR, shadow)

        // Body
        canvas.drawRoundRect(rect, cornerR, cornerR, fillPaint(COLOR_BG))
        canvas.drawRoundRect(rect, cornerR, cornerR, strokePaint(COLOR_BORDER, 1.2f * s))

        // Logo full-height góc trái scoreboard
        if (logoColW > 0f) {
            headerLogo()?.let { logo ->
                val logoPad = 3f * s
                val logoBg = RectF(boxX + logoPad, boxY + logoPad, boxX + logoColW - logoPad, boxY + boxH - logoPad)
                canvas.drawRoundRect(logoBg, 4f * s, 4f * s, fillPaint(0xF5FFFFFF.toInt()))
                val lh = (boxH - logoPad * 2).toInt().coerceAtLeast(1)
                val lw = (logoColW - logoPad * 2).toInt().coerceAtLeast(1)
                val scaled = scaledLogo(logo, lw, lh)
                if (scaled != null) {
                    canvas.drawBitmap(scaled, null, logoBg,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
            }
            canvas.drawLine(contentX, boxY, contentX, boxY + boxH, strokePaint(COLOR_GRID, 1.3f * s))
        }

        // Header band + LIVE badge
        if (headerH > 0f) {
            val headerRect = RectF(contentX, boxY, boxX + boxW, boxY + headerH)
            val headerPath = Path().apply {
                addRoundRect(headerRect, floatArrayOf(cornerR, cornerR, cornerR, cornerR, 0f, 0f, 0f, 0f), Path.Direction.CW)
            }
            val headerGradient = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    contentX, boxY, boxX + boxW, boxY + headerH,
                    intArrayOf(0xFF064E3B.toInt(), 0xFF047857.toInt(), 0xFF10B981.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawPath(headerPath, headerGradient)
            canvas.drawLine(contentX, boxY + headerH, boxX + boxW, boxY + headerH,
                strokePaint(0xFF34D399.toInt(), 2f * s))

            // LIVE badge góc phải header
            val liveLabel = "LIVE"
            val liveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xF0FFFFFF.toInt()
                textSize = 9.5f * s
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                letterSpacing = 0.08f
            }
            val liveTextW = liveP.measureText(liveLabel)
            val liveX = boxX + boxW - pad
            val liveY = boxY + headerH - 8f * s
            canvas.drawCircle(liveX - liveTextW - 7f * s, liveY - 3.5f * s, 3.5f * s, fillPaint(0xFFEF4444.toInt()))
            liveP.textAlign = Paint.Align.RIGHT
            canvas.drawText(liveLabel, liveX, liveY, liveP)

            if (topLabel.isNotEmpty()) {
                val labelP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = COLOR_HEADER_TEXT
                    textSize = 11.5f * s
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val maxW = (liveX - liveTextW - 18f * s - contentX - pad).coerceAtLeast(20f * s)
                canvas.drawText(truncateText(topLabel, labelP, maxW), contentX + pad, boxY + headerH - 8f * s, labelP)
            }
        } else if (logoColW > 0f) {
            // Không có header text — vạch ngăn logo / phần điểm
            canvas.drawLine(contentX, boxY, boxX + boxW, boxY, strokePaint(COLOR_GRID, 1f * s))
        }

        val contentTop = boxY + headerH
        val contentBottom = boxY + boxH
        val rowMidY = contentTop + rowH

        // Nền cột tỉ số
        canvas.drawRect(RectF(scoreColLeft, contentTop, boxX + boxW, contentBottom), fillPaint(COLOR_SCORE_COL))

        // Lưới kẻ ngang / dọc
        val gridP = strokePaint(COLOR_GRID, 1.3f * s)
        canvas.drawLine(scoreColLeft, contentTop, scoreColLeft, contentBottom, gridP)
        canvas.drawLine(contentX, rowMidY, boxX + boxW, rowMidY, gridP)

        drawTeamRow(canvas, contentX, contentTop, scoreColLeft, boxX + boxW, rowH, pad, s,
            row1Name, match.scoreLeft, row1Serving, match.serverNum, isSingles)
        drawTeamRow(canvas, contentX, rowMidY, scoreColLeft, boxX + boxW, rowH, pad, s,
            row2Name, match.scoreRight, row2Serving, match.serverNum, isSingles)

        // Pickleball score notation (góc dưới scoreboard)
        val sScore = if (row1Serving) match.scoreLeft else match.scoreRight
        val rScore = if (row1Serving) match.scoreRight else match.scoreLeft
        val notation = if (isSingles) "$sScore – $rScore" else "$sScore – $rScore – ${match.serverNum}"
        val noteP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT_DIM
            textSize = 10.5f * s
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
        }
        canvas.drawText(notation.uppercase(), boxX + pad, boxY + boxH + 12f * s, noteP)
    }

    private fun drawTeamRow(
        canvas: Canvas, boxX: Float, rowTop: Float, scoreColLeft: Float, boxRight: Float,
        rowH: Float, pad: Float, s: Float, name: String, score: Int,
        isServing: Boolean, serverNum: Int, isSingles: Boolean,
    ) {
        val nameRight = scoreColLeft
        val rowRect = RectF(boxX, rowTop, nameRight, rowTop + rowH)
        if (isServing) {
            canvas.drawRect(rowRect, fillPaint(COLOR_ROW_SERVE))
            canvas.drawRect(
                RectF(boxX, rowTop, boxX + 4f * s, rowTop + rowH),
                fillPaint(COLOR_SCORE_SERVE),
            )
        }

        // Ô tỉ số — highlight khi đang giao bóng
        val scoreRect = RectF(scoreColLeft, rowTop, boxRight, rowTop + rowH)
        if (isServing) {
            canvas.drawRect(scoreRect, fillPaint(COLOR_SCORE_SERVE))
        }

        val np = namePaint(s)
        val baseline = rowTop + rowH * 0.62f
        canvas.drawText(name, boxX + pad + (if (isServing) 3f * s else 0f), baseline, np)

        if (isServing) {
            val nameW = np.measureText(name)
            val ballR = 3.5f * s
            var bx = boxX + pad + nameW + 8f * s
            val by = baseline - 4f * s
            canvas.drawCircle(bx, by, ballR, fillPaint(COLOR_SERVE))
            if (!isSingles && serverNum >= 2) {
                bx += ballR * 2.8f
                canvas.drawCircle(bx, by, ballR, fillPaint(COLOR_SERVE))
            }
        }

        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isServing) 0xFFFDE68A.toInt() else COLOR_TEXT
            textSize = if (isServing) 24f * s else 22f * s
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("$score", scoreRect.centerX(), scoreRect.centerY() + 8f * s, sp)
    }

    private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun namePaint(s: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        textSize = 15f * s
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun drawLogos(canvas: Canvas, width: Int, height: Int, s: Float, margin: Float) {
        val logoPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        // Chỉ vẽ đúng list góc — KHÔNG fallback pickbaseLogo/tournamentLogo
        // (fallback cũ khiến logo giải trước hiện lại cả trên + dưới khi list rỗng tạm thời)
        val topLogoY = margin + 10f * s
        var topLogoX = width.toFloat() - margin
        for (logo in topRightLogos.reversed()) {
            if (logo.isRecycled || logo.height <= 0 || logo.width <= 0) continue
            val logoH = (73f * s).toInt().coerceAtLeast(1) // 81px @720p, −10%
            val logoW = (logoH.toFloat() * logo.width / logo.height).toInt().coerceAtLeast(1)
            val scaled = scaledLogo(logo, logoW, logoH) ?: continue
            topLogoX -= logoW
            val dst = RectF(topLogoX, topLogoY, topLogoX + logoW, topLogoY + logoH.toFloat())
            canvas.drawBitmap(scaled, null, dst, logoPaint)
            topLogoX -= 10f * s
        }

        var bottomLogoX = width.toFloat() - margin
        val marqueeH = if (marqueeTexts.isNotEmpty()) 28f * s else 0f
        val bottomLogoH = (59f * s).toInt().coerceAtLeast(1)
        val bottomLogoY = height - marqueeH - bottomLogoH - 4f * s
        for (logo in bottomRightLogos.reversed()) {
            if (logo.isRecycled || logo.height <= 0 || logo.width <= 0) continue
            val tLogoH = bottomLogoH
            val tLogoW = (tLogoH.toFloat() * logo.width / logo.height).toInt().coerceAtLeast(1)
            val scaled = scaledLogo(logo, tLogoW, tLogoH) ?: continue
            bottomLogoX -= tLogoW
            val dst = RectF(bottomLogoX, bottomLogoY, bottomLogoX + tLogoW, bottomLogoY + tLogoH.toFloat())
            canvas.drawBitmap(scaled, null, dst, logoPaint)
            bottomLogoX -= 10f * s
        }
    }

    private fun drawMarquee(canvas: Canvas, width: Int, height: Int, s: Float) {
        if (marqueeTexts.isEmpty()) return

        val barH = 28f * s
        val barY = height - barH
        val barBg = Paint().apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
        canvas.drawRect(0f, barY, width.toFloat(), height.toFloat(), barBg)

        val textP = Paint().apply {
            color = Color.WHITE; textSize = 16f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true
        }

        val separator = "     ★     "
        val fullText = marqueeTexts.joinToString(separator)
        val textWidth = textP.measureText(fullText + separator)

        val textY = barY + barH * 0.7f
        canvas.drawText(fullText + separator + fullText, -marqueeOffset, textY, textP)

        marqueeOffset += 4.5f * s
        if (marqueeOffset >= textWidth) marqueeOffset = 0f
    }

    fun drawPaused(canvas: Canvas, width: Int, height: Int, match: MatchState) {
        draw(canvas, width, height, match)

        // If pause image is configured, show it centered
        pauseImage?.let { img ->
            if (img.isRecycled || img.width <= 0 || img.height <= 0) return@let
            val s = height / 720f
            val imgW = width * 0.70f
            val imgH = imgW * img.height / img.width
            val imgX = (width - imgW) / 2f
            val imgY = (height - imgH) / 2f - 50f * s
            val logoPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
            canvas.drawBitmap(img, null, RectF(imgX, imgY, imgX + imgW, imgY + imgH), logoPaint)
            // "TIME OUT" text below image
            val tp = Paint().apply { color = Color.parseColor("#FACC15"); textSize = 44f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER; setShadowLayer(4f * s, 0f, 2f * s, Color.BLACK) }
            canvas.drawText("TIME OUT", width / 2f, imgY + imgH + 40f * s, tp)
            return
        }

        // Fallback: text banner
        val bannerH = height * 0.10f
        val bannerY = (height - bannerH) / 2f
        val bp = Paint().apply { color = Color.parseColor("#E0000000"); style = Paint.Style.FILL }
        canvas.drawRect(0f, bannerY, width.toFloat(), bannerY + bannerH, bp)
        val tp = Paint().apply { color = Color.parseColor("#FACC15"); textSize = bannerH * 0.45f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        canvas.drawText("TIME OUT", width / 2f, bannerY + bannerH * 0.65f, tp)
    }

    /** Draw only logos + marquee (when no match data yet) */
    fun drawLogosOnly(canvas: Canvas, width: Int, height: Int) {
        val s = height / 720f
        val margin = 12f * s
        drawLogos(canvas, width, height, s, margin)
        drawMarquee(canvas, width, height, s)
        if (isIntroActive()) {
            introMatch?.let { drawIntroScorebug(canvas, width, height, it, introAlpha()) }
        }
    }

    /**
     * Bảng tỉ số mở đầu — ~25% chiều cao, đáy màn hình.
     * Logo giữa + tên VĐV hai bên + tỉ số.
     */
    private fun drawIntroScorebug(canvas: Canvas, width: Int, height: Int, match: MatchState, alpha: Float) {
        if (alpha <= 0.01f) return
        val s = height / 720f
        val panelH = height * 0.25f
        val panelY = height - panelH
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        val midX = width / 2f
        val pad = 20f * s

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((a * 0.92f).toInt().coerceIn(0, 255), 15, 23, 42)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, panelY, width.toFloat(), height.toFloat(), bg)

        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, 16, 185, 129)
        }
        canvas.drawRect(0f, panelY, width.toFloat(), panelY + 4f * s, accent)

        // Logo giữa scorebug
        var logoBottom = panelY + 12f * s
        headerLogo()?.takeIf { !it.isRecycled && it.width > 0 }?.let { logo ->
            val logoH = (panelH * 0.32f).toInt().coerceAtLeast(1)
            val logoW = (logoH.toFloat() * logo.width / logo.height).toInt()
                .coerceIn(1, (width * 0.22f).toInt().coerceAtLeast(1))
            val scaled = scaledLogo(logo, logoW, logoH) ?: return@let
            val logoLeft = midX - logoW / 2f
            val logoTop = panelY + 10f * s
            val logoBg = RectF(logoLeft - 4f * s, logoTop - 2f * s, logoLeft + logoW + 4f * s, logoTop + logoH + 2f * s)
            val whiteBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((a * 0.95f).toInt(), 255, 255, 255)
            }
            canvas.drawRoundRect(logoBg, 8f * s, 8f * s, whiteBg)
            val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.alpha = a
            }
            canvas.drawBitmap(scaled, null, RectF(logoLeft, logoTop, logoLeft + logoW, logoTop + logoH), bmpPaint)
            logoBottom = logoTop + logoH + 8f * s
        }

        // Tournament / round dưới logo (hoặc sát mép trên nếu không có logo)
        val sub = buildString {
            if (!match.tournamentName.isNullOrEmpty()) append(match.tournamentName)
            if (!match.roundName.isNullOrEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(match.roundName)
            }
        }
        var namesY = logoBottom + 18f * s
        if (sub.isNotEmpty()) {
            val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(a, 148, 163, 184)
                textSize = 13f * s
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(truncateText(sub, subP, width - pad * 2), midX, logoBottom + 14f * s, subP)
            namesY = logoBottom + 32f * s
        }

        val nameP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, 248, 250, 252)
            textSize = 20f * s
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val scoreP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, 52, 211, 153)
            textSize = 36f * s
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val scoreText = "${match.scoreLeft}  –  ${match.scoreRight}"
        val scoreW = scoreP.measureText(scoreText)
        val sideW = ((width - scoreW) / 2f - pad - 12f * s).coerceAtLeast(60f * s)

        val leftName = shortenName(match.left.teamName.ifBlank { "Đội 1" }, nameP, sideW)
        val rightName = shortenName(match.right.teamName.ifBlank { "Đội 2" }, nameP, sideW)

        val leftX = pad + sideW / 2f
        val rightX = width - pad - sideW / 2f
        // Căn giữa khối tên+điểm trong phần còn lại của panel
        val blockY = ((namesY + (panelY + panelH - 28f * s)) / 2f).coerceAtLeast(namesY)

        canvas.drawText(leftName, leftX, blockY, nameP)
        canvas.drawText(scoreText, midX, blockY + 4f * s, scoreP)
        canvas.drawText(rightName, rightX, blockY, nameP)

        val liveP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, 239, 68, 68)
        }
        val liveLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(a, 255, 255, 255)
            textSize = 12f * s
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val liveW = 52f * s
        val liveH = 20f * s
        val liveRect = RectF(midX - liveW / 2f, panelY + panelH - liveH - 10f * s, midX + liveW / 2f, panelY + panelH - 10f * s)
        canvas.drawRoundRect(liveRect, 10f * s, 10f * s, liveP)
        canvas.drawText("LIVE", liveRect.centerX(), liveRect.centerY() + 4.5f * s, liveLabel)
    }

    private fun scaledLogo(src: Bitmap, targetW: Int, targetH: Int): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0 || targetW <= 0 || targetH <= 0) return null
        if (src.width == targetW && src.height == targetH) return src
        val key = "${System.identityHashCode(src)}:${targetW}x${targetH}"
        logoScaleCache[key]?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        val scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true)
        logoScaleCache[key] = scaled
        return scaled
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = paint.measureText(ellipsis)
        for (i in text.length - 1 downTo 0) {
            if (paint.measureText(text, 0, i) + ellipsisW <= maxWidth) {
                return text.substring(0, i).trimEnd() + ellipsis
            }
        }
        return ellipsis
    }

    /**
     * Viết tắt tên VĐV nếu quá dài cho scoreboard.
     * Giữ nguyên họ (từ đầu) + tên (từ cuối), viết tắt các từ giữa.
     * VD: "Nguyễn Văn Quân Khôi Nam" → "Nguyễn V. Q. K. Nam"
     * 
     * Với teamName dạng "Tên1 - Tên2" (đôi), xử lý từng tên riêng.
     */
    private fun shortenName(fullName: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(fullName) <= maxWidth) return fullName

        // Đôi: "A - B"
        if (fullName.contains(" - ")) {
            val parts = fullName.split(" - ", limit = 2)
            val shortened = parts.joinToString(" - ") { shortenSingleName(it.trim()) }
            if (paint.measureText(shortened) <= maxWidth) return shortened
            // Vẫn dài quá → viết tắt mạnh hơn
            return parts.joinToString(" - ") { shortenSingleNameAggressive(it.trim()) }
        }

        val shortened = shortenSingleName(fullName)
        if (paint.measureText(shortened) <= maxWidth) return shortened
        return shortenSingleNameAggressive(fullName)
    }

    /**
     * Viết tắt các từ giữa (giữ họ + tên cuối).
     * "Nguyễn Văn Quân Khôi Nam" → "Nguyễn V. Q. K. Nam"
     */
    private fun shortenSingleName(name: String): String {
        val words = name.trim().split("\\s+".toRegex())
        if (words.size <= 2) return name
        val first = words.first()
        val last = words.last()
        val middle = words.subList(1, words.size - 1).joinToString(" ") { "${it.first()}." }
        return "$first $middle $last"
    }

    /**
     * Viết tắt mạnh: chỉ giữ họ viết tắt + tên cuối.
     * "Nguyễn Văn Quân Khôi Nam" → "N. V. Q. K. Nam"
     */
    private fun shortenSingleNameAggressive(name: String): String {
        val words = name.trim().split("\\s+".toRegex())
        if (words.size <= 1) return name
        val last = words.last()
        val initials = words.subList(0, words.size - 1).joinToString(" ") { "${it.first()}." }
        return "$initials $last"
    }
}
