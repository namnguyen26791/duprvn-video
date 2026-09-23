package vn.vdpr.video.data

import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Listen to live matches on Firebase filtered by court + tournament.
 */
object FirebaseMatchListener {

    private val db = FirebaseDatabase.getInstance()

    fun observeMatchById(matchType: String, matchId: Int): Flow<MatchState?> = callbackFlow {
        val refPath = if (matchType == "group") "group_matches" else "matches"
        val ref = db.getReference("$refPath/$matchId")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(null)
                    return
                }
                val status = snapshot.child("status").getValue(String::class.java) ?: ""
                val parsed = parseMatch(snapshot)
                when (status) {
                    "playing", "paused" -> trySend(parsed)
                    "resolved" -> trySend(parsed.copy(status = "resolved"))
                    else -> {
                        // Snapshot giữa lần ghi điểm (máy yếu) — đừng emit null nếu còn tên đội
                        val hasTeams = parsed.left.teamName.isNotBlank() || parsed.right.teamName.isNotBlank()
                        android.util.Log.w("PB_OVERLAY", "Firebase status='$status' hasTeams=$hasTeams — keep parse")
                        if (hasTeams) trySend(parsed) else trySend(null)
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }

        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeCourtMatch(courtName: String, tournamentId: Int = 0): Flow<MatchState?> = callbackFlow {
        val bracketRef = db.getReference("matches")
        val groupRef = db.getReference("group_matches")

        var lastEmitted: MatchState? = null

        val emitBest = { bracketSnap: DataSnapshot?, groupSnap: DataSnapshot? ->
            val fromBracket = if (bracketSnap != null) findMatch(bracketSnap, courtName, tournamentId) else null
            val fromGroup = if (groupSnap != null) findMatch(groupSnap, courtName, tournamentId) else null
            val best = fromBracket ?: fromGroup
            if (best != lastEmitted) {
                lastEmitted = best
                trySend(best)
            }
        }

        var latestBracketSnap: DataSnapshot? = null
        var latestGroupSnap: DataSnapshot? = null

        val bracketListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestBracketSnap = snapshot
                emitBest(latestBracketSnap, latestGroupSnap)
            }
            override fun onCancelled(e: DatabaseError) {}
        }

        val groupListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                latestGroupSnap = snapshot
                emitBest(latestBracketSnap, latestGroupSnap)
            }
            override fun onCancelled(e: DatabaseError) {}
        }

        bracketRef.addValueEventListener(bracketListener)
        groupRef.addValueEventListener(groupListener)
        awaitClose {
            bracketRef.removeEventListener(bracketListener)
            groupRef.removeEventListener(groupListener)
        }
    }

    private fun findMatch(snapshot: DataSnapshot, courtName: String, tournamentId: Int): MatchState? {
        val now = System.currentTimeMillis()
        android.util.Log.d("PB_VIDEO", "findMatch: looking for court='$courtName' tid=$tournamentId, children=${snapshot.childrenCount}")
        
        var bestChild: DataSnapshot? = null
        var bestUpdatedAt = 0L

        for (child in snapshot.children) {
            val court = child.child("court").getValue(String::class.java) ?: continue
            val status = child.child("status").getValue(String::class.java) ?: continue
            val updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: 0L
            val tid = child.child("tournamentId").getValue(Int::class.java) ?: 0

            if (court != courtName) continue
            // Chấp nhận resolved để commentary đọc được câu kết (trọng tài đã ấn kết thúc)
            if (status != "playing" && status != "resolved") continue
            if ((now - updatedAt) > 7200000) continue
            if (tournamentId > 0 && tid > 0 && tid != tournamentId) continue

            // Pick the most recently updated match
            if (updatedAt > bestUpdatedAt) {
                bestUpdatedAt = updatedAt
                bestChild = child
            }
        }

        if (bestChild != null) {
            android.util.Log.d("PB_VIDEO", "  → MATCHED: ${bestChild.key} updatedAt=${bestUpdatedAt} status=${bestChild.child("status").getValue(String::class.java)}")
            return parseMatch(bestChild)
        }
        return null
    }

    private fun parseMatch(snap: DataSnapshot): MatchState {
        return MatchState(
            left = parseTeam(snap.child("left")),
            right = parseTeam(snap.child("right")),
            scoreLeft = snap.child("scoreLeft").getValue(Int::class.java) ?: 0,
            scoreRight = snap.child("scoreRight").getValue(Int::class.java) ?: 0,
            serve = snap.child("serve").getValue(String::class.java) ?: "left",
            serverNum = snap.child("serverNum").getValue(Int::class.java) ?: 2,
            serverHand = snap.child("serverHand").getValue(Int::class.java) ?: 1,
            serverIsHand1 = snap.child("serverIsHand1").getValue(Boolean::class.java) ?: true,
            courtSwapped = snap.child("courtSwapped").getValue(Boolean::class.java) ?: false,
            matchFormat = snap.child("matchFormat").getValue(String::class.java) ?: "doubles",
            scoringType = snap.child("scoringType").getValue(String::class.java) ?: "side_out",
            court = snap.child("court").getValue(String::class.java),
            tournamentName = snap.child("tournamentName").getValue(String::class.java),
            roundName = snap.child("roundName").getValue(String::class.java),
            paused = snap.child("paused").getValue(Boolean::class.java) ?: false,
            pauseReason = snap.child("pauseReason").getValue(String::class.java)
                ?: snap.child("pause_reason").getValue(String::class.java)
                ?: "",
            status = snap.child("status").getValue(String::class.java) ?: "",
            winScore = snap.child("win_score").getValue(Int::class.java)
                ?: snap.child("winScore").getValue(Int::class.java)
                ?: 0,
            maxScore = snap.child("max_score").getValue(Int::class.java)
                ?: snap.child("maxScore").getValue(Int::class.java)
                ?: 0,
        )
    }

    private fun parseTeam(snap: DataSnapshot): TeamSide {
        return TeamSide(
            teamId = snap.child("teamId").getValue(Int::class.java) ?: 0,
            teamName = snap.child("teamName").getValue(String::class.java) ?: "",
            hand1 = snap.child("hand1").getValue(String::class.java) ?: "",
            hand2 = snap.child("hand2").getValue(String::class.java) ?: "",
        )
    }
}
