package com.pickleball.video.data

/** Match state from Firebase — mirrors what Referee app writes */
data class TeamSide(
    val teamId: Int = 0,
    val teamName: String = "",
    val hand1: String = "",
    val hand2: String = "",
)

data class MatchState(
    val left: TeamSide = TeamSide(),
    val right: TeamSide = TeamSide(),
    val scoreLeft: Int = 0,
    val scoreRight: Int = 0,
    val serve: String = "left",
    val serverNum: Int = 2,
    val serverHand: Int = 1,
    val courtSwapped: Boolean = false,
    val court: String? = null,
    val tournamentId: Int? = null,
    val tournamentName: String? = null,
    val roundName: String? = null,
    val paused: Boolean = false,
    val status: String = "",
)

data class StreamConfig(
    val rtmpUrl: String = "",
    val streamKey: String = "",
    val youtubeVideoId: String = "",
)
