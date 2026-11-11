package com.arcade.bubbledefense.domain.games

import kotlinx.coroutines.flow.StateFlow

/**
 * Pure Kotlin abstraction boundary for Authentication, Leaderboards, Achievements, and Profile.
 *
 * Strict Architectural Rule:
 * Engine and simulation systems MUST NOT depend directly on Google Play Games or Firebase SDKs.
 * All games and profile interactions flow through this gateway.
 */
interface AuthAndGamesGateway {

    /** Current reactive player profile. */
    val profileState: StateFlow<PlayerProfile>

    /** Current leaderboards list (sorted highest score first). */
    val scoreboardState: StateFlow<List<ScoreboardEntry>>

    /** Current daily & weekly bounties list. */
    val bountiesState: StateFlow<List<BountyMission>>

    /** True if connected to an external 3rd-party/cloud service (e.g. Google Play Games). */
    val isCloudConnected: Boolean

    /** Set or update the player's custom gamer callsign. */
    fun updateGamerTag(newTag: String)

    /** Record the completion of an active run, updating XP, ranks, best stats, and bounties. */
    fun recordRunResult(
        score: Long,
        altitudeMeters: Float,
        wave: Int,
        parries: Int,
        prismsEarned: Int
    )

    /** Claim the rewards for a completed bounty. Returns prisms granted. */
    fun claimBountyReward(bountyId: String): Int
}
