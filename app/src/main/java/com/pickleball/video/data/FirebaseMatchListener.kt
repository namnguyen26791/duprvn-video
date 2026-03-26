package com.pickleball.video.data

import com.google.firebase.database.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Listen to all live matches on Firebase and find the one playing on our court.
 * Checks both "matches/" (bracket) and "group_matches/" (group) paths.
 */
object FirebaseMatchListener {

    private val db = FirebaseDatabase.getInstance()

    fun observeCourtMatch(courtName: String): Flow<MatchState?> = callbackFlow {
        var currentMatch: MatchState? = null

        val bracketRef = db.getReference("matches")
        val groupRef = db.getReference("group_matches")

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val found = findMatchForCourt(snapshot, courtName)
                // Also check the other path
                currentMatch = found
                trySend(currentMatch)
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        val bracketListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val found = findMatchForCourt(snapshot, courtName)
                if (found != null) {
                    currentMatch = found
                    trySend(currentMatch)
                } else {
                    // Check group matches too
                    groupRef.addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(groupSnap: DataSnapshot) {
                            currentMatch = findMatchForCourt(groupSnap, courtName)
                            trySend(currentMatch)
                        }
                        override fun onCancelled(error: DatabaseError) {}
                    })
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        val groupListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val found = findMatchForCourt(snapshot, courtName)
                if (found != null) {
                    currentMatch = found
                    trySend(currentMatch)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }

        bracketRef.addValueEventListener(bracketListener)
        groupRef.addValueEventListener(groupListener)

        awaitClose {
            bracketRef.removeEventListener(bracketListener)
            groupRef.removeEventListener(groupListener)
        }
    }

    private fun findMatchForCourt(snapshot: DataSnapshot, courtName: String): MatchState? {
        for (child in snapshot.children) {
            val court = child.child("court").getValue(String::class.java) ?: continue
            val status = child.child("status").getValue(String::class.java) ?: continue
            if (court == courtName && status == "playing") {
                return parseMatch(child)
            }
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
