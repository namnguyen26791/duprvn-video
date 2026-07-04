package vn.vdpr.video

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import vn.vdpr.video.data.*
import vn.vdpr.video.stream.CameraInfo
import vn.vdpr.video.stream.StreamManager
import vn.vdpr.video.stream.StreamQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun loadBitmapFromUrl(imageUrl: String): android.graphics.Bitmap? {
    return try {
        val url = java.net.URL(imageUrl)
        val connection = url.openConnection()
        if (connection is javax.net.ssl.HttpsURLConnection) {
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(null, trustAll, java.security.SecureRandom())
            connection.sslSocketFactory = sslCtx.socketFactory
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        val input = connection.getInputStream()
        val bmp = android.graphics.BitmapFactory.decodeStream(input)
        input.close()
        bmp
    } catch (_: Exception) { null }
}

@Composable
fun VideoApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("video_app", android.content.Context.MODE_PRIVATE)

    var apiBase by remember { mutableStateOf(BuildConfig.API_BASE) }
    var step by remember { mutableIntStateOf(prefs.getInt("step", 0)) }
    var tournaments by remember { mutableStateOf<List<TournamentListItem>>(emptyList()) }
    var tournamentSearch by remember { mutableStateOf("") }
    var selectedTournament by remember { mutableStateOf<TournamentListItem?>(null) }
    var selectedTournaments by remember { mutableStateOf(prefs.getStringSet("tids", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()) }
    var streamConfig by remember { mutableStateOf<StreamConfigResponse?>(null) }
    var selectedCourt by remember { mutableStateOf(prefs.getString("court", null)) }
    var courtMatches by remember { mutableStateOf<List<CourtMatchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoLaunched by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Persist step/court/tournaments
    LaunchedEffect(step, selectedCourt, selectedTournaments) {
        prefs.edit()
            .putInt("step", step)
            .putString("court", selectedCourt)
            .putStringSet("tids", selectedTournaments.map { it.toString() }.toSet())
            .apply()
    }

    // Camera selection
    var backCameras by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf("0") }
    var selectedQuality by remember { mutableStateOf(StreamQuality.Q_1080P) }
    var supportedQualities by remember { mutableStateOf<List<StreamQuality>>(StreamQuality.all()) }
    
    LaunchedEffect(Unit) {
        backCameras = StreamManager.getBackCameras(context)
        if (backCameras.isNotEmpty()) selectedCameraId = backCameras.first().id
        supportedQualities = StreamQuality.getSupportedQualities()
        // Default chọn chất lượng cao nhất mà máy hỗ trợ
        if (supportedQualities.isNotEmpty()) selectedQuality = supportedQualities.first()
    }

    // Step 3: Poll court matches, auto-launch when a match starts playing
    LaunchedEffect(step, selectedCourt) {
        if (step != 3 || selectedCourt == null) return@LaunchedEffect
        val court = selectedCourt ?: return@LaunchedEffect
        val tids = selectedTournaments.toList()
        if (tids.isEmpty()) return@LaunchedEffect

        // Load tournaments list if empty (resuming from saved state)
        if (tournaments.isEmpty()) {
            try { tournaments = ApiService.create(apiBase).getTournaments().data } catch (_: Exception) {}
        }

        while (step == 3) {
            try {
                // Merge court matches from all selected tournaments
                val allMatches = mutableListOf<CourtMatchItem>()
                for (tid in tids) {
                    try {
                        val tName = tournaments.find { it.id == tid }?.name ?: ""
                        val m = ApiService.create(apiBase).getCourtMatches(tid, court)
                        m.forEach { it.tournamentName = tName }
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
                if (playing != null) {
                    // Track bằng "matchId:broadcastId" — khi admin tạo broadcast mới, launchKey thay đổi → auto-launch lại
                    val launchKey = "${playing.id}:${playing.broadcast_id ?: playing.rtmp_url ?: "none"}"
                    if (autoLaunched != launchKey && !playing.rtmp_url.isNullOrBlank()) {
                        autoLaunched = launchKey
                        val tidForStream = if (selectedTournaments.size == 1) selectedTournaments.first() else 0
                        val intent = Intent(context, StreamActivity::class.java).apply {
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

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1E293B)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 500.dp).padding(24.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
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
                            StreamQuality.all().forEach { q ->
                                val isSelected = selectedQuality == q
                                val isSupported = supportedQualities.contains(q)
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
                                                q.label,
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
                                error = null; loading = true
                                scope.launch {
                                    try {
                                        tournaments = ApiService.create(apiBase).getTournaments().data
                                        step = 1
                                    } catch (e: Exception) { error = "Không kết nối được: ${e.message}" }
                                    loading = false
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        ) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Bắt đầu →", fontWeight = FontWeight.Bold)
                        }
                    }

                    1 -> {
                        Text("Chọn giải đấu (có thể chọn nhiều)", fontSize = 14.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = tournamentSearch,
                            onValueChange = { tournamentSearch = it },
                            label = { Text("Tìm giải...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        val filtered = tournaments.filter {
                            tournamentSearch.isBlank() || it.name.contains(tournamentSearch, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Text("Không tìm thấy giải nào", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(filtered) { t ->
                                    val isChecked = selectedTournaments.contains(t.id)
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedTournaments = if (isChecked) selectedTournaments - t.id else selectedTournaments + t.id
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
                            }
                        }
                        if (selectedTournaments.isNotEmpty()) {
                            Text("Đã chọn ${selectedTournaments.size} giải", fontSize = 12.sp, color = Color(0xFF16A34A), fontWeight = FontWeight.Medium)
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

                                        // Load overlay: merge từ tất cả giải (chưa có thì thêm, có rồi thì thôi)
                                        try {
                                            val mergedTopBitmaps = mutableListOf<Bitmap>()
                                            val mergedBottomBitmaps = mutableListOf<Bitmap>()
                                            val mergedMarquee = mutableListOf<String>()
                                            var mergedPause: Bitmap? = null

                                            for (tid in selectedTournaments) {
                                                try {
                                                    val overlay = ApiService.create(apiBase).getOverlayConfig(tid)
                                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                        if (mergedTopBitmaps.isEmpty()) {
                                                            val topLogos = overlay.logos.filter { it.position == "top_right" }
                                                            mergedTopBitmaps.addAll(topLogos.mapNotNull { loadBitmapFromUrl(it.url) })
                                                        }
                                                        if (mergedBottomBitmaps.isEmpty()) {
                                                            val bottomLogos = overlay.logos.filter { it.position == "bottom_right" }
                                                            mergedBottomBitmaps.addAll(bottomLogos.mapNotNull { loadBitmapFromUrl(it.url) })
                                                        }
                                                        if (mergedPause == null) {
                                                            overlay.logos.firstOrNull { it.position == "pause" }?.let { pauseLogo ->
                                                                mergedPause = loadBitmapFromUrl(pauseLogo.url)
                                                            }
                                                        }
                                                    }
                                                    if (mergedMarquee.isEmpty() && overlay.marquee_texts.isNotEmpty()) {
                                                        mergedMarquee.addAll(overlay.marquee_texts)
                                                    }
                                                } catch (_: Exception) {}
                                            }

                                            if (mergedTopBitmaps.isNotEmpty()) vn.vdpr.video.overlay.ScoreboardOverlay.topRightLogos = mergedTopBitmaps
                                            if (mergedBottomBitmaps.isNotEmpty()) vn.vdpr.video.overlay.ScoreboardOverlay.bottomRightLogos = mergedBottomBitmaps
                                            if (mergedMarquee.isNotEmpty()) vn.vdpr.video.overlay.ScoreboardOverlay.marqueeTexts = mergedMarquee
                                            if (mergedPause != null) vn.vdpr.video.overlay.ScoreboardOverlay.pauseImage = mergedPause
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
                        TextButton(onClick = { step = 0; tournamentSearch = ""; selectedTournaments = emptySet() }) { Text("← Quay lại") }
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
                        val displayName = selectedTournament?.name ?: selectedTournaments.let { tids ->
                            if (tids.size == 1) tournaments.find { it.id == tids.first() }?.name else "${tids.size} giải"
                        } ?: ""
                        Text("Chọn sân — $displayName", fontSize = 14.sp, color = Color.Gray)
                        val courts = streamConfig?.courts ?: emptyList()
                        val channelCounts = streamConfig?.court_channels ?: emptyMap()
                        if (courts.isEmpty()) {
                            Text("Chưa có sân nào", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(courts) { court ->
                                    val chCount = channelCounts[court] ?: 0
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedCourt = court
                                            autoLaunched = null
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

                        if (courtMatches.isEmpty()) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Color(0xFF16A34A), strokeWidth = 3.dp)
                            Text("Đang tải danh sách trận...", fontSize = 12.sp, color = Color.Gray)
                        } else {
                            // Show match list
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(courtMatches) { m ->
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

                            // Auto-live hint
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color(0xFF16A34A), strokeWidth = 2.dp)
                                Text("Tự động live khi trọng tài bắt đầu trận", fontSize = 11.sp, color = Color(0xFF94A3B8))
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            // Reset court selection but keep streamConfig
                            selectedCourt = null
                            courtMatches = emptyList()
                            autoLaunched = null
                            step = 2
                        }) { Text("← Đổi sân") }
                    }
                }
            }
        }
    }
}
