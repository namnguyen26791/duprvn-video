package vn.vdpr.video.commentary

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import vn.vdpr.video.data.MatchState
import vn.vdpr.video.data.TeamSide
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * Bình luận TTS — bank lớn, đủ tên VĐV, nhận diện mất giao / đổi tay / chuỗi điểm.
 * Giọng ưu tiên nam ấm, hơi hài; phát loa (lọt mic live).
 */
class CommentaryEngine(private val context: Context) : TextToSpeech.OnInitListener {

    enum class Density { LOW, MEDIUM, HIGH }

    @Volatile var enabled: Boolean = false
    @Volatile var density: Density = Density.MEDIUM

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private var lastLeft = -1
    private var lastRight = -1
    private var lastPaused: Boolean? = null
    private var lastMatchKey: String? = null
    private var lastServe: String? = null
    private var lastServerNum: Int = -1
    private var lastServerHand: Int = -1
    private var lastCourtSwapped: Boolean? = null
    private var lastSpeakAt = 0L
    private var speaking = false
    private val recentPhrases = ArrayDeque<String>()
    private val pointStreak = mutableMapOf<String, Int>()
    private var pendingMatch: MatchState? = null
    @Volatile private var currentMatch: MatchState? = null
    private var lastScoreChangeAt = 0L
    private var tensionRunnable: Runnable? = null
    private var lastNameStyle = ""
    private var lastOtherStyle = ""
    private val anonOther = listOf(
        "đối phương",
        "bên kia",
        "cặp kia",
        "phía bên kia",
        "đối thủ",
    )
    private val softOther = listOf(
        "đối phương",
        "bên kia",
        "cặp đang nhận",
        "phía đối diện",
    )

    /**
     * Bật/tắt bình luận. Tắt = shutdown TTS hẳn (không giữ instance engine) cho nhẹ máy.
     */
    fun applyEnabled(on: Boolean) {
        enabled = on
        if (on) {
            start()
            android.util.Log.i("PB_COMMENTARY", "Commentary ON — starting TTS")
        } else {
            stopEngine()
            android.util.Log.i("PB_COMMENTARY", "Commentary OFF — TTS released")
        }
    }

    fun start() {
        if (!enabled) return
        if (tts != null) return
        val googlePkg = "com.google.android.tts"
        val hasGoogle = try {
            context.packageManager.getPackageInfo(googlePkg, 0)
            true
        } catch (_: Exception) {
            false
        }
        tts = if (hasGoogle) {
            android.util.Log.i("PB_COMMENTARY", "Using Google TTS engine")
            TextToSpeech(context, this, googlePkg)
        } else {
            android.util.Log.i("PB_COMMENTARY", "Using default TTS engine")
            TextToSpeech(context, this)
        }
    }

    /** Tắt TTS + loop, giữ object để có thể bật lại sau. */
    fun stopEngine() {
        abandonFocus()
        stopTensionLoop()
        pendingMatch = null
        currentMatch = null
        speaking = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        ready = false
        resetTracking()
    }

    fun release() {
        enabled = false
        stopEngine()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            android.util.Log.w("PB_COMMENTARY", "TTS init failed status=$status")
            ready = false
            return
        }
        val engine = tts ?: return
        val vi = Locale("vi", "VN")
        val langResult = when {
            engine.isLanguageAvailable(vi) >= TextToSpeech.LANG_AVAILABLE -> engine.setLanguage(vi)
            engine.isLanguageAvailable(Locale("vi")) >= TextToSpeech.LANG_AVAILABLE ->
                engine.setLanguage(Locale("vi"))
            else -> engine.setLanguage(Locale.getDefault())
        }
        ready = langResult >= 0
        preferWarmMaleVoice(engine)
        // Pitch thấp hơn nếu vẫn là giọng nữ mặc định — nghe “nam” hơn
        val vName = engine.voice?.name?.lowercase().orEmpty()
        val soundsFemale = vName.contains("female") || vName.contains("fem") ||
            vName.contains("x-vie") || vName.contains("x-vif") ||
            vName == "vi-vn-language" || vName.endsWith("-language")
        engine.setPitch(if (soundsFemale) 0.72f else 0.85f)
        engine.setSpeechRate(1.15f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) { speaking = false; abandonFocus() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking = false; abandonFocus() }
        })
        android.util.Log.i(
            "PB_COMMENTARY",
            "TTS ready=$ready voice=${engine.voice?.name} pitch=${if (soundsFemale) 0.72f else 0.85f}",
        )
        pendingMatch?.let { m ->
            pendingMatch = null
            mainHandler.post { onMatchUpdate(m) }
        }
    }

    /**
     * Chọn giọng nam Việt.
     * Google TTS: nam ≈ `…-x-vid-…`, nữ ≈ `…-x-vie-…` / `…-x-vif-…` / `vi-VN-language`.
     */
    private fun preferWarmMaleVoice(engine: TextToSpeech) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val all = engine.voices ?: return
            val viAll = all.filter { it.locale.language.equals("vi", true) }
            if (viAll.isEmpty()) {
                android.util.Log.w("PB_COMMENTARY", "No Vietnamese voices on device")
                return
            }
            android.util.Log.i(
                "PB_COMMENTARY",
                "VI voices (${viAll.size}): " + viAll.joinToString { "${it.name}(net=${it.isNetworkConnectionRequired})" },
            )

            fun score(v: Voice): Int {
                val n = v.name.lowercase()
                var s = 0
                // Nam (Google / Samsung)
                if (n.contains("x-vid") || n.contains("-vid-") || n.contains("_vid_")) s += 100
                if (n.contains("male") || n.contains("#male") || n.contains("-m-") || n.contains("_m_")) s += 90
                if (Regex("""(^|[^a-z])nam([^a-z]|$)""").containsMatchIn(n)) s += 80
                if (n.contains("dmm") || n.contains("wavenet-b") || n.contains("wavenet-d") ||
                    n.contains("standard-b") || n.contains("standard-d")
                ) s += 40
                // Nữ — phạt nặng
                if (n.contains("x-vie") || n.contains("x-vif") || n.contains("-vie-") || n.contains("-vif-")) s -= 120
                if (n.contains("female") || n.contains("fem") || n.contains("nữ") || n.contains("nu ")) s -= 120
                // Generic language pack thường là nữ
                if (n == "vi-vn-language" || n.endsWith("-language")) s -= 80
                if (v.quality >= Voice.QUALITY_HIGH) s += 8
                if (v.quality >= Voice.QUALITY_VERY_HIGH) s += 5
                // Ưu tiên offline; nếu không có nam local thì vẫn cho phép network nam
                if (!v.isNetworkConnectionRequired) s += 6
                return s
            }

            val local = viAll.filter { !it.isNetworkConnectionRequired }
            val pool = local.ifEmpty { viAll }
            val best = pool.maxByOrNull { score(it) } ?: return
            val bestScore = score(best)

            // Nếu điểm vẫn thấp (không nhận ra nam), thử lại cả voice mạng
            val finalVoice = if (bestScore < 40 && viAll.any { score(it) >= 40 }) {
                viAll.maxByOrNull { score(it) } ?: best
            } else best

            engine.voice = finalVoice
            android.util.Log.i(
                "PB_COMMENTARY",
                "Selected voice=${finalVoice.name} score=${score(finalVoice)} locale=${finalVoice.locale}",
            )
        } catch (e: Exception) {
            android.util.Log.w("PB_COMMENTARY", "preferWarmMaleVoice: ${e.message}")
        }
    }

    fun onMatchUpdate(match: MatchState?) {
        if (!enabled || tts == null) {
            if (match == null) resetTracking()
            return
        }
        if (match == null) {
            val ending = currentMatch
            if (ending != null && (ending.scoreLeft > 0 || ending.scoreRight > 0 || lastMatchKey != null)) {
                announceMatchEnd(ending)
            }
            resetTracking()
            pendingMatch = null
            currentMatch = null
            stopTensionLoop()
            return
        }
        if (!ready) {
            pendingMatch = match
            return
        }
        currentMatch = match

        val key = matchKey(match)
        val now = System.currentTimeMillis()
        val gap = when (density) {
            Density.LOW -> 6500L
            Density.MEDIUM -> 2800L
            Density.HIGH -> 1400L
        }

        if (key != lastMatchKey) {
            lastMatchKey = key
            lastLeft = match.scoreLeft
            lastRight = match.scoreRight
            lastPaused = match.paused
            lastServe = match.serve
            lastServerNum = match.serverNum
            lastServerHand = match.serverHand
            lastCourtSwapped = match.courtSwapped
            lastScoreChangeAt = now
            pointStreak.clear()
            // Vào lại giữa trận — không đọc câu mở đầu lại từ đầu
            val midMatch = match.scoreLeft + match.scoreRight > 0
            if (!midMatch) {
                announceStart(match)
            } else {
                android.util.Log.i(
                    "PB_COMMENTARY",
                    "Skip START (re-enter mid-match ${match.scoreLeft}-${match.scoreRight})",
                )
            }
            scheduleTensionLoop()
            return
        }

        // Đổi bên sân
        if (lastCourtSwapped != null && lastCourtSwapped != match.courtSwapped) {
            lastCourtSwapped = match.courtSwapped
            speak(fillTeams(CommentaryPhrases.COURT_SWAP, match), minGapMs = 1200)
        } else {
            lastCourtSwapped = match.courtSwapped
        }

        if (lastPaused == false && match.paused) {
            lastPaused = true
            stopTensionLoop()
            onPausedAnnounce(match)
            return
        }
        if (lastPaused == true && !match.paused) {
            lastPaused = false
            lastScoreChangeAt = now
            speak(fillTeams(CommentaryPhrases.RESUME, match), minGapMs = 1200)
            scheduleTensionLoop()
            return
        }
        lastPaused = match.paused
        if (match.paused) return

        val dl = match.scoreLeft - lastLeft
        val dr = match.scoreRight - lastRight
        val serveChanged = lastServe != null && lastServe != match.serve
        val handChanged = lastServe == match.serve &&
            (lastServerNum != match.serverNum || lastServerHand != match.serverHand) &&
            lastServerNum >= 0

        // Không đổi điểm — chỉ đổi giao / tay giao
        if (dl == 0 && dr == 0) {
            if (serveChanged) {
                val receiver = if (match.serve == "left") "left" else "right"
                val lost = if (match.serve == "left") "right" else "left"
                // Đổi giao: đọc đúng người vừa nhận quyền giao (serverNum)
                val phrase = fillPoint(CommentaryPhrases.SIDE_OUT, match, receiver, lost, MentionBias.POINT, preferServer = true)
                syncServe(match)
                lastScoreChangeAt = now
                speak(phrase, minGapMs = gap / 2)
                scheduleTensionLoop()
            } else if (handChanged) {
                val serving = if (match.serve == "left") "left" else "right"
                val phrase = fillPoint(CommentaryPhrases.HAND_CHANGE, match, serving, opposite(serving), MentionBias.POINT, preferServer = true)
                    .replace("{hand}", handLabel(match))
                syncServe(match)
                speak(phrase, minGapMs = gap / 2)
                scheduleTensionLoop()
            }
            return
        }

        val scorer: String? = when {
            dl > 0 && dr == 0 -> "left"
            dr > 0 && dl == 0 -> "right"
            else -> null
        }
        lastLeft = match.scoreLeft
        lastRight = match.scoreRight
        lastScoreChangeAt = now
        if (scorer == null) {
            syncServe(match)
            scheduleTensionLoop()
            return
        }

        val other = opposite(scorer)
        val prevOtherStreak = pointStreak[other] ?: 0
        pointStreak[scorer] = (pointStreak[scorer] ?: 0) + 1
        pointStreak[other] = 0

        val phrase = buildRichPhrase(match, scorer, serveChanged, handChanged, prevOtherStreak)
        syncServe(match)

        val my = if (scorer == "left") match.scoreLeft else match.scoreRight
        val opp = if (scorer == "left") match.scoreRight else match.scoreLeft
        val justWon = isWinScore(match, my, opp)
        val scorerHasGamePoint = isGamePoint(match, my, opp)
        val dramaticClose = justWon || scorerHasGamePoint || isGamePoint(match, opp, my) || isLateGame(match)

        if (justWon || scorerHasGamePoint) {
            speak(phrase, force = true, minGapMs = 0)
            scheduleTensionLoop()
            return
        }
        if (now - lastSpeakAt < gap && !isDramatic(match, scorer, serveChanged) && !dramaticClose) return
        if (density == Density.LOW && !isDramatic(match, scorer, serveChanged) && !dramaticClose) {
            val total = match.scoreLeft + match.scoreRight
            if (total % 3 != 0 && !serveChanged) return
        }
        speak(
            phrase,
            force = serveChanged || (pointStreak[scorer] ?: 0) >= 3 || dramaticClose,
            minGapMs = if (dramaticClose) gap / 4 else gap / 3,
        )
        scheduleTensionLoop()
    }

    /** Khi trọng tài lâu không ấn — BLV nói vui, giữ không khí (không cần tỉ số sát). */
    private fun scheduleTensionLoop() {
        stopTensionLoop()
        if (!enabled || !ready) return
        val interval = when (density) {
            Density.LOW -> 16000L
            Density.MEDIUM -> 11000L
            Density.HIGH -> 7500L
        }
        val r = object : Runnable {
            override fun run() {
                if (!enabled || !ready) return
                val m = currentMatch ?: return
                if (m.paused) {
                    mainHandler.postDelayed(this, interval)
                    return
                }
                val idleMs = System.currentTimeMillis() - lastScoreChangeAt
                // Lâu không có điểm / không có thao tác → luôn có gì đó vui để nói
                if (idleMs >= interval && !speaking) {
                    val lines = if (isLateGame(m) || hasAnyGamePoint(m)) {
                        CommentaryPhrases.CLOSING_TENSION
                    } else {
                        CommentaryPhrases.idleLines()
                    }
                    speak(fillTeams(lines, m), minGapMs = 2500)
                }
                mainHandler.postDelayed(this, interval)
            }
        }
        tensionRunnable = r
        mainHandler.postDelayed(r, interval)
    }

    private fun stopTensionLoop() {
        tensionRunnable?.let { mainHandler.removeCallbacks(it) }
        tensionRunnable = null
    }

    private fun syncServe(m: MatchState) {
        lastServe = m.serve
        lastServerNum = m.serverNum
        lastServerHand = m.serverHand
    }

    private fun opposite(side: String) = if (side == "left") "right" else "left"

    private fun handLabel(m: MatchState): String {
        val n = when {
            m.serverNum in 1..2 -> m.serverNum
            m.serverHand in 1..2 -> m.serverHand
            else -> 1
        }
        return vnNumber(n)
    }

    /** Mở đầu trận: ưu tiên câu có tên giải; luôn đọc tên giải nếu có. */
    private fun announceStart(m: MatchState) {
        val tour = m.tournamentName?.trim().orEmpty()
        val pool = if (tour.isNotEmpty()) {
            CommentaryPhrases.START.filter { it.contains("{tour}") }.ifEmpty { CommentaryPhrases.START }
        } else {
            CommentaryPhrases.START
        }
        var text = fillTeams(pool, m, varyNames = false)
        if (tour.isNotEmpty() && !text.contains(tour, ignoreCase = true)) {
            text = "Xin kính chào quý vị đến với $tour. $text"
        }
        speak(text, force = true, minGapMs = 0)
    }

    /**
     * Đọc số bằng chữ tiếng Việt để TTS không đọc 10 thành "một không".
     * Hỗ trợ 0–999 (đủ cho tỉ số / win_score / max).
     */
    private fun vnNumber(n: Int): String {
        if (n < 0) return n.toString()
        val ones = arrayOf("không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín")
        fun under100(x: Int): String = when {
            x < 10 -> ones[x]
            x == 10 -> "mười"
            x < 20 -> when (val u = x % 10) {
                5 -> "mười lăm"
                else -> "mười ${ones[u]}"
            }
            else -> {
                val t = x / 10
                val u = x % 10
                val tens = "${ones[t]} mươi"
                when (u) {
                    0 -> tens
                    1 -> "$tens mốt"
                    4 -> "$tens tư"
                    5 -> "$tens lăm"
                    else -> "$tens ${ones[u]}"
                }
            }
        }
        return when {
            n < 100 -> under100(n)
            n < 1000 -> {
                val h = n / 100
                val r = n % 100
                val hundred = if (h == 1) "một trăm" else "${ones[h]} trăm"
                when {
                    r == 0 -> hundred
                    r < 10 -> "$hundred lẻ ${ones[r]}"
                    else -> "$hundred ${under100(r)}"
                }
            }
            else -> n.toString().map { c ->
                if (c.isDigit()) ones[c - '0'] else c.toString()
            }.joinToString(" ")
        }
    }

    /**
     * Trọng tài pause vì nhiều lý do — chỉ nói TIMEOUT khi đúng là tạm dừng/timeout.
     * Chạm win_score → hỏi kết thúc (pauseReason=end); nửa trận → hỏi đổi sân (switch).
     */
    private fun onPausedAnnounce(m: MatchState) {
        val reason = m.pauseReason.trim().lowercase()
        val left = m.scoreLeft
        val right = m.scoreRight
        val won = isWinScore(m, left, right) || isWinScore(m, right, left)
        val mid = (winTarget(m) + 1) / 2
        val atMidSwitch = mid > 0 && maxOf(left, right) >= mid && !won && !hasAnyGamePoint(m)
        val now = System.currentTimeMillis()

        when {
            reason == "end" || won -> {
                // Điểm thắng vừa được đọc ở buildRichPhrase — tránh chồng thêm TIMEOUT
                if (now - lastSpeakAt < 7000) {
                    android.util.Log.i("PB_COMMENTARY", "Pause end skip (already spoke win/closing)")
                    return
                }
                announceMatchEnd(m)
            }
            reason == "switch" || (reason.isEmpty() && atMidSwitch) -> {
                android.util.Log.i("PB_COMMENTARY", "Pause switch — skip TIMEOUT")
                // Đổi sân thật sẽ nói COURT_SWAP khi courtSwapped đổi
            }
            else -> {
                // timeout / manual / không rõ
                speak(fillTeams(CommentaryPhrases.TIMEOUT, m), minGapMs = 1200)
            }
        }
    }

    private fun isDramatic(m: MatchState, scorer: String, sideOut: Boolean): Boolean {
        if (sideOut) return true
        val a = m.scoreLeft
        val b = m.scoreRight
        val lead = abs(a - b)
        val high = maxOf(a, b)
        val late = lateThreshold(m)
        if (high >= late && lead <= 2) return true
        if (high >= late + 1) return true
        if (hasAnyGamePoint(m)) return true
        if ((pointStreak[scorer] ?: 0) >= 2) return true
        if (a == b && high >= maxOf(4, late - 4)) return true
        return false
    }

    /** Điểm thắng theo luật giải; thiếu cấu hình thì mặc định pickleball 11. */
    private fun winTarget(m: MatchState): Int = m.winScore.takeIf { it > 0 } ?: 11

    private fun maxCap(m: MatchState): Int = m.maxScore.coerceAtLeast(0)

    private fun lateThreshold(m: MatchState): Int {
        val t = winTarget(m)
        return (t - 2).coerceAtLeast((t * 2) / 3).coerceAtLeast(1)
    }

    /**
     * Thắng khi: đạt win_score và cách ≥ 2; hoặc chạm max_score và hơn đối thủ.
     * Khớp luật trọng tài V2.
     */
    private fun isWinScore(m: MatchState, my: Int, opp: Int): Boolean {
        if (my <= opp) return false
        val max = maxCap(m)
        if (max > 0 && my >= max) return true
        return my >= winTarget(m) && (my - opp) >= 2
    }

    /** Đội my chỉ cần thêm 1 điểm nữa là thắng. */
    private fun isGamePoint(m: MatchState, my: Int, opp: Int): Boolean =
        isWinScore(m, my + 1, opp)

    private fun hasAnyGamePoint(m: MatchState): Boolean =
        isGamePoint(m, m.scoreLeft, m.scoreRight) || isGamePoint(m, m.scoreRight, m.scoreLeft)

    /** Đã chạm/ vượt mốc win_score nhưng chưa cách 2 — kéo dài theo luật. */
    private fun isExtended(m: MatchState): Boolean {
        val high = maxOf(m.scoreLeft, m.scoreRight)
        return high >= winTarget(m) && !isWinScore(m, m.scoreLeft, m.scoreRight) &&
            !isWinScore(m, m.scoreRight, m.scoreLeft)
    }

    private fun isLateGame(m: MatchState): Boolean =
        maxOf(m.scoreLeft, m.scoreRight) >= lateThreshold(m) || isExtended(m) || hasAnyGamePoint(m)

    private fun announceMatchEnd(m: MatchState) {
        val now = System.currentTimeMillis()
        // Vừa nói điểm thắng / game point — tránh chồng câu kết thúc
        if (now - lastSpeakAt < 7000) {
            android.util.Log.i("PB_COMMENTARY", "Match end skip (just spoke ${now - lastSpeakAt}ms ago)")
            return
        }
        val left = m.scoreLeft
        val right = m.scoreRight
        val text = when {
            isWinScore(m, left, right) ->
                fillPoint(CommentaryPhrases.MATCH_WON, m, "left", "right", MentionBias.BIG)
            isWinScore(m, right, left) ->
                fillPoint(CommentaryPhrases.MATCH_WON, m, "right", "left", MentionBias.BIG)
            else ->
                fillTeams(CommentaryPhrases.MATCH_END, m, varyNames = false)
        }
        android.util.Log.i("PB_COMMENTARY", "Match end announce: $left-$right → ${text.take(80)}")
        speak(text, force = true, minGapMs = 0)
    }

    private fun buildRichPhrase(
        m: MatchState,
        scorer: String,
        sideOut: Boolean,
        handChanged: Boolean,
        prevOtherStreak: Int,
    ): String {
        val my = if (scorer == "left") m.scoreLeft else m.scoreRight
        val opp = if (scorer == "left") m.scoreRight else m.scoreLeft
        val streak = pointStreak[scorer] ?: 1
        val scoreCall = scoreCall(m)
        val servingSide = m.serve
        val otherSide = opposite(scorer)

        val bias = when {
            isWinScore(m, my, opp) -> MentionBias.BIG
            isGamePoint(m, my, opp) -> MentionBias.BIG
            streak >= 3 -> MentionBias.STREAK
            else -> MentionBias.POINT
        }
        // Giữ giao / vừa ghi điểm khi đang giao → ưu tiên đúng người giao
        val preferServer = scorer == servingSide || sideOut || handChanged
        val (name, other) = resolvePointNames(m, scorer, otherSide, bias, preferServer)

        // Ưu tiên tuyệt đối: vừa thắng / đang có game point
        if (isWinScore(m, my, opp)) {
            val wonPool = mutableListOf<String>()
            wonPool.addAll(applyPoint(CommentaryPhrases.MATCH_WON, name, other, scoreCall, m))
            if (maxCap(m) > 0 && my >= maxCap(m)) {
                wonPool.addAll(applyPoint(CommentaryPhrases.MAX_SCORE_WIN, name, other, scoreCall, m))
            }
            return pickFrom(wonPool)
        }
        if (isGamePoint(m, my, opp)) {
            val gp = mutableListOf<String>()
            gp.addAll(applyPoint(CommentaryPhrases.GAME_POINT, name, other, scoreCall, m))
            gp.addAll(applyPoint(CommentaryPhrases.MATCH_POINTISH, name, other, scoreCall, m))
            gp.addAll(applyPoint(CommentaryPhrases.CLUTCH, name, other, scoreCall, m))
            if (maxCap(m) > 0 && my + 1 >= maxCap(m)) {
                gp.addAll(applyPoint(CommentaryPhrases.NEAR_MAX, name, other, scoreCall, m))
            }
            return pickFrom(gp)
        }

        val pool = mutableListOf<String>()

        // Ưu tiên bank câu viết tay theo tình huống (không tổ hợp máy)
        when {
            sideOut -> {
                pool.addAll(applyPoint(CommentaryPhrases.SIDE_OUT, name, other, scoreCall, m))
                pool.addAll(applyPoint(CommentaryPhrases.BREAK_LIKE, name, other, scoreCall, m))
                pool.addAll(applyPoint(CommentaryPhrases.HYPE, name, other, scoreCall, m))
            }
            handChanged -> {
                pool.addAll(
                    applyPoint(CommentaryPhrases.HAND_CHANGE, name, other, scoreCall, m)
                        .map { it.replace("{hand}", handLabel(m)) },
                )
                pool.addAll(applyPoint(CommentaryPhrases.HOLD_SERVE, name, other, scoreCall, m))
            }
            scorer == servingSide -> {
                pool.addAll(applyPoint(CommentaryPhrases.HOLD_SERVE, name, other, scoreCall, m))
                pool.addAll(applyPoint(CommentaryPhrases.POINT, name, other, scoreCall, m))
            }
            else -> {
                pool.addAll(applyPoint(CommentaryPhrases.BREAK_LIKE, name, other, scoreCall, m))
                pool.addAll(applyPoint(CommentaryPhrases.POINT_VS, name, other, scoreCall, m))
            }
        }

        pool.addAll(applyPoint(CommentaryPhrases.POINT, name, other, scoreCall, m))
        pool.addAll(applyPoint(CommentaryPhrases.POINT_VS, name, other, scoreCall, m))
        if (Random.nextFloat() < 0.55f) {
            pool.addAll(applyPoint(CommentaryPhrases.FUNNY, name, other, scoreCall, m))
        }
        if (Random.nextFloat() < 0.40f) {
            pool.addAll(applyPoint(CommentaryPhrases.HYPE, name, other, scoreCall, m))
        }
        if (Random.nextFloat() < 0.35f) {
            pool.addAll(applyPoint(CommentaryPhrases.PREDICT, name, other, scoreCall, m))
        }

        if (streak >= 2) {
            pool.addAll(
                applyPoint(CommentaryPhrases.STREAK, name, other, scoreCall, m)
                    .map { it.replace("{n}", vnNumber(streak)) },
            )
        }
        if (prevOtherStreak >= 2) {
            pool.addAll(applyPoint(CommentaryPhrases.STOP_STREAK, name, other, scoreCall, m))
        }
        if (my == opp) {
            pool.addAll(applyPoint(CommentaryPhrases.TIE, name, other, scoreCall, m))
        }
        if (my == opp + 1 && my >= 2) {
            pool.addAll(applyPoint(CommentaryPhrases.TAKE_LEAD, name, other, scoreCall, m))
        }
        if (my < opp && opp - my <= 3 && my >= 3) {
            pool.addAll(applyPoint(CommentaryPhrases.COMEBACK, name, other, scoreCall, m))
        }
        if (abs(my - opp) >= 4 && my > opp) {
            pool.addAll(applyPoint(CommentaryPhrases.DOMINATE, name, other, scoreCall, m))
        }
        val late = lateThreshold(m)
        if (maxOf(my, opp) >= late && abs(my - opp) <= 2) {
            pool.addAll(applyPoint(CommentaryPhrases.CLUTCH, name, other, scoreCall, m))
        }
        if (maxOf(my, opp) >= late + 1 && abs(my - opp) <= 1) {
            pool.addAll(applyPoint(CommentaryPhrases.MATCH_POINTISH, name, other, scoreCall, m))
        }
        if (isExtended(m)) {
            pool.addAll(applyPoint(CommentaryPhrases.EXTENDED_WIN_BY_TWO, name, other, scoreCall, m))
        }
        // Đối phương đang có game point mà mình vừa ghi điểm (cứu nguy)
        if (isGamePoint(m, opp, my)) {
            pool.addAll(applyPoint(CommentaryPhrases.CLUTCH, name, other, scoreCall, m))
            pool.addAll(applyPoint(CommentaryPhrases.COMEBACK, name, other, scoreCall, m))
            pool.addAll(applyPoint(CommentaryPhrases.HYPE, name, other, scoreCall, m))
        }
        if (my + opp <= 2) {
            pool.addAll(applyPoint(CommentaryPhrases.ZERO_ZERO, name, other, scoreCall, m))
        }

        return pickFrom(pool)
    }

    private enum class MentionBias { POINT, STREAK, BIG }

    /**
     * Xoay cách gọi tên: solo / đồng đội / cả cặp / ẩn đối thủ.
     * Không đọc đủ 4 VĐV mọi câu — tránh nhàm.
     */
    private fun resolvePointNames(
        m: MatchState,
        focusSide: String,
        otherSide: String,
        bias: MentionBias,
        preferServer: Boolean = false,
    ): Pair<String, String> {
        val focus = if (focusSide == "left") m.left else m.right
        val rival = if (otherSide == "left") m.left else m.right
        val focusPlayers = playersOf(focus)
        val rivalPlayers = playersOf(rival)
        val isSingles = m.matchFormat == "singles" || focusPlayers.size <= 1

        val name = pickFocusMention(m, focusSide, focusPlayers, isSingles, bias, preferServer)
        val other = pickOtherMention(rivalPlayers, isSingles, bias)
        return name to other
    }

    private fun pickFocusMention(
        m: MatchState,
        side: String,
        players: List<String>,
        singles: Boolean,
        bias: MentionBias,
        preferServer: Boolean,
    ): String {
        if (players.isEmpty()) return "đội này"
        if (singles || players.size == 1) return players.first()

        val server = servingPlayer(m, side, players)
        val mate = players.firstOrNull { it != server } ?: players.last()
        val isServingSide = m.serve == side
        // soft_pair: chỉ 1 tên + "đồng đội" — không đọc đủ 2 người
        val softPair = "$server và đồng đội"

        val styles = when {
            // Ưu tiên tuyệt đối người đang giao — gần như luôn solo
            preferServer || isServingSide -> when (bias) {
                MentionBias.BIG -> listOf("solo" to 72, "soft_pair" to 20, "side" to 8)
                MentionBias.STREAK -> listOf("solo" to 82, "soft_pair" to 10, "side" to 8)
                MentionBias.POINT -> listOf("solo" to 90, "side" to 7, "soft_pair" to 3)
            }
            else -> when (bias) {
                MentionBias.BIG -> listOf("solo" to 70, "mate" to 15, "soft_pair" to 15)
                MentionBias.STREAK -> listOf("solo" to 65, "mate" to 25, "soft_pair" to 10)
                MentionBias.POINT -> listOf("solo" to 70, "mate" to 20, "side" to 10)
            }
        }
        val style = weightedStyle(styles, lastNameStyle)
        lastNameStyle = style
        return when (style) {
            "mate" -> mate
            "soft_pair" -> softPair
            "pair" -> softPair // không còn đọc đủ 2 tên trong điểm thường
            "side" -> if (isServingSide) "bên giao" else "cặp nhận"
            else -> if (isServingSide || preferServer) server else players.random()
        }
    }

    private fun pickOtherMention(
        players: List<String>,
        singles: Boolean,
        bias: MentionBias,
    ): String {
        if (players.isEmpty()) return anonOther.random()
        if (singles || players.size == 1) {
            val styles = listOf("anon" to 70, "named" to 30)
            return when (weightedStyle(styles, lastOtherStyle).also { lastOtherStyle = it }) {
                "named" -> players.first()
                else -> anonOther.random()
            }
        }

        // Đối thủ: chủ yếu ẩn danh — tránh nhắc thêm 2 tên
        val styles = when (bias) {
            MentionBias.BIG -> listOf(
                "anon" to 45,
                "soft" to 20,
                "solo" to 25,
                "soft_pair" to 10,
            )
            MentionBias.STREAK -> listOf(
                "anon" to 55,
                "soft" to 25,
                "solo" to 20,
            )
            MentionBias.POINT -> listOf(
                "anon" to 62,
                "soft" to 23,
                "solo" to 15,
            )
        }
        val style = weightedStyle(styles, lastOtherStyle)
        lastOtherStyle = style
        return when (style) {
            "soft_pair" -> "${players.random()} và đồng đội"
            "solo" -> players.random()
            "soft" -> softOther.random()
            else -> anonOther.random()
        }
    }

    private fun weightedStyle(weights: List<Pair<String, Int>>, avoid: String): String {
        val filtered = weights.filter { it.first != avoid }.ifEmpty { weights }
        val total = filtered.sumOf { it.second }
        var r = Random.nextInt(total.coerceAtLeast(1))
        for ((style, w) in filtered) {
            r -= w
            if (r < 0) return style
        }
        return filtered.last().first
    }

    /** Người đang giao đúng theo serverNum / serverHand (1 hoặc 2). */
    private fun servingPlayer(m: MatchState, side: String, players: List<String>): String {
        if (players.isEmpty()) return "đội này"
        if (players.size == 1) return players.first()
        val idx = when {
            m.serve == side && m.serverNum in 1..players.size -> m.serverNum - 1
            m.serve == side && m.serverHand in 1..players.size -> m.serverHand - 1
            m.serve == side -> 0
            else -> Random.nextInt(players.size)
        }
        return players[idx.coerceIn(0, players.lastIndex)]
    }

    /** Người đang “nổi” trên đội (idle / mentionSide). */
    private fun activePlayer(m: MatchState, side: String, players: List<String>): String =
        servingPlayer(m, side, players)

    private fun playersOf(side: TeamSide): List<String> {
        val fromHands = listOf(side.hand1, side.hand2).map { it.trim() }.filter { it.isNotEmpty() }
        if (fromHands.isNotEmpty()) return fromHands.distinct()
        val raw = side.teamName.trim()
        if (raw.isEmpty()) return emptyList()
        val parts = when {
            raw.contains(" - ") -> raw.split(" - ")
            raw.contains(" – ") -> raw.split(" – ")
            raw.contains(" và ") -> raw.split(" và ")
            raw.contains(" & ") -> raw.split(" & ")
            else -> listOf(raw)
        }.map { it.trim() }.filter { it.isNotEmpty() }
        return parts.distinct()
    }

    private fun pairLabel(players: List<String>): String = when {
        players.isEmpty() -> "đội này"
        players.size == 1 -> players.first()
        else -> players.joinToString(" và ")
    }

    /** Đủ tên cặp — dùng intro / kết thúc. */
    private fun fullName(side: TeamSide): String {
        val players = playersOf(side)
        if (players.isNotEmpty()) return pairLabel(players)
        return "đội kia"
    }

    /** Gọi tên một bên khi idle/timeout — hầu như 1 người (ưu tiên người giao). */
    private fun mentionSide(m: MatchState, sideKey: String, side: TeamSide): String {
        val players = playersOf(side)
        if (players.isEmpty()) return "đội kia"
        if (players.size == 1 || m.matchFormat == "singles") return players.first()
        val server = servingPlayer(m, sideKey, players)
        return when (Random.nextInt(100)) {
            in 0..74 -> server
            in 75..89 -> players.firstOrNull { it != server } ?: server
            else -> "$server và đồng đội"
        }
    }

    private fun applyPoint(
        templates: List<String>,
        name: String,
        other: String,
        score: String,
        m: MatchState? = null,
    ): List<String> {
        val target = vnNumber(m?.let { winTarget(it) } ?: 11)
        val max = m?.let { maxCap(it).takeIf { c -> c > 0 }?.let { c -> vnNumber(c) } } ?: ""
        return templates.map {
            it.replace("{name}", name)
                .replace("{other}", other)
                .replace("{score}", score)
                .replace("{target}", target)
                .replace("{max}", max)
        }
    }

    private fun fillTeams(templates: List<String>, m: MatchState, varyNames: Boolean = true): String {
        val left = if (varyNames) mentionSide(m, "left", m.left) else fullName(m.left)
        val right = if (varyNames) mentionSide(m, "right", m.right) else fullName(m.right)
        val target = vnNumber(winTarget(m))
        val max = maxCap(m).takeIf { it > 0 }?.let { vnNumber(it) } ?: ""
        val filled = templates.map {
            it.replace("{left}", left)
                .replace("{right}", right)
                .replace("{tour}", m.tournamentName?.trim()?.takeIf { t -> t.isNotEmpty() } ?: "giải đấu")
                .replace("{round}", m.roundName?.trim()?.takeIf { t -> t.isNotEmpty() } ?: "trận đấu")
                .replace("{score}", scoreCall(m))
                .replace("{target}", target)
                .replace("{max}", max)
        }
        return pickFrom(filled)
    }

    private fun fillPoint(
        templates: List<String>,
        m: MatchState,
        scorer: String,
        otherSide: String,
        bias: MentionBias = MentionBias.POINT,
        preferServer: Boolean = false,
    ): String {
        val (name, other) = resolvePointNames(m, scorer, otherSide, bias, preferServer)
        return pickFrom(applyPoint(templates, name, other, scoreCall(m), m))
    }

    private fun scoreCall(m: MatchState): String {
        val s = if (m.serve == "left") m.scoreLeft else m.scoreRight
        val r = if (m.serve == "left") m.scoreRight else m.scoreLeft
        return if (m.matchFormat == "singles") {
            "${vnNumber(s)} ${vnNumber(r)}"
        } else {
            "${vnNumber(s)} ${vnNumber(r)} ${vnNumber(m.serverNum.coerceIn(1, 2))}"
        }
    }

    private fun matchKey(m: MatchState) =
        "${m.left.teamName}|${m.right.teamName}|${m.tournamentName}|${m.roundName}"

    private fun resetTracking() {
        lastLeft = -1
        lastRight = -1
        lastPaused = null
        lastMatchKey = null
        lastServe = null
        lastServerNum = -1
        lastServerHand = -1
        lastCourtSwapped = null
        lastNameStyle = ""
        lastOtherStyle = ""
        pointStreak.clear()
        stopTensionLoop()
    }

    private fun pickFrom(options: List<String>): String {
        if (options.isEmpty()) return ""
        val filtered = options.filter { it !in recentPhrases }
        val pool = if (filtered.size >= 3) filtered else options.filter { phrase ->
            recentPhrases.takeLast(20).none { it == phrase }
        }.ifEmpty { options }
        val chosen = pool[Random.nextInt(pool.size)]
        recentPhrases.addLast(chosen)
        while (recentPhrases.size > 100) recentPhrases.removeFirst()
        return chosen
    }

    private fun speak(text: String, force: Boolean = false, minGapMs: Long = 2500) {
        if (!enabled || !ready || text.isBlank()) return
        val now = System.currentTimeMillis()
        if (!force && speaking) return
        if (!force && now - lastSpeakAt < minGapMs) return
        lastSpeakAt = now
        requestFocus()
        val engine = tts ?: return
        val id = UUID.randomUUID().toString()
        val run = Runnable {
            try {
                try {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (max > 0 && cur == 0) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.45f).toInt().coerceAtLeast(1), 0)
                    }
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val params = Bundle()
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                } else {
                    @Suppress("DEPRECATION")
                    engine.speak(
                        text,
                        TextToSpeech.QUEUE_FLUSH,
                        hashMapOf(
                            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to id,
                            TextToSpeech.Engine.KEY_PARAM_STREAM to AudioManager.STREAM_MUSIC.toString(),
                        ),
                    )
                }
                android.util.Log.i("PB_COMMENTARY", "Speak: $text")
            } catch (e: Exception) {
                android.util.Log.w("PB_COMMENTARY", "speak failed: ${e.message}")
                speaking = false
                abandonFocus()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { }
                    .build()
                focusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {}
    }

    private fun abandonFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }
}
