package com.arcade.bubbledefense.domain.economy

import com.arcade.bubbledefense.domain.world.GameWorld

/**
 * Reward placement discriminator for opt-in rewarded ad events.
 */
sealed interface RewardPlacement {
    /** 1-per-run Clutch Revive / EMP surge prompt on near-miss deaths. */
    data object ClutchRevive : RewardPlacement

    /** 2x Prism bounty doubler on the Game Over screen. */
    data object DoublePrismHarvest : RewardPlacement

    /** 1-run trial of a locked cosmetic shield shader. */
    data class SkinTrial(val skinId: String) : RewardPlacement
}

/**
 * Result outcome of an ad presentation or rewarded interaction.
 */
sealed interface RewardResult {
    /** Ad was viewed and the reward is verified and granted. */
    data object Granted : RewardResult

    /** Player dismissed the ad before completion. */
    data object Cancelled : RewardResult

    /** Ad inventory was unavailable or network failed. */
    data class Unavailable(val reason: String) : RewardResult
}

/**
 * Pure Kotlin abstraction boundary for billing, ads, and telemetry.
 *
 * Strict Architectural Rule (.agents/rules/05-economy-quarantine.md):
 * The engine, physics, and gameplay systems MUST NEVER import Google Play Billing,
 * AdMob, AppLovin, or external ad SDKs directly. All monetized actions pass through
 * this interface.
 */
interface EconomyGateway {

    /** Returns true if the player is currently eligible for a Clutch Revive / EMP surge. */
    fun isClutchReviveEligible(world: GameWorld): Boolean

    /** Returns true if non-opt-in interstitial ad criteria are satisfied. */
    fun canShowInterstitial(): Boolean

    /** Emits end-of-run telemetry and updates session cooldown counters. */
    fun recordRunCompleted(runDurationSeconds: Float, score: Int, parries: Int, prismsEarned: Int)

    /** Presents a rewarded ad for [placement] and delivers the outcome via [onResult]. */
    fun showRewardedAd(placement: RewardPlacement, onResult: (RewardResult) -> Unit)

    /** Presents an interstitial ad if cooldown conditions are met, then invokes [onCompleted]. */
    fun showInterstitialIfAllowed(onCompleted: () -> Unit)

    /** Soft currency Prism balance. */
    fun getPrismBalance(): Int

    /** Deducts soft currency if balance permits. Returns true on success. */
    fun spendPrisms(amount: Int): Boolean

    /** Credits soft currency to the player's account. */
    fun addPrisms(amount: Int)

    /** Active equipped shield skin ID. */
    var equippedShieldSkinId: String

    /** Active equipped bubble skin ID. */
    var equippedBubbleSkinId: String
}
