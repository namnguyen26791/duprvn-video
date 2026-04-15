package com.pickleball.video

import android.content.Intent
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
import androidx.compose.foundation.Image
import com.pickleball.video.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoApp() {
    var apiBase by remember { mutableStateOf(BuildConfig.API_BASE) }
    var step by remember { mutableIntStateOf(0) }
    var tournaments by remember { mutableStateOf<List<TournamentListItem>>(emptyList()) }
    var tournamentSearch by remember { mutableStateOf("") }
    var selectedTournament by remember { mutableStateOf<TournamentListItem?>(null) }
    var streamConfig by remember { mutableStateOf<StreamConfigResponse?>(null) }
    var selectedCourt by remember { mutableStateOf<String?>(null) }
    var courtMatches by remember { mutableStateOf<List<CourtMatchItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var autoLaunched by remember { mutableStateOf<String?>(null) } // "matchId:broadcastId"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Step 3: Poll court matches, auto-launch when a match starts playing
    LaunchedEffect(step, selectedCourt) {
        if (step != 3 || selectedCourt == null) return@LaunchedEffect
        val tid = selectedTournament?.id ?: return@LaunchedEffect
        val court = selectedCourt ?: return@LaunchedEffect

        while (step == 3) {
            try {
                val matches = ApiService.create(apiBase).getCourtMatches(tid, court)
                courtMatches = matches

                // Auto-launch: find match that is live (stream_started_at set, not ended, has rtmp)
                val playing = matches.firstOrNull {
                    !it.stream_started_at.isNullOrBlank() &&
                    it.stream_ended_at.isNullOrBlank() &&
                    !it.rtmp_url.isNullOrBlank()
                }
                if (playing != null) {
                    // Track bằng "matchId:broadcastId" — khi admin tạo broadcast mới, launchKey thay đổi → auto-launch lại
                    val launchKey = "${playing.id}:${playing.broadcast_id ?: playing.rtmp_url ?: "none"}"
                    if (autoLaunched != launchKey && !playing.rtmp_url.isNullOrBlank()) {
                        autoLaunched = launchKey
                        val intent = Intent(context, StreamActivity::class.java).apply {
                            putExtra("match_id", playing.id)
                            putExtra("match_type", playing.match_type)
                            putExtra("api_base", apiBase)
                            putExtra("tournament_id", tid as Int)
                            putExtra("court_name", court)
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
                    contentDescription = "PickBase",
                    modifier = Modifier.size(64.dp)
                )
                Text("PickBase Live", fontSize = 20.sp, fontWeight = FontWeight.Bold)

                when (step) {
                    0 -> {
                        Text("Nhập địa chỉ server", fontSize = 14.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = apiBase, onValueChange = { apiBase = it },
                            label = { Text("API Base URL") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
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
                            enabled = apiBase.isNotBlank() && !loading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                        ) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            else Text("Tiếp tục →", fontWeight = FontWeight.Bold)
                        }
                    }

                    1 -> {
                        Text("Chọn giải đấu", fontSize = 14.sp, color = Color.Gray)
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
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedTournament = t; loading = true; error = null
                                            scope.launch {
                                                try {
                                                    streamConfig = ApiService.create(apiBase).getStreamConfig(t.id)
                                                    step = 2
                                                } catch (e: Exception) { error = "Lỗi: ${e.message}" }
                                                loading = false
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text("🏆 ${t.name}", modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                        if (error != null) Text(error!!, color = Color.Red, fontSize = 12.sp)
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        TextButton(onClick = { step = 0; tournamentSearch = "" }) { Text("← Quay lại") }
                    }

                    2 -> {
                        Text("Chọn sân — ${selectedTournament?.name}", fontSize = 14.sp, color = Color.Gray)
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
                        TextButton(onClick = { step = 2 }) { Text("← Đổi sân") }
                    }
                }
            }
        }
    }
}
