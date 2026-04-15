package asia.pickbase.video.data

import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Listen to live matches on Firebase filtered by court + tournament.
 */
object FirebaseMatchListener {

    private val db = FirebaseDatabase.getInstance()

    fun observeCourtMatch(courtName: String, tournamentId: Int = 0): Flow<MatchState?> = callbackFlow {
        val bracketRef = db.getReference("matches")
        val groupRef = db.getReference("group_matches")

        val bracketListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val found = findMatch(snapshot, courtName, tournamentId)
                if (found != null) {
                    trySend(found)
                } else {
                    groupRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(gs: DataSnapshot) {
                            trySend(findMatch(gs, courtName, tournamentId))
                        }
                        override fun onCancelled(e: DatabaseError) {}
                    })
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }

        val groupListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val found = findMatch(snapshot, courtName, tournamentId)
                if (found != null) trySend(found)
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
        for (child in snapshot.children) {
            val court = child.child("court").getValue(String::class.java) ?: continue
            val status = child.child("status").getValue(String::class.java) ?: continue
            val updatedAt = child.child("updatedAt").getValue(Long::class.java) ?: 0L
            val tid = child.child("tournamentId").getValue(Int::class.java) ?: 0
            android.util.Log.d("PB_VIDEO", "  child=${child.key}: court='$court' status='$status' tid=$tid updatedAt=$updatedAt age=${(now-updatedAt)/1000}s")

            if (court != courtName) continue
            if (status != "playing") continue
            if ((now - updatedAt) > 7200000) continue
            if (tournamentId > 0 && tid > 0 && tid != tournamentId) continue

            android.util.Log.d("PB_VIDEO", "  → MATCHED!")
            return parseMatch(child)
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
            courtSwapped = snap.child("courtSwapped").getValue(Boolean::class.java) ?: false,
            court = snap.child("court").getValue(String::class.java),
            tournamentName = snap.child("tournamentName").getValue(String::class.java),
            roundName = snap.child("roundName").getValue(String::class.java),
            paused = snap.child("paused").getValue(Boolean::class.java) ?: false,
            status = snap.child("status").getValue(String::class.java) ?: "",
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

