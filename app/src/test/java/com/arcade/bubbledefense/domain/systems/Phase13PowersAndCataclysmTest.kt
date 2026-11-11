package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 13 Verification Tests:
 * 1. Interactive Green Armor Crystals (Tapping restores HP or builds Overshield Aegis, drone immunity).
 * 2. Solar Flare & Invasive Meteorite Shower (Dynamic trigger, drone EMP disruption, ink drag, perk stripping).
 * 3. 3 Stored Special Powers (Chrono Warp dilation, Supernova EMP blast, Plasma Lance radiation purge, parry charging).
 */
class Phase13PowersAndCataclysmTest {

    private lateinit var world: GameWorld
    private lateinit var inputSystem: ShieldInputSystem
    private lateinit var directorSystem: DirectorSystem
    private lateinit var physicsSystem: PhysicsSystem
    private lateinit var droneSystem: DroneSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 4242)
        inputSystem = ShieldInputSystem(world)
        directorSystem = DirectorSystem(world, initialSeed = 4242)
        physicsSystem = PhysicsSystem(world)
        droneSystem = DroneSystem(world, synth = null)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Green Armor Crystal Mechanics
    // ---------------------------------------------------------------------------------------------

    @Test
    fun armorCrystal_tapRestoresBubbleHp_whenDamaged() {
        world.bubbleHp = 2
        world.overshieldHp = 0

        // Spawn armor crystal in valid tap window (Y in 450..1400)
        val crystalIdx = world.obstacles.spawn(
            posX = 500f, posY = 800f,
            velX = 0f, velY = 150f,
            colRadius = GameConfig.ARMOR_CRYSTAL_RADIUS,
            mass = GameConfig.ARMOR_CRYSTAL_MASS,
            colRestitution = GameConfig.ARMOR_CRYSTAL_RESTITUTION,
            obstacleType = GameConfig.TYPE_ARMOR_CRYSTAL
        )
        assertTrue(crystalIdx >= 0)

        // Player taps directly on crystal with ACTION_DOWN
        world.inputRing.push(500f, 800f, System.nanoTime(), 1, InputRingBuffer.ACTION_DOWN)
        inputSystem.step(0.016f)

        // Core HP restored, overshield remains 0, crystal removed, score awarded
        assertEquals(3, world.bubbleHp)
        assertEquals(0, world.overshieldHp)
        assertEquals(0, world.obstacles.count)
        assertTrue(world.score >= 300)
    }

    @Test
    fun armorCrystal_tapGrantsOvershield_whenAtMaxHp() {
        world.bubbleHp = 3
        world.overshieldHp = 0

        world.obstacles.spawn(
            posX = 400f, posY = 750f,
            velX = 0f, velY = 150f,
            colRadius = GameConfig.ARMOR_CRYSTAL_RADIUS,
            mass = GameConfig.ARMOR_CRYSTAL_MASS,
            colRestitution = GameConfig.ARMOR_CRYSTAL_RESTITUTION,
            obstacleType = GameConfig.TYPE_ARMOR_CRYSTAL
        )

        world.inputRing.push(400f, 750f, System.nanoTime(), 1, InputRingBuffer.ACTION_DOWN)
        inputSystem.step(0.016f)

        // Overshield Aegis Plate activated!
        assertEquals(3, world.bubbleHp)
        assertEquals(1, world.overshieldHp)
        assertEquals(0, world.obstacles.count)
    }

    @Test
    fun armorCrystal_ignoredBySentinelDrones() {
        world.droneLevel = 2 // Dual overcharged sentinels

        // Spawn only an armor crystal
        world.obstacles.spawn(
            posX = 500f, posY = 800f,
            velX = 0f, velY = 100f,
            colRadius = GameConfig.ARMOR_CRYSTAL_RADIUS,
            mass = GameConfig.ARMOR_CRYSTAL_MASS,
            colRestitution = GameConfig.ARMOR_CRYSTAL_RESTITUTION,
            obstacleType = GameConfig.TYPE_ARMOR_CRYSTAL
        )

        droneSystem.step(0.016f)

        // Drones must never target armor crystals
        assertFalse(droneSystem.hasTarget[0])
        assertFalse(droneSystem.hasTarget[1])
        assertEquals(0f, droneSystem.beamTimer[0], 1e-6f)
        assertEquals(0f, droneSystem.beamTimer[1], 1e-6f)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Solar Flare Dynamics & Cataclysm
    // ---------------------------------------------------------------------------------------------

    @Test
    fun solarFlare_triggersWhenComboHigh() {
        world.comboCount = GameConfig.SOLAR_FLARE_TRIGGER_COMBO
        directorSystem.solarFlareCooldown = 0f
        world.currentWave = 1
        world.directorWaveTimer = 2.0f // In CRUNCH phase

        directorSystem.step(0.016f)

        // Entered Phase 1: Warning Telegraph
        assertEquals(1, world.solarFlareState)
        assertTrue(world.solarFlareTimer > 0f)
        assertTrue(world.droneDisruptedTimer > 0f)
    }

    @Test
    fun solarFlare_droneDisruptedByEMP_stunsFiring() {
        world.droneLevel = 2
        world.droneDisruptedTimer = 5.0f

        // Spawn valid hostile threat
        world.obstacles.spawn(
            posX = 500f, posY = 1000f,
            velX = 0f, velY = 100f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        droneSystem.step(0.016f)

        // Drones cannot lock on or fire while EMP disrupted
        assertFalse(droneSystem.hasTarget[0])
        assertFalse(droneSystem.hasTarget[1])
        assertTrue(world.droneDisruptedTimer < 5.0f) // Timer ticks down
    }

    @Test
    fun solarFlare_stormPhase_reducesInkRegenerationRate() {
        world.solarFlareState = 2 // STORM phase
        world.ink = 0f
        world.inkCooldownTimer = 0f

        inputSystem.step(1.0f)

        // Normal regen is INK_REGEN_RATE * 1.0f; during storm it must be scaled by SOLAR_FLARE_INK_SLOW_RATIO
        val expectedInk = GameConfig.INK_REGEN_RATE * GameConfig.SOLAR_FLARE_INK_SLOW_RATIO * 1.0f
        assertEquals(expectedInk, world.ink, 1.0f)
    }

    @Test
    fun meteorite_breaksOvershieldFirst_protectingCoreHp() {
        world.bubbleHp = 3
        world.overshieldHp = 1
        world.comboMultiplier = 3.5f

        // Place meteorite intersecting bubble perimeter
        world.obstacles.spawn(
            posX = world.bubbleX,
            posY = world.bubbleY - (world.bubbleRadius + GameConfig.METEORITE_RADIUS - 5f),
            velX = 0f, velY = 300f,
            colRadius = GameConfig.METEORITE_RADIUS,
            mass = GameConfig.METEORITE_MASS,
            colRestitution = GameConfig.METEORITE_RESTITUTION,
            obstacleType = GameConfig.TYPE_METEORITE
        )

        physicsSystem.stepFrame(GameConfig.FIXED_DT)

        // Overshield was broken, but core HP was completely protected!
        assertEquals(0, world.overshieldHp)
        assertEquals(3, world.bubbleHp)
        // Meteorite is bounced away or deflected, bubble granted brief invulnerability
        assertTrue(world.bubbleInvulnTimer > 0f)
    }

    @Test
    fun damageBreach_stripsComboMultiplier() {
        world.bubbleHp = 3
        world.overshieldHp = 0
        world.comboMultiplier = 4.0f
        world.comboCount = 8

        // Place hostile drifter intersecting bubble perimeter
        world.obstacles.spawn(
            posX = world.bubbleX,
            posY = world.bubbleY - (world.bubbleRadius + GameConfig.DRIFTER_RADIUS - 5f),
            velX = 0f, velY = 300f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepFrame(GameConfig.FIXED_DT)

        // Core HP took damage and combo perks were stripped
        assertEquals(2, world.bubbleHp)
        assertEquals(1.0f, world.comboMultiplier, 1e-4f)
        assertEquals(0, world.comboCount)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Stored Special Powers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun powers_parryAccumulatorGrantsChargesUpToMax3() {
        world.storedPowerCharges = 0
        world.parriesSinceLastCharge = 0

        // 3 parries
        repeat(3) { world.addParryPowerProgress() }
        assertEquals(0, world.storedPowerCharges)

        // 4th parry grants 1 charge
        world.addParryPowerProgress()
        assertEquals(1, world.storedPowerCharges)

        // 12 more parries -> max 3 charges capped
        repeat(12) { world.addParryPowerProgress() }
        assertEquals(3, world.storedPowerCharges)

        // Extra parries do not exceed capacity
        world.addParryPowerProgress()
        assertEquals(3, world.storedPowerCharges)
    }

    @Test
    fun powers_chronoWarp_slowsPhysicsTimeScale() {
        world.storedPowerCharges = 1
        val activated = world.triggerPower(GameConfig.POWER_CHRONO_WARP)

        assertTrue(activated)
        assertEquals(0, world.storedPowerCharges)
        assertEquals(GameConfig.POWER_CHRONO_WARP, world.activePowerType)
        assertEquals(GameConfig.POWER_CHRONO_TIMESCALE, world.timeScale, 1e-4f)

        // Physics steps active powers
        physicsSystem.stepFrame(GameConfig.FIXED_DT)
        assertTrue(world.activePowerTimer < GameConfig.POWER_CHRONO_DURATION)

        // Advance through duration
        world.activePowerTimer = 0.001f
        physicsSystem.stepFrame(0.010f)

        // Power expired: returned to normal time
        assertEquals(GameConfig.POWER_NONE, world.activePowerType)
        assertEquals(1.0f, world.timeScale, 1e-4f)
    }

    @Test
    fun powers_supernovaEMP_blastsObstaclesWithUpwardImpulse() {
        world.storedPowerCharges = 1

        val drifterIdx = world.obstacles.spawn(
            posX = 500f, posY = 600f,
            velX = 0f, velY = 200f,
            colRadius = 30f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        val needleIdx = world.obstacles.spawn(
            posX = 500f, posY = 800f,
            velX = 0f, velY = 500f,
            colRadius = GameConfig.NEEDLE_RADIUS, mass = 0.3f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_NEEDLE
        )

        val activated = world.triggerPower(GameConfig.POWER_SUPERNOVA_EMP)
        assertTrue(activated)

        // Needle was vaporized, drifter received massive upward velocity
        assertEquals(1, world.obstacles.count)
        assertEquals(GameConfig.TYPE_DRIFTER, world.obstacles.type[0])
        assertEquals(-GameConfig.POWER_EMP_BLAST_IMPULSE, world.obstacles.vy[0], 1e-4f)
        assertTrue(world.empShockwaveTimer > 0f)
    }

    @Test
    fun powers_plasmaLance_clearsSolarEMPDisruption() {
        world.storedPowerCharges = 1
        world.solarFlareState = 2
        world.droneDisruptedTimer = 5.0f

        val activated = world.triggerPower(GameConfig.POWER_PLASMA_LANCE)
        assertTrue(activated)

        // Twin lance incinerator clears solar drone EMP disruption instantly!
        assertEquals(0f, world.droneDisruptedTimer, 1e-6f)
        assertEquals(GameConfig.POWER_PLASMA_LANCE, world.activePowerType)
    }

    @Test
    fun powers_cannotTriggerWhenChargesDepletedOrAlreadyActive() {
        world.storedPowerCharges = 0
        assertFalse(world.triggerPower(GameConfig.POWER_CHRONO_WARP))

        world.storedPowerCharges = 2
        assertTrue(world.triggerPower(GameConfig.POWER_CHRONO_WARP))

        // Cannot trigger another power while Chrono Warp is running
        assertFalse(world.triggerPower(GameConfig.POWER_SUPERNOVA_EMP))
        assertEquals(1, world.storedPowerCharges) // Charge not wasted
    }
}
