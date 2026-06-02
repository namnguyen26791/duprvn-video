package asia.pickbase.video.overlay

import android.graphics.*
import asia.pickbase.video.data.MatchState

/**
 * Overlay layout:
 * - Scoreboard + tournament name: TOP-LEFT corner (one block)
 * - Logos top-right: horizontal row from right edge
 * - Logos bottom-right: horizontal row from right edge
 * - Pause image: center of screen when paused
 * - Marquee text: scrolling at BOTTOM of screen
 */
object ScoreboardOverlay {

    var pickbaseLogo: Bitmap? = null
    var tournamentLogo: Bitmap? = null
    var topRightLogos: List<Bitmap> = emptyList()
    var bottomRightLogos: List<Bitmap> = emptyList()
    var marqueeTexts: List<String> = emptyList()
    var pauseImage: Bitmap? = null
    private var marqueeOffset = 0f

    fun draw(canvas: Canvas, width: Int, height: Int, match: MatchState) {
        val s = height / 720f
        val margin = 12f * s
        val pad = 8f * s
        val cornerR = 6f * s

        // ═══════════════════════════════════════
        // SCOREBOARD — TOP-LEFT (compact, with tournament name attached)
        // ═══════════════════════════════════════
        val rowH = 28f * s
        val boxW = width * 0.23f

        val teamP = Paint().apply { color = Color.WHITE; textSize = 16f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val row1Name = shortenName(match.left.teamName, teamP, boxW - pad * 2 - 40f * s)
        val row2Name = shortenName(match.right.teamName, teamP, boxW - pad * 2 - 40f * s)
        val row1Score = match.scoreLeft
        val row2Score = match.scoreRight
        val row1Serving = match.serve == "left"
        val row2Serving = match.serve == "right"
        val serverNum = match.serverNum
        val isSingles = match.matchFormat == "singles"

        // Tournament name header (attached to top of scoreboard)
        val topLabel = buildString {
            if (!match.tournamentName.isNullOrEmpty()) append(match.tournamentName)
            if (!match.roundName.isNullOrEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(match.roundName)
            }
        }
        val headerH = if (topLabel.isNotEmpty()) 22f * s else 0f
        val boxH = headerH + rowH * 2 + pad * 2.5f
        val boxX = margin
        val boxY = margin

        val rect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)
        val bg = Paint().apply { color = Color.parseColor("#DD000000"); style = Paint.Style.FILL; isAntiAlias = true }
        val border = Paint().apply { color = Color.parseColor("#22C55E"); style = Paint.Style.STROKE; strokeWidth = 1.5f * s; isAntiAlias = true }
        canvas.drawRoundRect(rect, cornerR, cornerR, bg)
        canvas.drawRoundRect(rect, cornerR, cornerR, border)

        // Tournament name inside box header
        if (topLabel.isNotEmpty()) {
            val headerBg = Paint().apply { color = Color.parseColor("#22C55E"); style = Paint.Style.FILL; isAntiAlias = true }
            val headerRect = RectF(boxX, boxY, boxX + boxW, boxY + headerH)
            // Draw green header with top corners rounded
            val headerPath = Path().apply {
                addRoundRect(headerRect, floatArrayOf(cornerR, cornerR, cornerR, cornerR, 0f, 0f, 0f, 0f), Path.Direction.CW)
            }
            canvas.drawPath(headerPath, headerBg)
            val labelP = Paint().apply { color = Color.WHITE; textSize = 11f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val maxLabelW = boxW - pad * 2
            val truncatedLabel = truncateText(topLabel, labelP, maxLabelW)
            canvas.drawText(truncatedLabel, boxX + pad, boxY + headerH - 6f * s, labelP)
        }

        val scoreP = Paint().apply { color = Color.WHITE; textSize = 22f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.RIGHT }
        val ballP = Paint().apply { color = Color.parseColor("#FACC15"); style = Paint.Style.FILL; isAntiAlias = true }
        val divP = Paint().apply { color = Color.parseColor("#444444"); strokeWidth = 1f * s }

        val contentTop = boxY + headerH

        // Row 1
        val r1Y = contentTop + pad + rowH * 0.7f
        canvas.drawText(row1Name, boxX + pad, r1Y, teamP)
        if (row1Serving) {
            val nameW = teamP.measureText(row1Name)
            val ballR = 3f * s
            val b1X = boxX + pad + nameW + 6f * s
            canvas.drawCircle(b1X, r1Y - 3f * s, ballR, ballP)
            if (!isSingles && serverNum >= 2) canvas.drawCircle(b1X + ballR * 2.5f, r1Y - 3f * s, ballR, ballP)
        }
        canvas.drawText("$row1Score", boxX + boxW - pad, r1Y, scoreP)

        // Divider
        val divY = contentTop + pad + rowH + pad * 0.3f
        canvas.drawLine(boxX + pad, divY, boxX + boxW - pad, divY, divP)

        // Row 2
        val r2Y = divY + pad * 0.5f + rowH * 0.6f
        canvas.drawText(row2Name, boxX + pad, r2Y, teamP)
        if (row2Serving) {
            val nameW2 = teamP.measureText(row2Name)
            val ballR = 3f * s
            val b2X = boxX + pad + nameW2 + 6f * s
            canvas.drawCircle(b2X, r2Y - 3f * s, ballR, ballP)
            if (!isSingles && serverNum >= 2) canvas.drawCircle(b2X + ballR * 2.5f, r2Y - 3f * s, ballR, ballP)
        }
        canvas.drawText("$row2Score", boxX + boxW - pad, r2Y, scoreP)

        // Score summary below box
        val sScore = if (row1Serving) match.scoreLeft else match.scoreRight
        val rScore = if (row1Serving) match.scoreRight else match.scoreLeft
        val sumP = Paint().apply { color = Color.parseColor("#94A3B8"); textSize = 12f * s; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        val scoreText = if (isSingles) "$sScore-$rScore" else "$sScore-$rScore-${match.serverNum}"
        canvas.drawText(scoreText, boxX + pad, boxY + boxH + 14f * s, sumP)

        // ═══════════════════════════════════════
        // LOGOS
        // ═══════════════════════════════════════
        drawLogos(canvas, width, height, s, margin)

        // ═══════════════════════════════════════
        // MARQUEE TEXT — bottom of screen
        // ═══════════════════════════════════════
        drawMarquee(canvas, width, height, s)
    }

    private fun drawLogos(canvas: Canvas, width: Int, height: Int, s: Float, margin: Float) {
        val logoPaint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        // Top-right logos (horizontal from right)
        val activeTopLogos = if (topRightLogos.isNotEmpty()) topRightLogos else listOfNotNull(pickbaseLogo)
        var topLogoX = width.toFloat() - margin
        for (logo in activeTopLogos.reversed()) {
            val logoH = (81f * s).toInt()
            val logoW = (logoH.toFloat() * logo.width / logo.height).toInt()
            val scaled = if (logo.width < logoW * 2) {
                Bitmap.createScaledBitmap(logo, logoW, logoH, true)
            } else logo
            topLogoX -= logoW
            val dst = RectF(topLogoX, margin, topLogoX + logoW, margin + logoH.toFloat())
            canvas.drawBitmap(scaled, null, dst, logoPaint)
            topLogoX -= 10f * s
        }

        // Bottom-right logos (horizontal from right)
        val activeBottomLogos = if (bottomRightLogos.isNotEmpty()) bottomRightLogos else listOfNotNull(tournamentLogo)
        var bottomLogoX = width.toFloat() - margin
        val bottomLogoY = height - 50f * s - 65f * s
        for (logo in activeBottomLogos.reversed()) {
            val tLogoH = (65f * s).toInt()
            val tLogoW = (tLogoH.toFloat() * logo.width / logo.height).toInt()
            val scaled = if (logo.width < tLogoW * 2) {
                Bitmap.createScaledBitmap(logo, tLogoW, tLogoH, true)
            } else logo
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

        marqueeOffset += 3f * s
        if (marqueeOffset >= textWidth) marqueeOffset = 0f
    }

    fun drawPaused(canvas: Canvas, width: Int, height: Int, match: MatchState) {
        draw(canvas, width, height, match)

        // If pause image is configured, show it centered
        pauseImage?.let { img ->
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
    }

    /**
     * Cắt text + thêm "…" nếu vượt quá maxWidth.
     */
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
