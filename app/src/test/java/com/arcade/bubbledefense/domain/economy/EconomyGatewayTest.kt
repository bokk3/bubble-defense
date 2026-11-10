package com.arcade.bubbledefense.domain.economy

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EconomyGatewayTest {

    private lateinit var economy: MockEconomyService
    private lateinit var world: GameWorld

    @Before
    fun setUp() {
        economy = MockEconomyService(initialPrisms = 250)
        world = GameWorld(initialSeed = 101)
    }

    @Test
    fun interstitialCooldown_goldenRulesEnforced() {
        // Rule 1: No interstitial within first 3 runs
        economy.recordRunCompleted(runDurationSeconds = 60f, score = 100, parries = 5, prismsEarned = 10)
        assertEquals(1, economy.runsCount)
        assertFalse(economy.canShowInterstitial())

        economy.recordRunCompleted(runDurationSeconds = 60f, score = 120, parries = 6, prismsEarned = 10)
        assertEquals(2, economy.runsCount)
        assertFalse(economy.canShowInterstitial())

        economy.recordRunCompleted(runDurationSeconds = 60f, score = 150, parries = 7, prismsEarned = 10)
        assertEquals(3, economy.runsCount)
        assertFalse(economy.canShowInterstitial())

        // Run 4: Cumulative time = 180s + 60s = 240s >= 180s cooldown
        economy.recordRunCompleted(runDurationSeconds = 60f, score = 200, parries = 10, prismsEarned = 15)
        assertEquals(4, economy.runsCount)
        assertTrue(economy.canShowInterstitial())

        // Show interstitial: resets cooldown timer
        var shown = false
        economy.showInterstitialIfAllowed { shown = true }
        assertTrue(shown)
        assertEquals(economy.cumulativeGameplaySeconds, economy.lastInterstitialTimeSeconds, 1e-4f)

        // Immediately after: cooldown is active -> cannot show
        assertFalse(economy.canShowInterstitial())

        // Add 100s: 100s < 180s -> still cannot show
        economy.recordRunCompleted(runDurationSeconds = 100f, score = 50, parries = 2, prismsEarned = 5)
        assertFalse(economy.canShowInterstitial())

        // Add another 90s: total 190s >= 180s -> can show now
        economy.recordRunCompleted(runDurationSeconds = 90f, score = 80, parries = 4, prismsEarned = 5)
        assertTrue(economy.canShowInterstitial())
    }

    @Test
    fun clutchRevive_eligibility_enforcesSixtyPercentPb_andOnePerRun() {
        world.highScore = 1000

        // Score 500 < 600 (60% of 1000) -> ineligible
        world.score = 500
        world.reviveUsedThisRun = false
        assertFalse(economy.isClutchReviveEligible(world))

        // Score 600 == 600 (60% of 1000) -> eligible
        world.score = 600
        assertTrue(economy.isClutchReviveEligible(world))

        // Score 850 > 600 -> eligible
        world.score = 850
        assertTrue(economy.isClutchReviveEligible(world))

        // Once revive is used this run -> strictly ineligible
        world.reviveUsedThisRun = true
        assertFalse(economy.isClutchReviveEligible(world))
    }

    @Test
    fun prismEconomy_spendAndAddPrisms() {
        assertEquals(250, economy.getPrismBalance())

        // Try to spend more than balance
        val overspendSuccess = economy.spendPrisms(300)
        assertFalse(overspendSuccess)
        assertEquals(250, economy.getPrismBalance())

        // Valid spend
        val validSpend = economy.spendPrisms(100)
        assertTrue(validSpend)
        assertEquals(150, economy.getPrismBalance())

        // Add prisms
        economy.addPrisms(50)
        assertEquals(200, economy.getPrismBalance())
    }

    @Test
    fun cosmeticSkinUnlocks_andEquipping() {
        // Default skins
        assertEquals(ShieldSkin.CYBER_CYAN.id, economy.equippedShieldSkinId)
        assertEquals(BubbleSkin.CLASSIC_NEON.id, economy.equippedBubbleSkinId)

        // Unlock Plasma Magenta (cost: 150)
        val unlocked = economy.unlockSkin(ShieldSkin.PLASMA_MAGENTA.id, ShieldSkin.PLASMA_MAGENTA.costPrisms)
        assertTrue(unlocked)
        assertEquals(100, economy.getPrismBalance()) // 250 - 150 = 100
        assertTrue(economy.unlockedSkinIds.contains(ShieldSkin.PLASMA_MAGENTA.id))

        // Equip skin
        economy.equippedShieldSkinId = ShieldSkin.PLASMA_MAGENTA.id
        assertEquals(ShieldSkin.PLASMA_MAGENTA.id, economy.equippedShieldSkinId)

        // Try unlocking Solar Gold (cost: 300) with insufficient balance (100)
        val solarUnlocked = economy.unlockSkin(ShieldSkin.SOLAR_GOLD.id, ShieldSkin.SOLAR_GOLD.costPrisms)
        assertFalse(solarUnlocked)
        assertEquals(100, economy.getPrismBalance())
    }

    @Test
    fun cosmeticSkinLookup_fallbackToDefault() {
        assertEquals(ShieldSkin.CYBER_CYAN, ShieldSkin.findById("shield_cyber_cyan"))
        assertEquals(ShieldSkin.PLASMA_MAGENTA, ShieldSkin.findById("shield_plasma_magenta"))
        assertEquals(ShieldSkin.SOLAR_GOLD, ShieldSkin.findById("shield_solar_gold"))
        assertEquals(ShieldSkin.VOID_GLITCH, ShieldSkin.findById("shield_void_glitch"))
        assertEquals(ShieldSkin.CYBER_CYAN, ShieldSkin.findById("unknown_shield_id"))

        assertEquals(BubbleSkin.CLASSIC_NEON, BubbleSkin.findById("bubble_classic_neon"))
        assertEquals(BubbleSkin.PULSING_SYNTH, BubbleSkin.findById("bubble_pulsing_synth"))
        assertEquals(BubbleSkin.QUANTUM_TESSERACT, BubbleSkin.findById("bubble_quantum_tesseract"))
        assertEquals(BubbleSkin.CLASSIC_NEON, BubbleSkin.findById("unknown_bubble_id"))
    }
}
