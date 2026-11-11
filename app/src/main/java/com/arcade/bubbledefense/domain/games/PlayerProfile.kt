package com.arcade.bubbledefense.domain.games

/**
 * Player pilot profile data representing pilot progression, career achievements, and stats.
 */
data class PlayerProfile(
    val gamerTag: String = "VOID-PILOT",
    val pilotRank: String = "CADET I",
    val pilotLevel: Int = 1,
    val currentXp: Long = 0L,
    val xpForNextLevel: Long = 1000L,
    val careerBestAltitude: Float = 0f,
    val careerHighScore: Long = 0L,
    val careerTotalParries: Int = 0,
    val careerRunsPlayed: Int = 0,
    val careerPrismsCollected: Int = 0,
    val bountiesCompleted: Int = 0
) {
    val progressToNextLevel: Float
        get() = (currentXp.toFloat() / xpForNextLevel.toFloat()).coerceIn(0f, 1f)
}
