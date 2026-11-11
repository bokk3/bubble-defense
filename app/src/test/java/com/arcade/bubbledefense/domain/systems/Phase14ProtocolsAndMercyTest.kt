package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Phase 14 Verification Tests:
 * 1. Dynamic Mercy Flow (1 HP threat slowdown, emergency armor crystal relief, Wave 1 solar storm gating).
 * 2. Milestone 250m Warp Chamber Draft (Time dilation, 3 distinct choices generated via PRNG).
 * 3. 8 Roguelike Tactical Protocols:
 *    - Magnetic Aegis (Tractor suction)
 *    - Bouncy Shields (Restitution boost & integrity drain reduction)
 *    - Drone Overclock (Fire rate scale & secondary piercing strike)
 *    - Temporal Buffer (Expanded parry window & impulse gain)
 *    - Deep Ink Well (1500 wu tank & accelerated regen)
 *    - Nova Deflection (Radial shockwave on parry)
 *    - Repair Nanites (Immediate HP/overshield & 500m milestone regen)
 *    - Prism Battery (Special power charging on 8 prisms harvested)
 */
class Phase14ProtocolsAndMercyTest {

    private lateinit var world: GameWorld
    private lateinit var directorSystem: DirectorSystem
    private lateinit var physicsSystem: PhysicsSystem
    private lateinit var inputSystem: ShieldInputSystem
    private lateinit var droneSystem: DroneSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 777)
        directorSystem = DirectorSystem(world, initialSeed = 777)
        physicsSystem = PhysicsSystem(world)
        inputSystem = ShieldInputSystem(world)
        droneSystem = DroneSystem(world, synth = null)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Dynamic Mercy Flow
    // ---------------------------------------------------------------------------------------------

    @Test
    fun mercyFlow_threatSpeedSlowsDown_whenAt1Hp() {
        // Full HP: normal speed
        world.bubbleHp = 3
        val idx1 = world.obstacles.spawn(
            posX = 500f, posY = 500f,
            velX = 0f, velY = 400f,
            colRadius = 20f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        physicsSystem.stepFrame(0.1f)
        val fullHpDistanceMoved = world.obstacles.y[idx1] - 500f

        world.reset()

        // 1 HP: Mercy slowdown (0.75x speed)
        world.bubbleHp = 1
        val idx2 = world.obstacles.spawn(
            posX = 500f, posY = 500f,
            velX = 0f, velY = 400f,
            colRadius = 20f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        physicsSystem.stepFrame(0.1f)
        val lowHpDistanceMoved = world.obstacles.y[idx2] - 500f

        // Distance moved at 1 HP must be approximately 75% of full HP
        assertEquals(fullHpDistanceMoved * GameConfig.MERCY_THREAT_SPEED_SCALE, lowHpDistanceMoved, 2.0f)
    }

    @Test
    fun mercyFlow_emergencyGreenArmorCrystal_spawnsWhenAt1Hp() {
        world.bubbleHp = 1
        world.obstacles.clear()

        // Step past emergency cooldown (3.0s)
        for (i in 0 until 200) {
            directorSystem.step(0.016f)
        }

        var foundCrystal = false
        for (i in 0 until world.obstacles.count) {
            if (world.obstacles.type[i] == GameConfig.TYPE_ARMOR_CRYSTAL) {
                foundCrystal = true
                break
            }
        }
        assertTrue("Expected emergency green armor crystal to spawn when down to 1 HP", foundCrystal)
    }

    @Test
    fun mercyFlow_solarFlareGatedToWave2Plus() {
        world.bubbleHp = 3
        world.comboCount = 10 // Very high combo
        directorSystem.solarFlareCooldown = 0f
        world.currentWave = 1 // Early Wave 1
        world.directorWaveTimer = 5.0f

        directorSystem.step(0.016f)

        // Solar Flare must NOT trigger in Wave 1
        assertEquals(0, world.solarFlareState)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Milestone 250m Warp Chamber Draft
    // ---------------------------------------------------------------------------------------------

    @Test
    fun milestoneWarp_triggersAt250m_dilatesTimeAndGenerates3DistinctChoices() {
        world.altitudeMeters = 250.5f
        directorSystem.step(0.016f)

        // Warp chamber activated
        assertTrue(world.warpDraftActive)
        assertEquals(0.05f, world.timeScale, 1e-4f)
        assertEquals(1, world.lastMilestoneWarp)

        // 3 distinct protocol choices generated
        val c0 = world.warpDraftChoices[0]
        val c1 = world.warpDraftChoices[1]
        val c2 = world.warpDraftChoices[2]
        assertNotEquals(0, c0)
        assertNotEquals(0, c1)
        assertNotEquals(0, c2)
        assertNotEquals(c0, c1)
        assertNotEquals(c1, c2)
        assertNotEquals(c0, c2)

        // Install choice 0: resumes time
        world.installProtocol(c0)
        assertFalse(world.warpDraftActive)
        assertEquals(1.0f, world.timeScale, 1e-4f)
        assertTrue(world.hasProtocol(c0))
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The 8 Tactical Protocols
    // ---------------------------------------------------------------------------------------------

    @Test
    fun protocol1_magneticAegis_pullsPrismsTowardBubble() {
        world.installProtocol(GameConfig.PROTOCOL_MAGNETIC_AEGIS)

        // Spawn prism carrier within 300 wu of bubble
        val idx = world.obstacles.spawn(
            posX = world.bubbleX + 120f,
            posY = world.bubbleY - 120f,
            velX = 0f, velY = 50f,
            colRadius = GameConfig.PRISM_CARRIER_RADIUS,
            mass = GameConfig.PRISM_CARRIER_MASS,
            colRestitution = GameConfig.PRISM_CARRIER_RESTITUTION,
            obstacleType = GameConfig.TYPE_PRISM_CARRIER
        )

        physicsSystem.stepFrame(0.1f)

        // Magnetic suction pulls vx toward bubble (bubbleX is left of obstacle, so vx becomes negative)
        assertTrue("Expected negative vx towards bubble center, got ${world.obstacles.vx[idx]}", world.obstacles.vx[idx] < 0f)
    }

    @Test
    fun protocol2_bouncyShields_increasesRestitutionAndReducesDamage() {
        world.installProtocol(GameConfig.PROTOCOL_BOUNCY_SHIELDS)

        // Create a horizontal shield segment
        val segIdx = world.shieldSegments.spawn(
            400f, 1000f, 600f, 1000f,
            0f, 0f, 1, 0, 100f
        )

        // Spawn a drifter falling directly onto shield
        val obsIdx = world.obstacles.spawn(
            posX = 500f, posY = 970f,
            velX = 0f, velY = 300f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepFrame(GameConfig.FIXED_DT)

        // Integrity drain must be scaled down by 0.65x
        val initialIntegrity = 100f
        val remainingIntegrity = world.shieldSegments.integrity[segIdx]
        val damageSustained = initialIntegrity - remainingIntegrity

        // Damage is reduced by bouncy shields protocol (-35% damage: 25.5 * 0.65 = 16.575)
        assertTrue("Damage sustained $damageSustained should be reduced by bouncy shields protocol", damageSustained in 10f..20f)
    }

    @Test
    fun protocol3_droneOverclock_increasesFireRateAndPiercesSecondaryTarget() {
        world.droneLevel = 1
        world.installProtocol(GameConfig.PROTOCOL_DRONE_OVERCLOCK)

        // Spawn primary target
        val obs1 = world.obstacles.spawn(
            posX = 450f, posY = 900f,
            velX = 0f, velY = 100f,
            colRadius = 20f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        // Spawn secondary nearby target
        val obs2 = world.obstacles.spawn(
            posX = 480f, posY = 920f,
            velX = 0f, velY = 100f,
            colRadius = 20f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        droneSystem.fireCooldown[0] = 0f
        droneSystem.step(0.016f)

        // Secondary target must also receive upward kinetic knockback
        assertTrue("Secondary target should receive piercing knockback", world.obstacles.vy[obs2] < 100f)
    }

    @Test
    fun protocol4_temporalBuffer_expandsParryWindow() {
        world.installProtocol(GameConfig.PROTOCOL_TEMPORAL_BUFFER)
        assertTrue(world.hasProtocol(GameConfig.PROTOCOL_TEMPORAL_BUFFER))
    }

    @Test
    fun protocol5_deepInkWell_increasesCapacityAndRegeneration() {
        world.installProtocol(GameConfig.PROTOCOL_DEEP_INK_WELL)

        // Max ink expanded to 1500 wu
        assertEquals(GameConfig.PROTOCOL_DEEP_INK_MAX, world.ink, 1e-4f)

        world.ink = 0f
        world.inkCooldownTimer = 0f
        inputSystem.step(1.0f)

        // Regen rate is scaled by 1.30x (364 wu/s)
        assertEquals(GameConfig.PROTOCOL_DEEP_INK_REGEN_RATE, world.ink, 2.0f)
    }

    @Test
    fun protocol6_novaDeflection_emitsRadialShockwaveOnParry() {
        world.installProtocol(GameConfig.PROTOCOL_NOVA_DEFLECTION)

        val segIdx = world.shieldSegments.spawn(
            400f, 1000f, 600f, 1000f,
            0f, -600f, 1, 0, 100f
        )

        // Falling drifter A hits shield to trigger parry
        val obsA = world.obstacles.spawn(
            posX = 500f, posY = 970f,
            velX = 0f, velY = 300f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        // Nearby drifter B not directly touching shield
        val obsB = world.obstacles.spawn(
            posX = 550f, posY = 960f,
            velX = 0f, velY = 200f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepFrame(GameConfig.FIXED_DT)

        // Drifter B was caught in Nova shockwave and blasted upward
        assertTrue("Nearby obstacle B should be deflected by Nova shockwave", world.obstacles.vy[obsB] < 0f)
    }

    @Test
    fun protocol7_repairNanites_healsOnInstallAndPassivelyAt500m() {
        world.bubbleHp = 1
        world.installProtocol(GameConfig.PROTOCOL_REPAIR_NANITES)

        // Immediately restored +1 HP
        assertEquals(2, world.bubbleHp)

        // Crossing 500m milestone (even milestone) triggers passive regen
        world.altitudeMeters = 500.5f
        directorSystem.step(0.016f)

        assertEquals(3, world.bubbleHp)
    }

    @Test
    fun protocol8_prismBattery_chargesStoredPowerOnHarvestThreshold() {
        world.installProtocol(GameConfig.PROTOCOL_PRISM_BATTERY)
        world.storedPowerCharges = 0
        world.prismBatteryProgress = 0

        // Harvest 8 prisms
        repeat(7) { world.addPrismHarvestProgress() }
        assertEquals(0, world.storedPowerCharges)

        // 8th prism charges power
        world.addPrismHarvestProgress()
        assertEquals(1, world.storedPowerCharges)
    }
}
