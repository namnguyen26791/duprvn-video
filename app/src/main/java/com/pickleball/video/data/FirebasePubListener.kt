package vn.vdpr.video.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Node pub/{tid}/courts/{courtKey} — tín hiệu live + RTMP cho máy video. */
data class CourtLiveSignal(
    val matchId: Int,
    val matchType: String,
    val status: String?,
    val court: String?,
    val streamReady: Boolean,
    val streamStartedAt: String?,
    val streamEndedAt: String?,
    val rtmpUrl: String?,
    val streamKey: String?,
    val broadcastId: String?,
    val youtubeVideoId: String?,
    val team1: String?,
    val team2: String?,
)

data class CatalogTournament(
    val id: Int,
    val name: String,
    val bannerImage: String?,
    val startDate: String?,
    val location: String?,
    val courts: List<String>,
)

object FirebasePubListener {
    private val db = FirebaseDatabase.getInstance()

    fun courtKey(court: String): String {
        return court.trim()
            .replace("/", "_")
            .replace(".", "_")
            .replace("#", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
    }

    fun observeCourt(tournamentId: Int, court: String): Flow<CourtLiveSignal?> = callbackFlow {
        val key = courtKey(court)
        val ref = db.getReference("pub/$tournamentId/courts/$key")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                trySend(parseCourt(snapshot))
            }
            override fun onCancelled(error: DatabaseError) {
                android.util.Log.w("PB_FIREBASE", "observeCourt cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /** Gộp court từ nhiều giải đã chọn → Pair(tournamentId, signal). */
    fun observeCourts(tournamentIds: List<Int>, court: String): Flow<List<Pair<Int, CourtLiveSignal>>> = callbackFlow {
        if (tournamentIds.isEmpty()) {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }
        val key = courtKey(court)
        val latest = linkedMapOf<Int, CourtLiveSignal?>()
        val listeners = mutableListOf<Pair<com.google.firebase.database.DatabaseReference, ValueEventListener>>()

        fun emit() {
            trySend(latest.mapNotNull { (tid, sig) -> sig?.let { tid to it } })
        }

        for (tid in tournamentIds) {
            latest[tid] = null
            val ref = db.getReference("pub/$tid/courts/$key")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    latest[tid] = if (snapshot.exists()) parseCourt(snapshot) else null
                    emit()
                }
                override fun onCancelled(error: DatabaseError) {}
            }
            ref.addValueEventListener(listener)
            listeners.add(ref to listener)
        }

        awaitClose {
            listeners.forEach { (ref, l) -> ref.removeEventListener(l) }
        }
    }

    fun observeCatalog(): Flow<List<CatalogTournament>> = callbackFlow {
        val ref = db.getReference("video_catalog")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<CatalogTournament>()
                for (child in snapshot.children) {
                    val id = child.child("id").getValue(Int::class.java)
                        ?: child.key?.toIntOrNull()
                        ?: continue
                    val name = child.child("name").getValue(String::class.java) ?: continue
                    val courtsSnap = child.child("courts")
                    val courts = mutableListOf<String>()
                    if (courtsSnap.exists()) {
                        for (c in courtsSnap.children) {
                            c.getValue(String::class.java)?.let { courts.add(it) }
                        }
                    }
                    list.add(
                        CatalogTournament(
                            id = id,
                            name = name,
                            bannerImage = child.child("banner_image").getValue(String::class.java),
                            startDate = child.child("start_date").getValue(String::class.java),
                            location = child.child("location").getValue(String::class.java),
                            courts = courts,
                        )
                    )
                }
                list.sortByDescending { it.startDate ?: "" }
                trySend(list)
            }
            override fun onCancelled(error: DatabaseError) {
                android.util.Log.w("PB_FIREBASE", "observeCatalog: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeMatchStream(matchType: String, matchId: Int, tournamentId: Int): Flow<CourtLiveSignal?> = callbackFlow {
        // Fallback: tìm court node theo matchId trong pub/{tid}/courts
        val ref = db.getReference("pub/$tournamentId/courts")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                for (child in snapshot.children) {
                    val mid = child.child("matchId").getValue(Int::class.java) ?: continue
                    val mt = child.child("matchType").getValue(String::class.java) ?: "bracket"
                    if (mid == matchId && mt == matchType) {
                        trySend(parseCourt(child))
                        return
                    }
                }
                trySend(null)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    private fun parseCourt(snap: DataSnapshot): CourtLiveSignal {
        return CourtLiveSignal(
            matchId = snap.child("matchId").getValue(Int::class.java) ?: 0,
            matchType = snap.child("matchType").getValue(String::class.java) ?: "bracket",
            status = snap.child("status").getValue(String::class.java),
            court = snap.child("court").getValue(String::class.java),
            streamReady = snap.child("streamReady").getValue(Boolean::class.java) ?: false,
            streamStartedAt = snap.child("streamStartedAt").getValue(String::class.java),
            streamEndedAt = snap.child("streamEndedAt").getValue(String::class.java),
            rtmpUrl = snap.child("rtmpUrl").getValue(String::class.java),
            streamKey = snap.child("streamKey").getValue(String::class.java),
            broadcastId = snap.child("broadcastId").getValue(String::class.java),
            youtubeVideoId = snap.child("youtubeVideoId").getValue(String::class.java),
            team1 = snap.child("team1").getValue(String::class.java),
            team2 = snap.child("team2").getValue(String::class.java),
        )
    }
}
