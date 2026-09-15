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
    /** Câu mới nhất chờ nói xong câu hiện tại — bỏ qua các câu trung gian. */
    private var pendingSpeak: String? = null
    private var idleStreak = 0
    /** Đã kết thúc trận — chỉ cho câu kết, không idle/điểm thêm. */
    @Volatile private var matchEnded = false
    /** Giữ luật điểm từ Firebase khi một số update thiếu field. */
    private var stickyWinScore = 0
    private var stickyMaxScore = 0
    private var basePitch = 1.0f
    private var baseRate = 1.08f
    /** Máy chỉ có pack TTS mặc định (A70 hay gặp) — dùng profile pitch/rate riêng. */
    private var limitedVoiceProfile = false
    private val recentPhrases = ArrayDeque<String>()
    private val pointStreak = mutableMapOf<String, Int>()
    private var pendingMatch: MatchState? = null
    @Volatile private var currentMatch: MatchState? = null
    private var lastScoreChangeAt = 0L
    private var tensionRunnable: Runnable? = null
    private val pendingFlushRunnable = Runnable { flushPendingSpeak() }
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
        pendingSpeak = null
        mainHandler.removeCallbacks(pendingFlushRunnable)
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
        applyVoiceProsody(engine)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking = true }
            override fun onDone(utteranceId: String?) {
                speaking = false
                abandonFocus()
                mainHandler.post { flushPendingSpeak() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speaking = false
                abandonFocus()
                mainHandler.post { flushPendingSpeak() }
            }
        })
        android.util.Log.i(
            "PB_COMMENTARY",
            "TTS ready=$ready voice=${engine.voice?.name} pitch=$basePitch rate=$baseRate limited=$limitedVoiceProfile",
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
                // Neural / HQ — ít máy hơn language pack cũ
                if (n.contains("wavenet") || n.contains("neural") || n.contains("studio") ||
                    n.contains("journey") || n.contains("chirp") || n.contains("news")
                ) s += 55
                if (n.contains("dmm") || n.contains("wavenet-b") || n.contains("wavenet-d") ||
                    n.contains("standard-b") || n.contains("standard-d")
                ) s += 40
                // Nữ — phạt nặng
                if (n.contains("x-vie") || n.contains("x-vif") || n.contains("-vie-") || n.contains("-vif-")) s -= 120
                if (n.contains("female") || n.contains("fem") || n.contains("nữ") || n.contains("nu ")) s -= 120
                // Generic language pack thường là nữ + máy
                if (n == "vi-vn-language" || n.endsWith("-language")) s -= 100
                if (v.quality >= Voice.QUALITY_HIGH) s += 12
                if (v.quality >= Voice.QUALITY_VERY_HIGH) s += 18
                // Offline ổn định; neural mạng vẫn được nếu điểm cao hơn rõ
                if (!v.isNetworkConnectionRequired) s += 4
                return s
            }

            // Ưu tiên giọng điểm cao nhất (neural mạng > pack máy local nếu chênh lệch lớn)
            val bestLocal = viAll.filter { !it.isNetworkConnectionRequired }.maxByOrNull { score(it) }
            val bestAny = viAll.maxByOrNull { score(it) } ?: return
            val finalVoice = when {
                bestLocal != null && score(bestLocal) >= score(bestAny) - 25 -> bestLocal
                else -> bestAny
            }

            engine.voice = finalVoice
            android.util.Log.i(
                "PB_COMMENTARY",
                "Selected voice=${finalVoice.name} score=${score(finalVoice)} " +
                    "net=${finalVoice.isNetworkConnectionRequired} q=${finalVoice.quality}",
            )
        } catch (e: Exception) {
            android.util.Log.w("PB_COMMENTARY", "preferWarmMaleVoice: ${e.message}")
        }
    }

    /**
     * Tự chỉnh pitch/rate theo giọng máy có sẵn — không cần user tải thêm voice.
     * Pack mặc định Samsung/Google (thường nữ, máy) → hạ pitch + chậm = ấm, dễ nghe trên sân.
     */
    private fun applyVoiceProsody(engine: TextToSpeech) {
        val vName = engine.voice?.name?.lowercase().orEmpty()
        val male = vName.contains("x-vid") || vName.contains("-vid-") || vName.contains("_vid_") ||
            vName.contains("male") || Regex("""(^|[^a-z])nam([^a-z]|$)""").containsMatchIn(vName)
        val femaleMarked = vName.contains("female") || vName.contains("fem") ||
            vName.contains("x-vie") || vName.contains("x-vif") ||
            vName == "vi-vn-language" || vName.endsWith("-language")
        val neural = vName.contains("wavenet") || vName.contains("neural") ||
            vName.contains("studio") || vName.contains("journey") || vName.contains("chirp")

        limitedVoiceProfile = !male && !neural && (femaleMarked || vName.isEmpty() ||
            (engine.voices?.count { it.locale.language.equals("vi", true) } ?: 0) <= 2)

        when {
            male && neural -> {
                basePitch = 0.98f
                baseRate = 1.10f
            }
            male -> {
                basePitch = 0.96f
                baseRate = 1.08f
            }
            limitedVoiceProfile -> {
                // Pack mặc định: nói nhanh hơn như BLV, vẫn giữ pitch ấm
                basePitch = 0.72f
                baseRate = 1.12f
            }
            femaleMarked -> {
                basePitch = 0.80f
                baseRate = 1.08f
            }
            else -> {
                basePitch = 0.94f
                baseRate = 1.08f
            }
        }
        try {
            engine.setPitch(basePitch)
            engine.setSpeechRate(baseRate)
        } catch (_: Exception) {}
        android.util.Log.i(
            "PB_COMMENTARY",
            "Prosody limited=$limitedVoiceProfile male=$male neural=$neural → pitch=$basePitch rate=$baseRate",
        )
    }

    fun onMatchUpdate(match: MatchState?) {
        if (!enabled || tts == null) {
            if (match == null) resetTracking()
            return
        }
        if (match == null) {
            val ending = currentMatch
            if (ending != null && !matchEnded && (ending.scoreLeft > 0 || ending.scoreRight > 0 || lastMatchKey != null)) {
                markMatchEnded()
                announceMatchEnd(ending)
            } else {
                markMatchEnded()
            }
            pendingMatch = null
            currentMatch = null
            stopTensionLoop()
            resetTracking()
            return
        }
        if (!ready) {
            pendingMatch = match
            return
        }
        rememberRules(match)
        currentMatch = match

        // Trọng tài đã ấn kết thúc (status=resolved) → đọc câu kết ngay
        if (match.status == "resolved") {
            // Trận MỚI (khác trận đã kết thúc trước) → reset cờ để không bị chặn bởi matchEnded cũ
            val endKey = matchKey(match)
            if (endKey != lastMatchKey) {
                lastMatchKey = endKey
                matchEnded = false
            }
            if (!matchEnded) {
                android.util.Log.i("PB_COMMENTARY", "status=resolved → announce match end (${match.scoreLeft}-${match.scoreRight})")
                announceMatchEnd(match)
            } else {
                android.util.Log.d("PB_COMMENTARY", "status=resolved but already ended — skip")
            }
            return
        }

        // Đã kết thúc — bỏ qua mọi điểm/idle; chỉ giữ im lặng
        if (matchEnded) {
            android.util.Log.d("PB_COMMENTARY", "matchEnded — ignore score chatter ${match.scoreLeft}-${match.scoreRight}")
            return
        }

        val key = matchKey(match)
        val now = System.currentTimeMillis()
        val gap = when (density) {
            Density.LOW -> 6500L
            Density.MEDIUM -> 2800L
            Density.HIGH -> 1400L
        }

        if (key != lastMatchKey) {
            lastMatchKey = key
            matchEnded = false
            lastLeft = match.scoreLeft
            lastRight = match.scoreRight
            lastPaused = match.paused
            lastServe = match.serve
            lastServerNum = match.serverNum
            lastServerHand = match.serverHand
            lastCourtSwapped = match.courtSwapped
            lastScoreChangeAt = now
            idleStreak = 0
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
            // CHỈ đọc TIMEOUT khi đúng là tạm dừng (reason=timeout).
            // switch (đổi sân) / end (kết thúc) → không đọc.
            val reason = match.pauseReason.trim().lowercase()
            if (reason == "timeout" || reason.isEmpty()) {
                speak(fillTeams(CommentaryPhrases.TIMEOUT, match), minGapMs = 1200)
            }
            return
        }
        if (lastPaused == true && !match.paused) {
            lastPaused = false
            lastScoreChangeAt = now
            idleStreak = 0
            if (matchEnded) {
                matchEnded = false
                android.util.Log.i("PB_COMMENTARY", "Resume — reopen commentary")
            }
            // Đọc RESUME khi tiếp tục sau timeout
            if (!matchEnded) {
                speak(fillTeams(CommentaryPhrases.RESUME, match), minGapMs = 1200)
            }
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
                val phrase = fillPoint(CommentaryPhrases.SIDE_OUT, match, receiver, lost, MentionBias.POINT, preferServer = true)
                syncServe(match)
                lastScoreChangeAt = now
                idleStreak = 0
                speak(phrase, minGapMs = gap / 2)
                scheduleTensionLoop()
            } else if (handChanged) {
                val serving = if (match.serve == "left") "left" else "right"
                val phrase = fillPoint(CommentaryPhrases.HAND_CHANGE, match, serving, opposite(serving), MentionBias.POINT, preferServer = true)
                    .replace("{hand}", handLabel(match))
                syncServe(match)
                idleStreak = 0
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
        idleStreak = 0
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

        // KHÔNG tự dự đoán thắng/game point — chỉ kết thúc khi trọng tài ấn (Firebase node xóa → onMatchUpdate(null))
        val streakDramatic = (pointStreak[scorer] ?: 0) >= 3
        // force=false: không cắt câu đang nói; pending giữ câu mới nhất
        speak(
            phrase,
            force = streakDramatic,
            minGapMs = when {
                now - lastSpeakAt < gap && !isDramatic(match, scorer, serveChanged) -> gap
                density == Density.LOW && !isDramatic(match, scorer, serveChanged) -> gap
                else -> gap / 3
            },
        )
        scheduleTensionLoop()
    }

    /** Khi trọng tài lâu không ấn — mật độ giảm dần nếu tỉ số đứng yên lâu. */
    private fun scheduleTensionLoop() {
        stopTensionLoop()
        if (!enabled || !ready || matchEnded) return
        val base = when (density) {
            Density.LOW -> 22000L
            Density.MEDIUM -> 16000L
            Density.HIGH -> 11000L
        }
        val r = object : Runnable {
            override fun run() {
                if (!enabled || !ready || matchEnded) return
                val m = currentMatch ?: return
                if (m.paused) {
                    mainHandler.postDelayed(this, base)
                    return
                }
                val idleMs = System.currentTimeMillis() - lastScoreChangeAt
                // Backoff: càng đứng điểm càng thưa
                val interval = when {
                    idleMs >= 180_000L -> base * 4
                    idleMs >= 90_000L -> base * 3
                    idleMs >= 45_000L -> base * 2
                    else -> base
                }
                if (idleMs >= interval && !speaking && pendingSpeak == null) {
                    // Sau ~3 phút đứng yên: gần như im, chỉ thỉnh thoảng
                    if (idleMs >= 180_000L && idleStreak >= 2) {
                        android.util.Log.i("PB_COMMENTARY", "Idle silence (score stuck ${idleMs}ms)")
                    } else {
                        // Không dự đoán "sắp kết" theo win_score — chỉ câu lấp khoảng lặng bình thường
                        speak(fillTeams(CommentaryPhrases.idleLines(), m), minGapMs = interval / 2)
                        idleStreak++
                    }
                }
                mainHandler.postDelayed(this, interval)
            }
        }
        tensionRunnable = r
        mainHandler.postDelayed(r, base)
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

    private fun rememberRules(m: MatchState) {
        if (m.winScore > 0 && m.winScore != stickyWinScore) {
            stickyWinScore = m.winScore
            android.util.Log.i("PB_COMMENTARY", "Rules win_score=$stickyWinScore")
        }
        if (m.maxScore > 0 && m.maxScore != stickyMaxScore) {
            stickyMaxScore = m.maxScore
            android.util.Log.i("PB_COMMENTARY", "Rules max_score=$stickyMaxScore")
        }
    }

    private fun markMatchEnded() {
        if (!matchEnded) {
            android.util.Log.i("PB_COMMENTARY", "matchEnded=true — stop chatter")
        }
        matchEnded = true
        stopTensionLoop()
        // Xóa pending idle/điểm thường — giữ lại nếu đang là câu thắng (pending sẽ nói sau)
    }

    /** Điểm thắng theo luật giải; nhớ sticky từ Firebase; thiếu thì mặc định 11. */
    private fun winTarget(m: MatchState): Int =
        m.winScore.takeIf { it > 0 } ?: stickyWinScore.takeIf { it > 0 } ?: 11

    private fun maxCap(m: MatchState): Int =
        m.maxScore.takeIf { it > 0 } ?: stickyMaxScore.coerceAtLeast(0)

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
        markMatchEnded()
        // Câu kết là quan trọng nhất — CẮT NGANG câu đang nói để đọc ngay,
        // không defer (node resolved chỉ tồn tại ~12s, defer dễ mất câu kết).
        // endPhrase=true → luôn nói dù không khớp isEndPhrase (tránh bị guard chống-chatter chặn).
        pendingSpeak = null
        speak(buildMatchEndText(m), force = true, minGapMs = 0, interrupt = true, endPhrase = true)
    }

    private fun buildMatchEndText(m: MatchState): String {
        // Tại thời điểm kết thúc, tỉ số đã là kết quả cuối → bên cao điểm hơn là bên thắng.
        // Không dùng isWinScore (win_score không tin cậy) — chỉ so sánh trực tiếp.
        val left = m.scoreLeft
        val right = m.scoreRight
        // finalScore=true → chỉ đọc tỉ số chính x-y, bỏ số người giao (z)
        return when {
            left > right -> fillPoint(CommentaryPhrases.MATCH_WON, m, "left", "right", MentionBias.BIG, finalScore = true)
            right > left -> fillPoint(CommentaryPhrases.MATCH_WON, m, "right", "left", MentionBias.BIG, finalScore = true)
            else -> fillTeams(CommentaryPhrases.MATCH_END, m, varyNames = false, finalScore = true)
        }
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

        // KHÔNG dùng isWinScore/isGamePoint (win_score không tin cậy) — chỉ bình luận điểm bình thường.
        // Câu kết chỉ đọc khi trọng tài ấn kết thúc (Firebase node xóa → onMatchUpdate null).
        val bias = when {
            streak >= 3 -> MentionBias.STREAK
            else -> MentionBias.POINT
        }
        // Giữ giao / vừa ghi điểm khi đang giao → ưu tiên đúng người giao
        val preferServer = scorer == servingSide || sideOut || handChanged
        val (name, other) = resolvePointNames(m, scorer, otherSide, bias, preferServer)

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
        // KHÔNG dùng câu game point / clutch / extended / match point theo win_score
        // (win_score không tin cậy → tránh bình luận "sắp thắng" sai ở điểm 11)
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

    /**
     * Người đang giao — khớp logic referee: serverIsHand1=true → hand1 (players[0]), ngược lại hand2.
     * players = [hand1, hand2] (từ playersOf).
     */
    private fun servingPlayer(m: MatchState, side: String, players: List<String>): String {
        if (players.isEmpty()) return "đội này"
        if (players.size == 1) return players.first()
        // Chỉ biết chắc người giao khi đội này đang giao
        if (m.serve != side) return players.first()
        val idx = if (m.serverIsHand1) 0 else 1
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

    private fun fillTeams(
        templates: List<String>,
        m: MatchState,
        varyNames: Boolean = true,
        finalScore: Boolean = false,
    ): String {
        val left = if (varyNames) mentionSide(m, "left", m.left) else fullName(m.left)
        val right = if (varyNames) mentionSide(m, "right", m.right) else fullName(m.right)
        val target = vnNumber(winTarget(m))
        val max = maxCap(m).takeIf { it > 0 }?.let { vnNumber(it) } ?: ""
        val filled = templates.map {
            it.replace("{left}", left)
                .replace("{right}", right)
                .replace("{tour}", m.tournamentName?.trim()?.takeIf { t -> t.isNotEmpty() } ?: "giải đấu")
                .replace("{round}", m.roundName?.trim()?.takeIf { t -> t.isNotEmpty() } ?: "trận đấu")
                .replace("{score}", scoreCall(m, finalScore))
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
        finalScore: Boolean = false,
    ): String {
        val (name, other) = resolvePointNames(m, scorer, otherSide, bias, preferServer)
        return pickFrom(applyPoint(templates, name, other, scoreCall(m, finalScore), m))
    }

    /**
     * Chuỗi tỉ số đọc TTS.
     * finalScore=true (khi trận kết thúc) → chỉ đọc tỉ số chính x-y, KHÔNG đọc số người giao (z).
     */
    private fun scoreCall(m: MatchState, finalScore: Boolean = false): String {
        if (finalScore) {
            // Kết thúc: đọc theo bên thắng trước (điểm cao hơn), chỉ tỉ số chính, không có z
            val hi = maxOf(m.scoreLeft, m.scoreRight)
            val lo = minOf(m.scoreLeft, m.scoreRight)
            return "${vnNumber(hi)} ${vnNumber(lo)}"
        }
        val s = if (m.serve == "left") m.scoreLeft else m.scoreRight
        val r = if (m.serve == "left") m.scoreRight else m.scoreLeft
        // Rally scoring hoặc đơn → không đọc số người giao (z)
        return if (m.matchFormat == "singles" || m.scoringType == "rally") {
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
        matchEnded = false
        idleStreak = 0
        pendingSpeak = null
        mainHandler.removeCallbacks(pendingFlushRunnable)
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

    /**
     * Nói TTS: không cắt câu đang nói.
     * Nếu đang nói / trong gap → ghi đè pending bằng câu mới nhất; nói xong mới phát.
     * force chỉ bỏ qua minGap (ưu tiên win/game point), vẫn không QUEUE_FLUSH giữa câu.
     */
    private fun speak(text: String, force: Boolean = false, minGapMs: Long = 2500, interrupt: Boolean = false, endPhrase: Boolean = false) {
        if (!enabled || !ready || text.isBlank()) return
        // endPhrase = câu kết chính thức → luôn nói, bỏ qua guard chống-chatter
        if (!endPhrase && matchEnded && !isEndPhrase(text)) {
            android.util.Log.d("PB_COMMENTARY", "Drop post-end chatter: ${text.take(48)}")
            return
        }
        val now = System.currentTimeMillis()
        // interrupt = câu kết → cắt ngang câu đang nói, phát ngay (QUEUE_FLUSH trong startUtterance)
        if (interrupt) {
            pendingSpeak = null
            mainHandler.removeCallbacks(pendingFlushRunnable)
            startUtterance(text)
            return
        }
        if (speaking) {
            pendingSpeak = text
            android.util.Log.d("PB_COMMENTARY", "Queue latest (speaking): ${text.take(60)}")
            return
        }
        if (!force && now - lastSpeakAt < minGapMs) {
            pendingSpeak = text
            val wait = (minGapMs - (now - lastSpeakAt)).coerceAtLeast(80L)
            mainHandler.removeCallbacks(pendingFlushRunnable)
            mainHandler.postDelayed(pendingFlushRunnable, wait)
            return
        }
        startUtterance(text)
    }

    private fun isEndPhrase(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("thắng") || t.contains("kết thúc") || t.contains("khép") ||
            t.contains("chiến thắng") || t.contains("hết trận") || t.contains("chúc mừng") ||
            t.contains("hết giờ") || t.contains("đóng màn") || t.contains("xong rồi")
    }

    private fun flushPendingSpeak() {
        if (speaking) return
        val next = pendingSpeak ?: return
        pendingSpeak = null
        if (!enabled || !ready) return
        if (matchEnded && !isEndPhrase(next)) return
        startUtterance(next)
    }

    private fun startUtterance(text: String) {
        val spoken = humanizeForTts(text)
        if (spoken.isBlank()) return
        lastSpeakAt = System.currentTimeMillis()
        requestFocus()
        val engine = tts ?: return
        // Pack mặc định: dao động rất nhẹ (dao động mạnh càng nghe máy)
        val (pitch, rate) = if (limitedVoiceProfile) {
            val p = (basePitch + Random.nextFloat() * 0.03f - 0.01f).coerceIn(0.68f, 0.78f)
            val r = (baseRate + Random.nextFloat() * 0.06f - 0.02f).coerceIn(1.05f, 1.20f)
            p to r
        } else {
            val p = (basePitch + Random.nextFloat() * 0.05f - 0.02f).coerceIn(0.88f, 1.05f)
            val r = (baseRate + Random.nextFloat() * 0.06f - 0.02f).coerceIn(1.02f, 1.18f)
            p to r
        }
        try {
            engine.setPitch(pitch)
            engine.setSpeechRate(rate)
        } catch (_: Exception) {}
        val id = UUID.randomUUID().toString()
        val run = Runnable {
            try {
                try {
                    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (max > 0) {
                        val target = (max * 0.85f).toInt().coerceAtLeast(1)
                        if (cur < target) {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                        }
                    }
                } catch (_: Exception) {}
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val params = Bundle()
                    params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    engine.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, id)
                } else {
                    @Suppress("DEPRECATION")
                    engine.speak(
                        spoken,
                        TextToSpeech.QUEUE_FLUSH,
                        hashMapOf(
                            TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to id,
                            TextToSpeech.Engine.KEY_PARAM_STREAM to AudioManager.STREAM_MUSIC.toString(),
                            TextToSpeech.Engine.KEY_PARAM_VOLUME to "1.0",
                        ),
                    )
                }
                android.util.Log.i("PB_COMMENTARY", "Speak(p=$pitch r=$rate): $spoken")
            } catch (e: Exception) {
                android.util.Log.w("PB_COMMENTARY", "speak failed: ${e.message}")
                speaking = false
                abandonFocus()
                flushPendingSpeak()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) run.run() else mainHandler.post(run)
    }

    /**
     * Làm mềm câu trước TTS: bỏ mở đầu máy móc, thêm nhịp nghỉ tự nhiên.
     */
    private fun humanizeForTts(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        // Bỏ các opener kiểu script expand dễ nghe robot
        val stiffOpeners = listOf(
            "Thật vậy, ", "Nhìn xem, ", "Đúng lúc này, ", "Trên sân lúc này, ", "Xin nhắc lại, ",
            "Một diễn biến mới, ", "Cập nhật nhanh, ", "Theo dõi tiếp, ", "Không khí đang đổi, ",
            "Vừa rồi, ", "Lúc này quý vị, ", "Đáng chú ý, ", "Xin hãy lắng nghe, ", "Phút giây này, ",
            "Diễn biến nóng, ", "Trực tiếp từ sân, ", "Bình luận viên xin phép, ", "Góc nhìn nhanh, ",
            "Nhìn này, ", "Xin phép, ", "Đúng rồi, ", "Thật đáng xem, ", "Giữ mắt trên sân, ",
            "Không khí vừa nhảy một nấc, ", "Loa bình luận xin phép nóng, ", "Một nhịp nữa, ",
            "Cảm xúc đang cao, ", "Đúng chất giải đấu, ", "Vâng, ", "Ôi giời, ", "Xin giữ chỗ ngồi, ",
            "Một khoảnh khắc đáng tiền, ", "Trời đất ơi, ", "Nghe này quý vị, ",
            "Sân đang rất đáng theo dõi, ", "Bản lĩnh lên tiếng, ", "Nhanh thôi, ",
            "Úi giời, ",
        )
        for (op in stiffOpeners) {
            if (t.startsWith(op, ignoreCase = true)) {
                t = t.substring(op.length).trimStart()
                if (t.isNotEmpty()) t = t.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale("vi", "VN")) else c.toString() }
                break
            }
        }
        // Rút đuôi máy lặp lại
        val stiffTails = listOf(
            " Xin giữ tinh thần cổ vũ đến cùng.",
            " Đừng chỉnh kênh nhé quý vị.",
            " Cảm xúc sân đang rất đáng theo dõi.",
            " Bản lĩnh sẽ lên tiếng ngay thôi.",
            " Hãy cùng thở chung một nhịp với sân.",
            " Đây mới là chất lượng trận đấu.",
            " Khán đài đang nóng dần từng giây.",
            " Loa bình luận cũng muốn nói to hơn.",
            " Một pha nữa có thể đổi cả cục diện.",
            " Xin vỗ tay tiếp sức cho cả hai phía.",
            " Trận đấu vẫn còn rất nhiều chuyện để kể.",
            " Đừng bỏ lỡ giây nào lúc này.",
            " Áp lực và niềm vui đang trộn đều trên sân.",
            " Tinh thần thể thao đang lên tiếng đẹp mắt.",
            " Quá đáng xem quý vị ạ.",
            " Hãy ở lại đến phút cuối nhé.",
            " Tim khán đài cũng đập theo từng nhịp.",
            " Câu chuyện trên sân vẫn chưa dừng lại.",
            " Phong độ lúc này rất đáng ghi nhận.",
            " Xin cảm ơn không khí tuyệt đẹp trên sân.",
            " Hãy cùng dõi theo tiếp.",
            " Không khí vẫn rất đáng xem.",
            " Xin giữ tinh thần cổ vũ văn minh.",
            " Bản lĩnh sẽ quyết định đoạn tới.",
            " Cảm xúc sân còn rất cao.",
            " Đừng bỏ lỡ những pha tiếp theo.",
            " Trận đấu vẫn còn nhiều bất ngờ.",
            " Loa bình luận cũng đang nóng theo.",
            " Khán đài đang rất tập trung.",
            " Một nhịp nữa sẽ rõ hơn.",
        )
        for (tail in stiffTails) {
            if (t.endsWith(tail)) {
                t = t.removeSuffix(tail).trimEnd()
                if (t.isNotEmpty() && t.last() !in ".!?…") t += "."
                break
            }
        }
        // Nghỉ hơi tự nhiên quanh dấu câu / tỉ số
        t = t
            .replace(Regex("""\s{2,}"""), " ")
            .replace("..", ".")
            .replace(" ,", ",")
            .replace(Regex("""([^\s]),([^\s])"""), "$1, $2")
        // Chèn nghỉ nhẹ trước cụm điểm kiểu "mười một mười" nếu câu dài
        if (t.length > 90 && t.count { it == ',' } < 2) {
            val mid = t.indexOf(". ")
            if (mid in 20 until t.length - 20) {
                // đã có ngắt câu
            } else {
                val cut = t.indexOf(", ", t.length / 3)
                if (cut < 0) {
                    val sp = t.indexOf(' ', t.length / 2)
                    if (sp > 0) t = t.substring(0, sp) + "," + t.substring(sp)
                }
            }
        }
        // Pack mặc định: câu ngắn hơn nghe tự nhiên hơn (TTS yếu hay “đọc sách”)
        if (limitedVoiceProfile && t.length > 110) {
            t = shortenForLimitedTts(t, 110)
        }
        return t.trim()
    }

    private fun shortenForLimitedTts(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val cutDots = text.lastIndexOf(". ", maxLen)
        if (cutDots >= 40) return text.substring(0, cutDots + 1).trim()
        val cutComma = text.lastIndexOf(", ", maxLen)
        if (cutComma >= 40) return text.substring(0, cutComma).trim() + "."
        val sp = text.lastIndexOf(' ', maxLen)
        if (sp >= 40) return text.substring(0, sp).trim() + "."
        return text.substring(0, maxLen).trim() + "."
    }

    private fun requestFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
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
