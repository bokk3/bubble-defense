package com.arcade.bubbledefense.domain.games

/**
 * An individual entry on the leaderboards / scoreboards.
 */
data class ScoreboardEntry(
    val rank: Int,
    val pilotName: String,
    val altitudeMeters: Float,
    val score: Long,
    val wave: Int,
    val dateIso: String,
    val isCurrentPlayer: Boolean = false
)

/**
 * A daily or weekly tactical bounty / mission.
 */
data class BountyMission(
    val id: String,
    val title: String,
    val description: String,
    val currentProgress: Int,
    val targetProgress: Int,
    val prismReward: Int,
    val xpReward: Int,
    val isClaimed: Boolean = false
) {
    val isCompleted: Boolean
        get() = currentProgress >= targetProgress

    val progressFraction: Float
        get() = (currentProgress.toFloat() / targetProgress.toFloat()).coerceIn(0f, 1f)
}
