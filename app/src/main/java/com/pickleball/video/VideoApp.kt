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
import com.pickleball.video.data.*
import kotlinx.coroutines.launch

/**
 * Setup screen: enter API URL → select tournament → select court → start streaming.
 */
@Composable
fun VideoApp() {
    var apiBase by remember { mutableStateOf(BuildConfig.API_BASE) }
    var step by remember { mutableIntStateOf(0) } // 0=api, 1=tournament, 2=court
    var tournaments by remember { mutableStateOf<List<TournamentListItem>>(emptyList()) }
    var selectedTournament by remember { mutableStateOf<TournamentListItem?>(null) }
    var streamConfig by remember { mutableStateOf<StreamConfigResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                Text("🏓 Pickleball Video", fontSize = 24.sp, fontWeight = FontWeight.Bold)

                when (step) {
                    // Step 0: API URL
                    0 -> {
                        Text("Nhập địa chỉ server", fontSize = 14.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = apiBase,
                            onValueChange = { apiBase = it },
                            label = { Text("API Base URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        if (error != null) Text(error!!, color = Color.Red, fontSize = 12.sp)
                        Button(
                            onClick = {
                                error = null; loading = true
                                scope.launch {
                                    try {
                                        val api = ApiService.create(apiBase)
                                        tournaments = api.getTournaments().data
                                        step = 1
                                    } catch (e: Exception) {
                                        error = "Không kết nối được: ${e.message}"
                                    }
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

                    // Step 1: Select tournament
                    1 -> {
                        Text("Chọn giải đấu", fontSize = 14.sp, color = Color.Gray)
                        if (tournaments.isEmpty()) {
                            Text("Không có giải đấu nào", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(tournaments) { t ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedTournament = t; loading = true; error = null
                                            scope.launch {
                                                try {
                                                    val api = ApiService.create(apiBase)
                                                    streamConfig = api.getStreamConfig(t.id)
                                                    step = 2
                                                } catch (e: Exception) {
                                                    error = "Lỗi: ${e.message}"
                                                }
                                                loading = false
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Text(
                                            text = "🏆 ${t.name}",
                                            modifier = Modifier.padding(12.dp),
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }
                        if (error != null) Text(error!!, color = Color.Red, fontSize = 12.sp)
                        if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        TextButton(onClick = { step = 0 }) { Text("← Quay lại") }
                    }

                    // Step 2: Select court → start stream
                    2 -> {
                        Text("Chọn sân — ${selectedTournament?.name}", fontSize = 14.sp, color = Color.Gray)
                        val courts = streamConfig?.courts ?: emptyList()
                        val configs = streamConfig?.stream_config ?: emptyMap()

                        if (courts.isEmpty()) {
                            Text("Chưa có sân nào được cấu hình", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(courts) { court ->
                                    val cfg = configs[court]
                                    val hasStream = cfg?.rtmp_url?.isNotBlank() == true
                                    Card(
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            // Launch StreamActivity
                                            val intent = Intent(context, StreamActivity::class.java).apply {
                                                putExtra("api_base", apiBase)
                                                putExtra("court_name", court)
                                                putExtra("tournament_id", selectedTournament?.id ?: 0)
                                                putExtra("rtmp_url", cfg?.rtmp_url ?: "")
                                                putExtra("stream_key", cfg?.stream_key ?: "")
                                            }
                                            context.startActivity(intent)
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("🏟 $court", fontWeight = FontWeight.Medium)
                                                Text(
                                                    if (hasStream) "🔴 RTMP sẵn sàng" else "⚠️ Chưa có RTMP",
                                                    fontSize = 11.sp,
                                                    color = if (hasStream) Color(0xFF16A34A) else Color(0xFFEAB308),
                                                )
                                            }
                                            Text("▶", fontSize = 20.sp, color = Color(0xFF16A34A))
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { step = 1 }) { Text("← Quay lại") }
                    }
                }
            }
        }
    }
}
