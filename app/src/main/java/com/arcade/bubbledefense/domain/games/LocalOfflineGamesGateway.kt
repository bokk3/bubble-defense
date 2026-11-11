package com.arcade.bubbledefense.domain.games

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/**
 * Local offline implementation of [AuthAndGamesGateway].
 *
 * Provides a fully functional profile, career progression, rank computation, competitive
 * local scoreboards, and bounty mission tracking with zero external cloud or SDK dependencies.
 */
class LocalOfflineGamesGateway : AuthAndGamesGateway {

    private val _profileState = MutableStateFlow(
        PlayerProfile(
            gamerTag = "VOID-PILOT",
            pilotRank = "CADET I",
            pilotLevel = 1,
            currentXp = 0L,
            xpForNextLevel = 500L,
            careerBestAltitude = 0f,
            careerHighScore = 0L,
            careerTotalParries = 0,
            careerRunsPlayed = 0,
            careerPrismsCollected = 0,
            bountiesCompleted = 0
        )
    )
    override val profileState: StateFlow<PlayerProfile> = _profileState.asStateFlow()

    private val _scoreboardState = MutableStateFlow(
        listOf(
            ScoreboardEntry(1, "AEGIS-9", 1840f, 24500L, 8, "2026-11-10"),
            ScoreboardEntry(2, "NOVA-PRIME", 1420f, 18200L, 6, "2026-11-10"),
            ScoreboardEntry(3, "VOID-VIPER", 980f, 12650L, 4, "2026-11-11"),
            ScoreboardEntry(4, "SOLAR-FOX", 650f, 8400L, 3, "2026-11-11"),
            ScoreboardEntry(5, "NEBULA-ZERO", 310f, 4200L, 2, "2026-11-11")
        )
    )
    override val scoreboardState: StateFlow<List<ScoreboardEntry>> = _scoreboardState.asStateFlow()

    private val _bountiesState = MutableStateFlow(
        listOf(
            BountyMission(
                id = "bounty_parry_15",
                title = "KINETIC MASTERY",
                description = "Execute 15 Perfect Parries across runs",
                currentProgress = 0,
                targetProgress = 15,
                prismReward = 25,
                xpReward = 350
            ),
            BountyMission(
                id = "bounty_altitude_500",
                title = "DEEP STRATOSPHERE",
                description = "Reach an altitude of 500 meters in a single sortie",
                currentProgress = 0,
                targetProgress = 500,
                prismReward = 30,
                xpReward = 400
            ),
            BountyMission(
                id = "bounty_prisms_40",
                title = "VOID HARVEST",
                description = "Collect 40 Prisms in space combat",
                currentProgress = 0,
                targetProgress = 40,
                prismReward = 20,
                xpReward = 250
            ),
            BountyMission(
                id = "bounty_runs_3",
                title = "PATROL SORTIE",
                description = "Deploy on 3 tactical defense sorties",
                currentProgress = 0,
                targetProgress = 3,
                prismReward = 15,
                xpReward = 200
            )
        )
    )
    override val bountiesState: StateFlow<List<BountyMission>> = _bountiesState.asStateFlow()

    override val isCloudConnected: Boolean = false

    override fun updateGamerTag(newTag: String) {
        val sanitized = newTag.trim().take(16).ifEmpty { "VOID-PILOT" }
        _profileState.value = _profileState.value.copy(gamerTag = sanitized)
    }

    override fun recordRunResult(
        score: Long,
        altitudeMeters: Float,
        wave: Int,
        parries: Int,
        prismsEarned: Int
    ) {
        val current = _profileState.value

        // 1. Calculate XP earned from run (score/10 + altitude*2 + parries*20)
        val earnedXp = (score / 10L) + (altitudeMeters.toLong() * 2L) + (parries.toLong() * 20L)
        var totalXp = current.currentXp + earnedXp
        var level = current.pilotLevel
        var xpRequired = current.xpForNextLevel

        while (totalXp >= xpRequired) {
            totalXp -= xpRequired
            level += 1
            xpRequired = 500L + (level * 250L)
        }

        val rankTitle = computeRankTitle(level)

        _profileState.value = current.copy(
            pilotRank = rankTitle,
            pilotLevel = level,
            currentXp = totalXp,
            xpForNextLevel = xpRequired,
            careerBestAltitude = max(current.careerBestAltitude, altitudeMeters),
            careerHighScore = max(current.careerHighScore, score),
            careerTotalParries = current.careerTotalParries + parries,
            careerRunsPlayed = current.careerRunsPlayed + 1,
            careerPrismsCollected = current.careerPrismsCollected + prismsEarned
        )

        // 2. Update Leaderboard Entry
        val playerEntry = ScoreboardEntry(
            rank = 0, // Computed after sorting
            pilotName = _profileState.value.gamerTag,
            altitudeMeters = altitudeMeters,
            score = score,
            wave = wave,
            dateIso = "TODAY",
            isCurrentPlayer = true
        )

        val updatedList = (_scoreboardState.value + playerEntry)
            .sortedByDescending { it.score }
            .take(10)
            .mapIndexed { idx, entry -> entry.copy(rank = idx + 1) }

        _scoreboardState.value = updatedList

        // 3. Update Bounties
        _bountiesState.value = _bountiesState.value.map { b ->
            if (b.isClaimed) return@map b
            when (b.id) {
                "bounty_parry_15" -> b.copy(currentProgress = (b.currentProgress + parries).coerceAtMost(b.targetProgress))
                "bounty_altitude_500" -> b.copy(currentProgress = max(b.currentProgress, altitudeMeters.toInt()).coerceAtMost(b.targetProgress))
                "bounty_prisms_40" -> b.copy(currentProgress = (b.currentProgress + prismsEarned).coerceAtMost(b.targetProgress))
                "bounty_runs_3" -> b.copy(currentProgress = (b.currentProgress + 1).coerceAtMost(b.targetProgress))
                else -> b
            }
        }
    }

    override fun claimBountyReward(bountyId: String): Int {
        val bounty = _bountiesState.value.find { it.id == bountyId } ?: return 0
        if (!bounty.isCompleted || bounty.isClaimed) return 0

        _bountiesState.value = _bountiesState.value.map {
            if (it.id == bountyId) it.copy(isClaimed = true) else it
        }

        // Grant XP and Prisms
        val current = _profileState.value
        var totalXp = current.currentXp + bounty.xpReward
        var level = current.pilotLevel
        var xpRequired = current.xpForNextLevel

        while (totalXp >= xpRequired) {
            totalXp -= xpRequired
            level += 1
            xpRequired = 500L + (level * 250L)
        }

        _profileState.value = current.copy(
            pilotLevel = level,
            pilotRank = computeRankTitle(level),
            currentXp = totalXp,
            xpForNextLevel = xpRequired,
            bountiesCompleted = current.bountiesCompleted + 1,
            careerPrismsCollected = current.careerPrismsCollected + bounty.prismReward
        )

        return bounty.prismReward
    }

    private fun computeRankTitle(level: Int): String = when {
        level <= 1 -> "CADET I"
        level == 2 -> "CADET II"
        level == 3 -> "VOID SCOUT I"
        level == 4 -> "VOID SCOUT II"
        level == 5 -> "VOID PIONEER I"
        level == 6 -> "VOID PIONEER II"
        level == 7 -> "AEGIS CAPTAIN"
        level == 8 -> "COSMIC DEFENDER"
        level == 9 -> "STELLAR VANGUARD"
        else -> "SINGULARITY MASTER"
    }
}
