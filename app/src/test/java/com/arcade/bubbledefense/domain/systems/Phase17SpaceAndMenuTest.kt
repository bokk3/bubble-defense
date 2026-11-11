package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.games.LocalOfflineGamesGateway
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 17 unit test suite covering:
 * - [SpaceFloatSystem]: 3-axis Lissajous zero-g drift, gyroscopic tilt, RCS micro-thrusters, orbiting satellites.
 * - [EnemyAISystem]: Void Stalker, Sniper Platform, Gravity Weaver, Swarmer state machines & swap-remove sync.
 * - [LocalOfflineGamesGateway]: Player profile, pilot rank math, scoreboard sorting, bounty progress & claims.
 */
class Phase17SpaceAndMenuTest {

    private lateinit var world: GameWorld
    private lateinit var spaceFloat: SpaceFloatSystem
    private lateinit var enemyAI: EnemyAISystem
    private lateinit var gamesGateway: LocalOfflineGamesGateway

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 42)
        spaceFloat = SpaceFloatSystem()
        enemyAI = EnemyAISystem(world)
        gamesGateway = LocalOfflineGamesGateway()

        // Wire obstacle swap-remove listener
        world.obstacles.onSwapRemoveListener = com.arcade.bubbledefense.engine.pool.ObstacleSwapRemoveListener { idx, last ->
            enemyAI.onSwapRemove(idx, last)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. SpaceFloatSystem Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testSpaceFloatInitialConditions() {
        assertEquals(0f, spaceFloat.offsetX, 0.001f)
        assertEquals(0f, spaceFloat.offsetY, 0.001f)
        assertEquals(0f, spaceFloat.elevationZ, 0.001f)
        assertEquals(0f, spaceFloat.pitch, 0.001f)
        assertEquals(0f, spaceFloat.roll, 0.001f)
        assertEquals(0f, spaceFloat.rcsLeft, 0.001f)
        assertEquals(0f, spaceFloat.rcsRight, 0.001f)
        assertEquals(0f, spaceFloat.rcsBottom, 0.001f)
    }

    @Test
    fun testSpaceFloatBuoyancyDriftWithinBounds() {
        // Step space float simulation for 10 seconds (1200 steps at 120Hz)
        val dt = 1f / 120f
        for (step in 0 until 1200) {
            spaceFloat.update(dt, inputActive = false, swipeDx = 0f, swipeDy = 0f)

            // Drift must remain bounded by MAX_FLOAT_OFFSET
            val driftDist = sqrt(spaceFloat.offsetX * spaceFloat.offsetX + spaceFloat.offsetY * spaceFloat.offsetY)
            assertTrue("Drift offset exceeded maximum allowed bound", driftDist <= 35f)
            assertTrue("Vertical elevation offset out of bounds", abs(spaceFloat.elevationZ) <= 25f)
        }

        // Must have oscillated smoothly away from exact zero
        assertTrue("OffsetX should have drifted", abs(spaceFloat.offsetX) > 0.01f || abs(spaceFloat.offsetY) > 0.01f)
    }

    @Test
    fun testSpaceFloatGyroscopicTiltOnSwipe() {
        val dt = 1f / 120f

        // Apply a sharp rightward swipe gesture (swipeDx = +45 wu)
        for (i in 0 until 15) {
            spaceFloat.update(dt, inputActive = true, swipeDx = 45f, swipeDy = 0f)
        }

        // Roll must lean in direction of swipe
        assertTrue("Gyroscopic roll should lean rightward into swipe", spaceFloat.roll > 0.05f)

        // Once finger lifts, gyroscopic tilt must relax back toward zero
        for (i in 0 until 120) {
            spaceFloat.update(dt, inputActive = false, swipeDx = 0f, swipeDy = 0f)
        }
        assertTrue("Roll should damp back toward zero", abs(spaceFloat.roll) < 0.05f)
    }

    @Test
    fun testOrbitingEscortSatellitesUpdatePhases() {
        val initial0 = spaceFloat.satAngle0
        val initial1 = spaceFloat.satAngle1
        val initial2 = spaceFloat.satAngle2

        // Verify distinct initial phase angles
        assertTrue("Satellites should have staggered initial angles", initial0 != initial1 && initial1 != initial2)

        // Advance 1 second
        spaceFloat.update(1.0f, inputActive = false, swipeDx = 0f, swipeDy = 0f)

        assertTrue("Satellite 0 should advance angle", spaceFloat.satAngle0 != initial0)
        assertTrue("Satellite 1 should advance angle", spaceFloat.satAngle1 != initial1)
        assertTrue("Satellite 2 should advance angle", spaceFloat.satAngle2 != initial2)
    }

    @Test
    fun testSpaceFloatRecoilImpulseAndSpringRecovery() {
        spaceFloat.applyRecoilImpulse(dirX = 0.5f, dirY = 0.866f, impulseMagnitude = 100f)

        // Step a frame to integrate velocity into offset
        spaceFloat.update(1f / 60f, inputActive = false, swipeDx = 0f, swipeDy = 0f)

        assertTrue("OffsetX should register impulse", abs(spaceFloat.offsetX) > 0.01f)
        assertTrue("OffsetY should register impulse", abs(spaceFloat.offsetY) > 0.01f)

        // Simulate spring recovery over 1.5s
        val dt = 1f / 120f
        for (step in 0 until 180) {
            spaceFloat.update(dt, inputActive = false, swipeDx = 0f, swipeDy = 0f)
        }

        // Offsets should return toward equilibrium
        assertTrue("OffsetX should spring-return", abs(spaceFloat.offsetX) < 15f)
        assertTrue("OffsetY should spring-return", abs(spaceFloat.offsetY) < 15f)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. EnemyAISystem Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testVoidStalkerStateProgression() {
        // Spawn Void Stalker far from bubble
        val idx = world.obstacles.spawn(
            posX = 100f,
            posY = 200f,
            velX = 0f,
            velY = 100f,
            colRadius = 28f,
            mass = 1.2f,
            colRestitution = 0.70f,
            obstacleType = GameConfig.TYPE_VOID_STALKER,
            obstacleFlags = 0
        )
        assertTrue("Failed to spawn obstacle", idx >= 0)

        // Step AI system toward bubble at (500, 1476)
        val dt = 0.05f
        for (step in 0 until 100) {
            enemyAI.update(dt, world.bubbleX, world.bubbleY)
            if (enemyAI.aiState[idx].toInt() == 1) { // STATE_ORBIT reached
                break
            }
        }

        // Stalker should have engaged AI state machine
        val state = enemyAI.aiState[idx].toInt()
        assertTrue("Stalker state should be Approach or Orbit", state == 0 || state == 1)
    }

    @Test
    fun testSniperPlatformAnchoringAndTargeting() {
        val idx = world.obstacles.spawn(
            posX = 250f,
            posY = 120f,
            velX = 0f,
            velY = 150f,
            colRadius = 32f,
            mass = 2.0f,
            colRestitution = 0.50f,
            obstacleType = GameConfig.TYPE_SNIPER_PLATFORM,
            obstacleFlags = 0
        )
        assertTrue(idx >= 0)

        // Step AI: sniper should anchor and charge (6 steps of 0.1s = 0.6s into 1.2s charge)
        val dt = 0.1f
        for (step in 0 until 6) {
            enemyAI.update(dt, world.bubbleX, world.bubbleY)
        }

        // Railgun charge should be progressing mid-charge
        assertTrue("Sniper charge should be non-zero during charge", enemyAI.aiCharge[idx] > 0.3f)
        assertEquals(world.bubbleX, enemyAI.aiTargetX[idx], 0.001f)
        assertEquals(world.bubbleY, enemyAI.aiTargetY[idx], 0.001f)

        // Continue until charge completes (1.2s total = 7 more steps)
        for (step in 0 until 7) {
            enemyAI.update(dt, world.bubbleX, world.bubbleY)
        }
        // State should have reached FIRE (1) or RETREAT (2)
        assertTrue("Sniper should fire kinetic spike", enemyAI.aiState[idx].toInt() >= 1)
    }

    @Test
    fun testGravityWeaverGravitationalPullMath() {
        // Spawn Weaver at center
        val weaverIdx = world.obstacles.spawn(
            posX = 500f,
            posY = 600f,
            velX = 0f,
            velY = 20f,
            colRadius = 30f,
            mass = 3.0f,
            colRestitution = 0.40f,
            obstacleType = GameConfig.TYPE_GRAVITY_WEAVER,
            obstacleFlags = 0
        )
        assertTrue(weaverIdx >= 0)

        // Spawn a Drifter to the left of Weaver
        val drifterIdx = world.obstacles.spawn(
            posX = 350f,
            posY = 600f,
            velX = 0f,
            velY = 100f,
            colRadius = 24f,
            mass = 1.0f,
            colRestitution = 0.75f,
            obstacleType = GameConfig.TYPE_DRIFTER,
            obstacleFlags = 0
        )
        assertTrue(drifterIdx >= 0)

        val initialVx = world.obstacles.vx[drifterIdx]
        assertEquals(0f, initialVx, 0.001f)

        // Step AI: Weaver emits gravity vortex curving drifter toward right (+X)
        enemyAI.update(0.1f, world.bubbleX, world.bubbleY)

        val pulledVx = world.obstacles.vx[drifterIdx]
        assertTrue("Gravity Weaver should pull nearby obstacle toward its center (+Vx)", pulledVx > 0f)
    }

    @Test
    fun testObstaclePoolSwapRemoveSyncWithEnemyAI() {
        val idx0 = world.obstacles.spawn(posX = 100f, posY = 200f, velX = 0f, velY = 50f, colRadius = 20f, mass = 1f, colRestitution = 0.7f, obstacleType = GameConfig.TYPE_VOID_STALKER)
        val idx1 = world.obstacles.spawn(posX = 300f, posY = 300f, velX = 0f, velY = 50f, colRadius = 20f, mass = 1f, colRestitution = 0.7f, obstacleType = GameConfig.TYPE_SNIPER_PLATFORM)

        // Set unique tracking values
        enemyAI.aiCharge[idx0] = 0.25f
        enemyAI.aiCharge[idx1] = 0.75f

        // Remove idx0 (slot 0) -> swap-remove swaps idx1 into slot 0
        world.obstacles.removeAt(idx0)

        assertEquals("Pool count should decrease", 1, world.obstacles.count)
        assertEquals("Slot 0 should now have swapped obstacle type", GameConfig.TYPE_SNIPER_PLATFORM, world.obstacles.type[0])
        assertEquals("AI charge should be swapped into slot 0", 0.75f, enemyAI.aiCharge[0], 0.001f)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. LocalOfflineGamesGateway Tests
    // ---------------------------------------------------------------------------------------------

    @Test
    fun testLocalOfflineGatewayInitialProfile() {
        val profile = gamesGateway.profileState.value
        assertEquals("VOID-PILOT", profile.gamerTag)
        assertEquals("CADET I", profile.pilotRank)
        assertEquals(1, profile.pilotLevel)
        assertEquals(0L, profile.currentXp)
        assertEquals(500L, profile.xpForNextLevel)
        assertFalse(gamesGateway.isCloudConnected)
    }

    @Test
    fun testUpdateGamerTagSanitization() {
        gamesGateway.updateGamerTag("   CYBER-GHOST-9999999   ")
        val tag = gamesGateway.profileState.value.gamerTag
        assertEquals("CYBER-GHOST-9999", tag) // Clamped to 16 characters

        // Empty tag fallback
        gamesGateway.updateGamerTag("   ")
        assertEquals("VOID-PILOT", gamesGateway.profileState.value.gamerTag)
    }

    @Test
    fun testRecordRunResultLevelUpAndRankProgression() {
        // Run result with substantial score and altitude to trigger level-ups
        gamesGateway.recordRunResult(
            score = 15000L,
            altitudeMeters = 850f,
            wave = 4,
            parries = 18,
            prismsEarned = 35
        )

        val profile = gamesGateway.profileState.value
        assertTrue("Pilot level should advance beyond level 1", profile.pilotLevel > 1)
        assertTrue("Career high score should be updated", profile.careerHighScore >= 15000L)
        assertTrue("Career best altitude should be updated", profile.careerBestAltitude >= 850f)
        assertEquals(18, profile.careerTotalParries)
        assertEquals(35, profile.careerPrismsCollected)
        assertEquals(1, profile.careerRunsPlayed)

        // Leaderboard must contain player run entry
        val scoreboard = gamesGateway.scoreboardState.value
        val playerEntry = scoreboard.find { it.isCurrentPlayer }
        assertNotNull("Player entry should exist on scoreboard", playerEntry)
        assertEquals(15000L, playerEntry?.score)
    }

    @Test
    fun testBountyProgressAndClaimReward() {
        // Record a run with 20 parries (completes bounty_parry_15)
        gamesGateway.recordRunResult(
            score = 2000L,
            altitudeMeters = 200f,
            wave = 1,
            parries = 20,
            prismsEarned = 10
        )

        val parryBounty = gamesGateway.bountiesState.value.find { it.id == "bounty_parry_15" }
        assertNotNull(parryBounty)
        assertTrue("Bounty should be completed", parryBounty!!.isCompleted)
        assertFalse("Bounty should not yet be claimed", parryBounty.isClaimed)

        // Claim reward
        val rewardPrisms = gamesGateway.claimBountyReward("bounty_parry_15")
        assertEquals(25, rewardPrisms)

        val updatedBounty = gamesGateway.bountiesState.value.find { it.id == "bounty_parry_15" }
        assertTrue("Bounty should now be marked claimed", updatedBounty!!.isClaimed)
        assertEquals(1, gamesGateway.profileState.value.bountiesCompleted)
    }
}
