package com.arcade.bubbledefense.domain.economy

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld

/**
 * In-memory test implementation of [EconomyGateway].
 *
 * Simulates rewarded ad and monetization events instantly for deterministic testing
 * and offline development without external SDK dependencies.
 */
class MockEconomyService(
    initialPrisms: Int = 100
) : EconomyGateway {

    private var prisms: Int = initialPrisms
    var runsCount: Int = 0
        private set
    var cumulativeGameplaySeconds: Float = 0f
        private set
    var lastInterstitialTimeSeconds: Float = -9999f
        private set

    override var equippedShieldSkinId: String = ShieldSkin.CYBER_CYAN.id
    override var equippedBubbleSkinId: String = BubbleSkin.CLASSIC_NEON.id

    val unlockedSkinIds = mutableSetOf(
        ShieldSkin.CYBER_CYAN.id,
        BubbleSkin.CLASSIC_NEON.id
    )

    override fun isClutchReviveEligible(world: GameWorld): Boolean {
        if (world.reviveUsedThisRun) return false
        // Eligible if player achieved at least 60% of their personal best high score
        val threshold = (world.highScore * GameConfig.CLUTCH_REVIVE_MIN_PB_RATIO).toInt()
        return world.score >= threshold
    }

    override fun canShowInterstitial(): Boolean {
        // Enforce golden rules: no interstitial within first 3 runs, and 180s cooldown
        val runCountMet = runsCount > GameConfig.INTERSTITIAL_MIN_RUNS_BEFORE_FIRST
        val cooldownMet = (cumulativeGameplaySeconds - lastInterstitialTimeSeconds) >= GameConfig.INTERSTITIAL_MIN_COOLDOWN_SECONDS
        return runCountMet && cooldownMet
    }

    override fun recordRunCompleted(
        runDurationSeconds: Float,
        score: Int,
        parries: Int,
        prismsEarned: Int
    ) {
        runsCount++
        cumulativeGameplaySeconds += runDurationSeconds
        prisms += prismsEarned
    }

    override fun showRewardedAd(
        placement: RewardPlacement,
        onResult: (RewardResult) -> Unit
    ) {
        // Mock instant ad success
        when (placement) {
            is RewardPlacement.DoublePrismHarvest -> {
                // Double harvest logic handled by caller
            }
            is RewardPlacement.ClutchRevive -> {
                // Revive grant handled by caller
            }
            is RewardPlacement.SkinTrial -> {
                // Trial granted
            }
        }
        onResult(RewardResult.Granted)
    }

    override fun showInterstitialIfAllowed(onCompleted: () -> Unit) {
        if (canShowInterstitial()) {
            lastInterstitialTimeSeconds = cumulativeGameplaySeconds
        }
        onCompleted()
    }

    override fun getPrismBalance(): Int = prisms

    override fun spendPrisms(amount: Int): Boolean {
        if (amount <= 0) return true
        return if (prisms >= amount) {
            prisms -= amount
            true
        } else {
            false
        }
    }

    override fun addPrisms(amount: Int) {
        if (amount > 0) {
            prisms += amount
        }
    }

    override var droneUpgradeLevel: Int = 0

    override fun getNextDroneUpgradeCost(): Int {
        return if (droneUpgradeLevel < GameConfig.DRONE_MAX_LEVEL) {
            GameConfig.DRONE_UPGRADE_COSTS[droneUpgradeLevel]
        } else -1
    }

    override fun upgradeDrone(): Boolean {
        val cost = getNextDroneUpgradeCost()
        if (cost > 0 && spendPrisms(cost)) {
            droneUpgradeLevel++
            return true
        }
        return false
    }

    /** Unlocks a cosmetic skin if the player has sufficient prisms. */
    fun unlockSkin(skinId: String, cost: Int): Boolean {
        if (unlockedSkinIds.contains(skinId)) return true
        return if (spendPrisms(cost)) {
            unlockedSkinIds.add(skinId)
            true
        } else {
            false
        }
    }
}
