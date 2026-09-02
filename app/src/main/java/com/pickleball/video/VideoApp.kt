package vn.vdpr.video

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import vn.vdpr.video.data.*
import vn.vdpr.video.stream.CameraInfo
import vn.vdpr.video.stream.StreamManager
import vn.vdpr.video.stream.StreamQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun loadBitmapFromUrl(imageUrl: String, maxEdge: Int = vn.vdpr.video.overlay.BitmapUtils.MAX_LOGO_EDGE): android.graphics.Bitmap? {
    return vn.vdpr.video.overlay.BitmapUtils.loadUrl(imageUrl, maxEdge)
}

@Composable
fun VideoApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("video_app", android.content.Context.MODE_PRIVATE)
    val lifecycleOwner = LocalLifecycleOwner.current
    var mainResumed by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mainResumed = true
                Lifecycle.Event.ON_PAUSE -> mainResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var apiBase by remember { mutableStateOf(BuildConfig.API_BASE) }
    var step by remember { mutableIntStateOf(prefs.getInt("step", 0)) }
    var tournaments by remember { mutableStateOf<List<TournamentListItem>>(emptyList()) }
    var tournamentSearch by remember { mutableStateOf("") }
    var tournamentPage by remember { mutableIntStateOf(0) }
    var tournamentLastPage by remember { mutableIntStateOf(1) }
    var tournamentTotal by remember { mutableIntStateOf(0) }
    var tournamentsLoadingMore by remember { mutableStateOf(false) }
    var selectedTournament by remember { mutableStateOf<TournamentListItem?>(null) }
    var selectedTournaments by remember { mutableStateOf(prefs.getStringSet("tids", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()) }
    var selectedTournamentNames by remember {
        mutableStateOf(
            prefs.getString("tid_names", "")
                ?.split("\n")
                ?.mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null
                    else line.substring(0, i).toIntOrNull()?.let { id -> id to line.substring(i + 1) }
                }
                ?.toMap()
                ?: emptyMap()
        )
    }
    var streamConfig by remember { mutableStateOf<StreamConfigResponse?>(null) }
    var selectedCourt by remember { mutableStateOf(prefs.getString("court", null)) }
    var courtMatches by remember { mutableStateOf<List<CourtMatchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Persist để tránh vòng lặp: StreamActivity đóng → MainActivity recreate → autoLaunched=null → vào lại → finish...
    var autoLaunched by remember { mutableStateOf(prefs.getString("auto_launched", null)) }
    fun setAutoLaunched(key: String?) {
        autoLaunched = key
        prefs.edit().putString("auto_launched", key).apply()
    }
    val scope = rememberCoroutineScope()

    suspend fun fetchTournaments(page: Int, query: String, append: Boolean) {
        val q = query.trim().ifBlank { null }
        val res = ApiService.create(apiBase).getTournaments(
            // Có từ khóa → không gửi active (tránh chỉ lọc giải đang hiện trên list)
            active = if (q == null) 1 else null,
            page = page,
            perPage = 20,
            query = q,
        )
        tournaments = if (append) {
            (tournaments + res.data).distinctBy { it.id }
        } else {
            res.data
        }
        tournamentPage = res.current_page
        tournamentLastPage = res.last_page.coerceAtLeast(1)
        tournamentTotal = res.total
    }

    // Persist step/court/tournaments
    LaunchedEffect(step, selectedCourt, selectedTournaments, selectedTournamentNames) {
        prefs.edit()
            .putInt("step", step)
            .putString("court", selectedCourt)
            .putStringSet("tids", selectedTournaments.map { it.toString() }.toSet())
            .putString(
                "tid_names",
                selectedTournaments.mapNotNull { id ->
                    selectedTournamentNames[id]?.let { name -> "$id=$name" }
                }.joinToString("\n"),
            )
            .apply()
    }

    fun tournamentLabel(id: Int): String =
        selectedTournamentNames[id]
            ?: tournaments.find { it.id == id }?.name
            ?: "Giải #$id"

    // Camera selection
    var backCameras by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf("0") }
    var selectedQuality by remember { mutableStateOf(StreamQuality.Q_720P) }
    var supportedQualities by remember { mutableStateOf<List<StreamQuality>>(emptyList()) }
    
    LaunchedEffect(Unit) {
        backCameras = StreamManager.getBackCameras(context)
        if (backCameras.isNotEmpty()) selectedCameraId = backCameras.first().id
        supportedQualities = StreamQuality.getSupportedQualities(context)
        selectedQuality = StreamQuality.preferredDefault(supportedQualities)
    }

    // Step 3: Poll court matches, auto-launch when a match starts playing.
    // Chỉ launch khi MainActivity RESUMED — tránh start khi StreamActivity đang mở / recreate.
    LaunchedEffect(step, selectedCourt, mainResumed) {
        if (step != 3 || selectedCourt == null || !mainResumed) return@LaunchedEffect
        val court = selectedCourt ?: return@LaunchedEffect
        val tids = selectedTournaments.toList()
        if (tids.isEmpty()) return@LaunchedEffect

        // Load tournaments list if empty (resuming from saved state)
        if (tournaments.isEmpty()) {
            try { fetchTournaments(page = 1, query = "", append = false) } catch (_: Exception) {}
        }

        while (step == 3 && mainResumed) {
            try {
                // Merge court matches from all selected tournaments
                val allMatches = mutableListOf<CourtMatchItem>()
                for (tid in tids) {
                    try {
                        val tName = tournaments.find { it.id == tid }?.name ?: ""
                        val m = ApiService.create(apiBase).getCourtMatches(tid, court)
                        m.forEach {
                            it.tournamentName = tName
                            it.tournament_id = tid
                        }
                        allMatches.addAll(m)
                    } catch (_: Exception) {}
                }
                courtMatches = allMatches

                // Auto-launch: find match that is live (stream_started_at set, not ended, has rtmp)
                val playing = allMatches.firstOrNull {
                    !it.stream_started_at.isNullOrBlank() &&
                    it.stream_ended_at.isNullOrBlank() &&
                    !it.rtmp_url.isNullOrBlank()
                }
                if (playing == null) {
                    // Hết live → cho phép auto-launch trận/broadcast tiếp theo
                    if (autoLaunched != null) setAutoLaunched(null)
                } else {
                    // Track bằng "matchId:broadcastId" — tạo broadcast mới → launchKey đổi → vào lại
                    val launchKey = "${playing.id}:${playing.broadcast_id ?: playing.rtmp_url ?: "none"}"
                    if (autoLaunched != launchKey && !playing.rtmp_url.isNullOrBlank()) {
                        setAutoLaunched(launchKey)
                        val tidForStream = playing.tournament_id
                            ?: if (selectedTournaments.size == 1) selectedTournaments.first() else 0
                        val intent = Intent(context, StreamActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra("match_id", playing.id)
                            putExtra("match_type", playing.match_type)
                            putExtra("api_base", apiBase)
                            putExtra("tournament_id", tidForStream)
                            putExtra("court_name", court)
                            putExtra("camera_id", selectedCameraId)
                            putExtra("stream_quality", selectedQuality.name)
                        }
                        context.startActivity(intent)
                    }
                }
            } catch (_: Exception) {}
            delay(5000)
        }
    }

    val configuration = LocalConfiguration.current
    val cardMaxHeight = (configuration.screenHeightDp - 32).dp
    val contentScroll = rememberScrollState()

    // Đổi step → cuộn về đầu (tránh kẹt giữa danh sách dài)
    LaunchedEffect(step) {
        contentScroll.scrollTo(0)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1E293B)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = cardMaxHeight),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(contentScroll)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "VDPR",
                    modifier = Modifier.size(64.dp)
                )
                Text("VDPR Live", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                when (step) {
                    0 -> {
                        Text(
                            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                        )
                        // Camera selector
                        Text("Chọn camera", fontSize = 14.sp, color = Color.Gray)
                        if (backCameras.isEmpty()) {
                            Text("⚠️ Chưa phát hiện camera (cần cấp quyền Camera)", fontSize = 12.sp, color = Color(0xFFEAB308))
                        } else if (backCameras.size == 1) {
                            Text("📷 ${backCameras[0].label}", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Thiết bị chỉ có 1 camera sau khả dụng", fontSize = 11.sp, color = Color(0xFF94A3B8))
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                backCameras.forEach { cam ->
                                    val isSelected = selectedCameraId == cam.id
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable { selectedCameraId = cam.id },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isSelected) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surface
                                        ),
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(cam.label, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                            Spacer(modifier = Modifier.weight(1f))
                                            if (isSelected) Text("✓", color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Quality selector
                        Text("Chất lượng stream", fontSize = 14.sp, color = Color.Gray)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StreamQuality.displayOrder().forEach { q ->
                                val isSelected = selectedQuality == q
                                val isSupported = supportedQualities.contains(q)
                                val isRecommended = q == StreamQuality.Q_720P
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = isSupported) {
                                        if (isSupported) selectedQuality = q
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            !isSupported -> Color(0xFFF1F5F9)
                                            isSelected -> Color(0xFFDBEAFE)
                                            else -> MaterialTheme.colorScheme.surface
                                        }
                                    ),
                                ) {
                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                if (isRecommended) "${q.label} · Khuyến nghị" else q.label,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSupported) Color.Unspecified else Color(0xFF94A3B8),
                                            )
                                            Text(
                                                "${q.width}x${q.height} · ${q.bitrate / 1024 / 1024}Mbps",
                                                fontSize = 11.sp,
                                                color = if (isSupported) Color(0xFF64748B) else Color(0xFFCBD5E1),
                                            )
                                        }
                                        when {
                                            !isSupported -> Text("✗ Không hỗ trợ", fontSize = 11.sp, color = Color(0xFFEF4444))
                                            isSelected -> Text("✓", color = Color(0xFF2563EB), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                        Text("Tự động phát hiện phần cứng · fallback nếu cần", fontSize = 11.sp, color = Color(0xFF94A3B8))

                        Spacer(modifier = Modifier.height(8.dp))
                        if (error != null) Text(error!!, color = Color.Red, fontSize = 12.sp)
                        Button(
                            onClick = {
                                error = null
                                tournamentSearch = ""
                                tournaments = emptyList()
                                tournamentPage = 0
                                step = 1
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        ) {
                            Text("Bắt đầu →", fontWeight = FontWeight.Bold)
                        }
                    }

                    1 -> {
                        Text("Chọn giải đấu (có thể chọn nhiều)", fontSize = 14.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = tournamentSearch,
                            onValueChange = { tournamentSearch = it },
                            label = { Text("Tìm giải (tên hoặc ID)...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        // Debounce → gọi API server (toàn bộ DB, không filter local trang đang hiện)
                        LaunchedEffect(tournamentSearch) {
                            delay(450)
                            loading = true
                            error = null
                            try {
                                fetchTournaments(page = 1, query = tournamentSearch, append = false)
                            } catch (e: Exception) {
                                error = "Lỗi tìm kiếm: ${e.message}"
                                tournaments = emptyList()
                                tournamentTotal = 0
                            }
                            loading = false
                        }
                        Text(
                            if (tournamentTotal > 0) "Hiển thị ${tournaments.size}/$tournamentTotal giải"
                            else "Không tìm thấy giải nào",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                        )
                        if (tournaments.isEmpty() && !loading) {
                            Text(
                                if (tournamentSearch.isBlank()) "Không có giải đang diễn ra"
                                else "Không khớp \"$tournamentSearch\"",
                                color = Color.Gray,
                                fontSize = 13.sp,
                            )
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                tournaments.forEach { t ->
                                    val isChecked = selectedTournaments.contains(t.id)
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            if (isChecked) {
                                                selectedTournaments = selectedTournaments - t.id
                                                selectedTournamentNames = selectedTournamentNames - t.id
                                            } else {
                                                selectedTournaments = selectedTournaments + t.id
                                                selectedTournamentNames = selectedTournamentNames + (t.id to t.name)
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (isChecked) Color(0xFFDCFCE7) else MaterialTheme.colorScheme.surface
                                        ),
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("🏆 ${t.name}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                                            if (isChecked) Text("✓", color = Color(0xFF16A34A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        }
                                    }
                                }
                                if (tournamentPage < tournamentLastPage) {
                                    TextButton(
                                        onClick = {
                                            if (tournamentsLoadingMore) return@TextButton
                                            tournamentsLoadingMore = true
                                            scope.launch {
                                                try {
                                                    fetchTournaments(
                                                        page = tournamentPage + 1,
                                                        query = tournamentSearch,
                                                        append = true,
                                                    )
                                                } catch (e: Exception) {
                                                    error = "Không tải thêm được: ${e.message}"
                                                }
                                                tournamentsLoadingMore = false
                                            }
                                        },
                                        enabled = !tournamentsLoadingMore,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        if (tournamentsLoadingMore) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                        } else {
                                            Text("Tải thêm (trang ${tournamentPage + 1}/$tournamentLastPage)")
                                        }
                                    }
                                }
                            }
                        }
                        if (selectedTournaments.isNotEmpty()) {
                            Text(
                                "Đã chọn ${selectedTournaments.size} giải:\n" +
                                    selectedTournaments.sorted().joinToString("\n") { "• ${tournamentLabel(it)}" },
                                fontSize = 12.sp,
                                color = Color(0xFF16A34A),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        if (error != null) Text(error!!, color = Color.Red, fontSize = 12.sp)
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Button(
                            onClick = {
                                if (selectedTournaments.isEmpty()) return@Button
                                val firstId = selectedTournaments.first()
                                selectedTournament = tournaments.find { it.id == firstId }
                                loading = true; error = null
                                scope.launch {
                                    try {
                                        // Load stream config from ALL selected tournaments, merge courts + court_channels
                                        var mergedCourts = listOf<String>()
                                        val mergedChannels = mutableMapOf<String, Int>()
                                        var mergedStreamCfg = mapOf<String, vn.vdpr.video.data.CourtStreamConfig>()
                                        for (tid in selectedTournaments) {
                                            val cfg = ApiService.create(apiBase).getStreamConfig(tid)
                                            mergedCourts = (mergedCourts + cfg.courts).distinct()
                                            cfg.court_channels?.forEach { (court, count) ->
                                                mergedChannels[court] = (mergedChannels[court] ?: 0) + count
                                            }
                                            // Merge stream_config (later overrides earlier for same court)
                                            mergedStreamCfg = mergedStreamCfg + cfg.stream_config
                                        }
                                        streamConfig = StreamConfigResponse(
                                            courts = mergedCourts,
                                            stream_config = mergedStreamCfg,
                                            court_channels = mergedChannels,
                                        )

                                        // Overlay: chỉ lấy logo từ giải ĐÃ CHỌN; luôn xóa state/cache cũ trước
                                        // (tránh dính logo giải trước / giải không chọn)
                                        try {
                                            vn.vdpr.video.overlay.OverlayCache.clear(context)
                                            vn.vdpr.video.overlay.ScoreboardOverlay.clearAll()

                                            val mergedTopBitmaps = mutableListOf<Bitmap>()
                                            val mergedBottomBitmaps = mutableListOf<Bitmap>()
                                            val mergedMarquee = mutableListOf<String>()
                                            var mergedPause: Bitmap? = null
                                            val seenTopUrls = mutableSetOf<String>()
                                            val seenBottomUrls = mutableSetOf<String>()

                                            for (tid in selectedTournaments.sorted()) {
                                                try {
                                                    val overlay = ApiService.create(apiBase).getOverlayConfig(tid)
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        for (logo in overlay.logos.filter { it.position == "top_right" }) {
                                                            if (!seenTopUrls.add(logo.url)) continue
                                                            loadBitmapFromUrl(logo.url)?.let { mergedTopBitmaps.add(it) }
                                                        }
                                                        for (logo in overlay.logos.filter { it.position == "bottom_right" }) {
                                                            if (!seenBottomUrls.add(logo.url)) continue
                                                            loadBitmapFromUrl(logo.url)?.let { mergedBottomBitmaps.add(it) }
                                                        }
                                                        if (mergedPause == null) {
                                                            overlay.logos.firstOrNull { it.position == "pause" }?.let { pauseLogo ->
                                                                mergedPause = loadBitmapFromUrl(
                                                                    pauseLogo.url,
                                                                    vn.vdpr.video.overlay.BitmapUtils.MAX_PAUSE_EDGE
                                                                )
                                                            }
                                                        }
                                                    }
                                                    for (text in overlay.marquee_texts) {
                                                        if (text.isNotBlank() && text !in mergedMarquee) {
                                                            mergedMarquee.add(text)
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                            }

                                            // Luôn gán (kể cả rỗng) — không giữ logo cũ
                                            vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos = mergedTopBitmaps
                                            vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos = mergedBottomBitmaps
                                            vn.vdpr.video.overlay.ScoreboardOverlay.marqueeTexts = mergedMarquee
                                            vn.vdpr.video.overlay.ScoreboardOverlay.pauseImage = mergedPause
                                            if (mergedTopBitmaps.isNotEmpty()) {
                                                vn.vdpr.video.overlay.ScoreboardOverlay.pickbaseLogo = mergedTopBitmaps.first()
                                            }
                                            if (mergedBottomBitmaps.isNotEmpty()) {
                                                vn.vdpr.video.overlay.ScoreboardOverlay.tournamentLogo = mergedBottomBitmaps.first()
                                            }
                                            vn.vdpr.video.overlay.ScoreboardOverlay.loadedTournamentIds = selectedTournaments
                                            vn.vdpr.video.overlay.OverlayCache.save(context, selectedTournaments)
                                        } catch (_: Exception) {}
                                        step = 2
                                    } catch (e: Exception) { error = "Lỗi: ${e.message}" }
                                    loading = false
                                }
                            },
                            enabled = !loading && selectedTournaments.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        ) { Text("Tiếp tục →", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = {
                            step = 0; tournamentSearch = ""; selectedTournaments = emptySet()
                            selectedTournamentNames = emptyMap()
                            tournaments = emptyList()
                            tournamentPage = 0
                            tournamentLastPage = 1
                            tournamentTotal = 0
                            // Clear overlay cache khi chọn lại giải
                            vn.vdpr.video.overlay.OverlayCache.clear(context)
                            vn.vdpr.video.overlay.ScoreboardOverlay.clearAll()
                        }) { Text("← Quay lại") }
                    }

                    2 -> {
                        // Reload streamConfig nếu bị null (back từ step 3 hoặc process restore)
                        LaunchedEffect(step) {
                            if (streamConfig == null && selectedTournaments.isNotEmpty()) {
                                try {
                                    var mergedCourts = listOf<String>()
                                    val mergedChannels = mutableMapOf<String, Int>()
                                    var mergedStreamCfg = mapOf<String, vn.vdpr.video.data.CourtStreamConfig>()
                                    for (tid in selectedTournaments) {
                                        val cfg = ApiService.create(apiBase).getStreamConfig(tid)
                                        mergedCourts = (mergedCourts + cfg.courts).distinct()
                                        cfg.court_channels?.forEach { (court, count) ->
                                            mergedChannels[court] = (mergedChannels[court] ?: 0) + count
                                        }
                                        mergedStreamCfg = mergedStreamCfg + cfg.stream_config
                                    }
                                    streamConfig = StreamConfigResponse(
                                        courts = mergedCourts,
                                        stream_config = mergedStreamCfg,
                                        court_channels = mergedChannels,
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                        val selectedNames = selectedTournaments.sorted().map { tournamentLabel(it) }
                        Text("Chọn sân", fontSize = 14.sp, color = Color.Gray)
                        if (selectedNames.isNotEmpty()) {
                            Text(
                                "Giải đã chọn (${selectedNames.size}):\n" +
                                    selectedNames.joinToString("\n") { "• $it" },
                                fontSize = 12.sp,
                                color = Color(0xFF334155),
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        val courts = streamConfig?.courts ?: emptyList()
                        val channelCounts = streamConfig?.court_channels ?: emptyMap()
                        if (courts.isEmpty()) {
                            Text("Chưa có sân nào", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                courts.forEach { court ->
                                    val chCount = channelCounts[court] ?: 0
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedCourt = court
                                            setAutoLaunched(null)
                                            courtMatches = emptyList()
                                            step = 3
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("🏟 $court", fontWeight = FontWeight.Medium)
                                                Text(
                                                    if (chCount > 0) "✓ $chCount kênh YouTube" else "⚠️ Chưa kết nối YouTube",
                                                    fontSize = 11.sp,
                                                    color = if (chCount > 0) Color(0xFF16A34A) else Color(0xFFEAB308),
                                                )
                                            }
                                            Text("▶", fontSize = 20.sp, color = if (chCount > 0) Color(0xFF16A34A) else Color(0xFF94A3B8))
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { step = 1 }) { Text("← Quay lại") }
                    }

                    3 -> {
                        Text("🏟 Sân $selectedCourt", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        if (selectedTournaments.isNotEmpty()) {
                            Text(
                                selectedTournaments.sorted().joinToString("\n") { "• ${tournamentLabel(it)}" },
                                fontSize = 12.sp,
                                color = Color(0xFF64748B),
                            )
                        }

                        if (courtMatches.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color(0xFF16A34A), strokeWidth = 3.dp)
                            Text("Đang tải danh sách trận...", fontSize = 12.sp, color = Color.Gray)
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                courtMatches.forEach { m ->
                                    val statusLabel = when {
                                        !m.stream_started_at.isNullOrBlank() && m.stream_ended_at.isNullOrBlank() -> "🔴 LIVE"
                                        !m.stream_ended_at.isNullOrBlank() -> "✅ Kết thúc"
                                        !m.rtmp_url.isNullOrBlank() -> "⏳ Chờ live"
                                        !m.youtube_video_id.isNullOrBlank() -> "📹 Broadcast sẵn"
                                        else -> "○ Chờ"
                                    }
                                    val statusColor = when {
                                        !m.stream_started_at.isNullOrBlank() && m.stream_ended_at.isNullOrBlank() -> Color(0xFFDC2626)
                                        !m.stream_ended_at.isNullOrBlank() -> Color(0xFF16A34A)
                                        !m.rtmp_url.isNullOrBlank() -> Color(0xFFEAB308)
                                        else -> Color(0xFF94A3B8)
                                    }
                                    Card(shape = RoundedCornerShape(8.dp)) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("T${m.match_order ?: "?"} · ${m.team1} vs ${m.team2}", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                                if (!m.tournamentName.isNullOrBlank()) {
                                                    Text(m.tournamentName!!, fontSize = 10.sp, color = Color(0xFF64748B))
                                                }
                                                Text(statusLabel, fontSize = 11.sp, color = statusColor)
                                            }
                                        }
                                    }
                                }
                            }

                            // Auto-live hint + nút vào lại nếu đã suppress
                            Spacer(modifier = Modifier.height(4.dp))
                            val liveMatch = courtMatches.firstOrNull {
                                !it.stream_started_at.isNullOrBlank() &&
                                    it.stream_ended_at.isNullOrBlank() &&
                                    !it.rtmp_url.isNullOrBlank()
                            }
                            val liveKey = liveMatch?.let { "${it.id}:${it.broadcast_id ?: it.rtmp_url ?: "none"}" }
                            if (liveMatch != null && liveKey != null && autoLaunched == liveKey) {
                                Button(
                                    onClick = {
                                        val tidForStream = liveMatch.tournament_id
                                            ?: if (selectedTournaments.size == 1) selectedTournaments.first() else 0
                                        setAutoLaunched(liveKey)
                                        context.startActivity(
                                            Intent(context, StreamActivity::class.java).apply {
                                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                putExtra("match_id", liveMatch.id)
                                                putExtra("match_type", liveMatch.match_type)
                                                putExtra("api_base", apiBase)
                                                putExtra("tournament_id", tidForStream)
                                                putExtra("court_name", selectedCourt)
                                                putExtra("camera_id", selectedCameraId)
                                                putExtra("stream_quality", selectedQuality.name)
                                            }
                                        )
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("🔴 Vào live lại", color = Color.White)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF16A34A), strokeWidth = 2.dp)
                                    Text("Tự động live khi trọng tài bắt đầu trận", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            // Reset court selection but keep streamConfig
                            selectedCourt = null
                            courtMatches = emptyList()
                            setAutoLaunched(null)
                            step = 2
                        }) { Text("← Đổi sân") }
                    }
                }
            }
        }
    }
}
