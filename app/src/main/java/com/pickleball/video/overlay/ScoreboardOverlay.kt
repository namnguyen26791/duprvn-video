package com.pickleball.video.overlay

import android.graphics.*
import com.pickleball.video.data.MatchState

/**
 * Scoreboard: bottom-left, 2 rows with border.
 * Row 1: green dot + team name + ball icons (right of name) ... score
 * Row 2: team name ... score
 * Ball icons: ● = tay 1, ●● = tay 2 (after serving team name)
 */
object ScoreboardOverlay {

    fun draw(canvas: Canvas, width: Int, height: Int, match: MatchState) {
        val s = height / 720f
        val rowH = 38f * s
        val pad = 12f * s
        val margin = 16f * s
        val bottomGap = 50f * s
        val boxW = width * 0.42f
        val cornerR = 8f * s

        val leftName = if (match.courtSwapped) match.right.teamName else match.left.teamName
        val rightName = if (match.courtSwapped) match.left.teamName else match.right.teamName
        val leftServing = if (match.courtSwapped) match.serve == "right" else match.serve == "left"
        val serverNum = match.serverNum // 1 or 2 — server number in pickleball

        // Fixed: row 1 = left team, row 2 = right team (never swap)
        val row1Name = leftName
        val row2Name = rightName
        val row1Score = match.scoreLeft
        val row2Score = match.scoreRight
        val row1Serving = leftServing
        val row2Serving = !leftServing

        val boxH = rowH * 2 + pad * 3
        val boxX = margin
        val boxY = height - bottomGap - boxH
        val rect = RectF(boxX, boxY, boxX + boxW, boxY + boxH)

        // Background + border
        val bg = Paint().apply { color = Color.parseColor("#DD000000"); style = Paint.Style.FILL; isAntiAlias = true }
        val border = Paint().apply { color = Color.parseColor("#22C55E"); style = Paint.Style.STROKE; strokeWidth = 2f * s; isAntiAlias = true }
        canvas.drawRoundRect(rect, cornerR, cornerR, bg)
        canvas.drawRoundRect(rect, cornerR, cornerR, border)

        val teamP = Paint().apply { color = Color.WHITE; textSize = 20f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val scoreP = Paint().apply { color = Color.WHITE; textSize = 28f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.RIGHT }
        val ballP = Paint().apply { color = Color.parseColor("#FACC15"); style = Paint.Style.FILL; isAntiAlias = true }
        val divP = Paint().apply { color = Color.parseColor("#444444"); strokeWidth = 1f * s }

        // ── Row 1: Left team (fixed) ──
        val r1Y = boxY + pad + rowH * 0.7f
        canvas.drawText(row1Name, boxX + pad, r1Y, teamP)
        // Ball dots on serving team
        if (row1Serving) {
            val nameW = teamP.measureText(row1Name)
            val ballR = 4f * s
            val b1X = boxX + pad + nameW + 8f * s
            canvas.drawCircle(b1X, r1Y - 4f * s, ballR, ballP)
            if (serverNum >= 2) {
                canvas.drawCircle(b1X + ballR * 2.5f, r1Y - 4f * s, ballR, ballP)
            }
        }
        canvas.drawText("$row1Score", boxX + boxW - pad, r1Y, scoreP)

        // Divider
        val divY = boxY + pad + rowH + pad * 0.5f
        canvas.drawLine(boxX + pad, divY, boxX + boxW - pad, divY, divP)

        // ── Row 2: Right team (fixed) ──
        val r2Y = divY + pad + rowH * 0.5f
        canvas.drawText(row2Name, boxX + pad, r2Y, teamP)
        if (row2Serving) {
            val nameW2 = teamP.measureText(row2Name)
            val ballR = 4f * s
            val b2X = boxX + pad + nameW2 + 8f * s
            canvas.drawCircle(b2X, r2Y - 4f * s, ballR, ballP)
            if (serverNum >= 2) {
                canvas.drawCircle(b2X + ballR * 2.5f, r2Y - 4f * s, ballR, ballP)
            }
        }
        canvas.drawText("$row2Score", boxX + boxW - pad, r2Y, scoreP)

        // Score summary below box: serving-receiving-serverNum
        val sScore = if (leftServing) match.scoreLeft else match.scoreRight
        val rScore = if (leftServing) match.scoreRight else match.scoreLeft
        val sumP = Paint().apply { color = Color.parseColor("#94A3B8"); textSize = 14f * s; isAntiAlias = true; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("$sScore-$rScore-${match.serverNum}", boxX + pad, boxY + boxH + 16f * s, sumP)

        // Tournament name + Round name at TOP-LEFT
        val topLabel = buildString {
            if (!match.tournamentName.isNullOrEmpty()) append(match.tournamentName)
            if (!match.roundName.isNullOrEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(match.roundName)
            }
        }
        if (topLabel.isNotEmpty()) {
            val topBg = Paint().apply { color = Color.parseColor("#AA000000"); style = Paint.Style.FILL; isAntiAlias = true }
            val topBorder = Paint().apply { color = Color.parseColor("#22C55E"); style = Paint.Style.STROKE; strokeWidth = 1.5f * s; isAntiAlias = true }
            val topText = Paint().apply { color = Color.WHITE; textSize = 18f * s; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
            val tw = topText.measureText(topLabel) + pad * 4
            val topRect = RectF(margin, margin, margin + tw, margin + 28f * s)
            canvas.drawRoundRect(topRect, cornerR, cornerR, topBg)
            canvas.drawRoundRect(topRect, cornerR, cornerR, topBorder)
            canvas.drawText(topLabel, margin + pad * 2, margin + 20f * s, topText)
        }
    }

    fun drawPaused(canvas: Canvas, width: Int, height: Int, match: MatchState) {
        draw(canvas, width, height, match)
        val bannerH = height * 0.12f
        val bannerY = (height - bannerH) / 2f
        val bp = Paint().apply { color = Color.parseColor("#E0000000"); style = Paint.Style.FILL }
        canvas.drawRect(0f, bannerY, width.toFloat(), bannerY + bannerH, bp)
        val tp = Paint().apply { color = Color.parseColor("#FACC15"); textSize = bannerH * 0.45f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        canvas.drawText("TAM DUNG", width / 2f, bannerY + bannerH * 0.65f, tp)
    }
}
