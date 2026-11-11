package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Phase 15 Verification Tests:
 * 1. Frictionless Protocol Chance Rolls (0ms slowdown, instant milestone & combo unlocks).
 * 2. In-Run Sentinel Drone Deployment & Overdrive Barrages.
 * 3. The Hypernova Ultimate ("ULT") Multi-Stage Cataclysm (Implosion, Hyper-Beam, Overdrive).
 * 4. Zero-allocation verification across all new systems.
 */
class Phase15UltAndDronesTest {

    private lateinit var world: GameWorld
    private lateinit var directorSystem: DirectorSystem
    private lateinit var physicsSystem: PhysicsSystem
    private lateinit var droneSystem: DroneSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 10101)
        directorSystem = DirectorSystem(world, initialSeed = 10101)
        physicsSystem = PhysicsSystem(world)
        droneSystem = DroneSystem(world, synth = null)
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Frictionless Protocol Chance Rolls
    // ---------------------------------------------------------------------------------------------

    @Test
    fun protocols_milestoneRoll_instantlyInstallsWithoutModalOrTimeDilation() {
        world.altitudeMeters = 250.5f
        assertEquals(0, world.activeProtocolsMask)
        assertEquals(1.0f, world.timeScale, 1e-4f)

        directorSystem.step(0.016f)

        // Milestone crossed: 100% instant protocol roll
        assertEquals(1, world.lastMilestoneWarp)
        assertFalse("Warp draft modal must NOT be active", world.warpDraftActive)
        assertEquals("Time scale must remain 1.0f (no game slowdown)", 1.0f, world.timeScale, 1e-4f)
        assertNotEquals("A protocol should be automatically unlocked", 0, world.activeProtocolsMask)
    }

    @Test
    fun protocols_comboMilestoneRoll_advancesMilestoneTracker() {
        world.comboCount = 5
        directorSystem.step(0.016f)

        assertEquals(1, world.lastComboMilestoneRolled)

        world.comboCount = 10
        directorSystem.step(0.016f)
        assertEquals(2, world.lastComboMilestoneRolled)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. In-Run Sentinel Drone Deployment & Overdrive
    // ---------------------------------------------------------------------------------------------

    @Test
    fun drones_inRunScramble_deploysLeftDrone_freeFirst() {
        world.droneLevel = 0
        world.freeDroneDeploymentAvailable = true
        world.prismsCollected = 0

        assertTrue(world.canScrambleDrone())
        val success = world.scrambleDrone()

        assertTrue(success)
        assertEquals(1, world.droneLevel)
        assertFalse(world.freeDroneDeploymentAvailable)
        assertTrue(world.droneArrivalAnimTimer > 0f)
        assertEquals(0, world.prismsCollected) // First deployment was free
    }

    @Test
    fun drones_inRunScramble_deploysRightDrone_costs12Prisms() {
        world.droneLevel = 1
        world.freeDroneDeploymentAvailable = false
        world.prismsCollected = 20

        assertTrue(world.canScrambleDrone())
        val success = world.scrambleDrone()

        assertTrue(success)
        assertEquals(2, world.droneLevel)
        assertEquals(8, world.prismsCollected) // 20 - 12
        assertTrue(world.droneArrivalAnimTimer > 0f)
    }

    @Test
    fun drones_overdriveBarrage_triggersWhenBothDronesActive() {
        world.droneLevel = 2
        world.droneOverdriveCooldown = 0f
        world.droneOverdriveTimer = 0f

        assertTrue(world.canScrambleDrone())
        val success = world.scrambleDrone()

        assertTrue(success)
        assertEquals(GameConfig.DRONE_OVERDRIVE_DURATION, world.droneOverdriveTimer, 1e-3f)
        assertEquals(GameConfig.DRONE_OVERDRIVE_COOLDOWN, world.droneOverdriveCooldown, 1e-3f)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. The Hypernova Ultimate ("ULT") System
    // ---------------------------------------------------------------------------------------------

    @Test
    fun ult_chargeAccumulatesFromCombatActions_andCapsAt100() {
        world.ultCharge = 0f

        world.addUltCharge(GameConfig.ULT_CHARGE_PARRY)
        assertEquals(GameConfig.ULT_CHARGE_PARRY, world.ultCharge, 1e-4f)

        world.addUltCharge(GameConfig.ULT_CHARGE_DRONE_KILL)
        assertEquals(GameConfig.ULT_CHARGE_PARRY + GameConfig.ULT_CHARGE_DRONE_KILL, world.ultCharge, 1e-4f)

        world.addUltCharge(200f)
        assertEquals(GameConfig.ULT_MAX_CHARGE, world.ultCharge, 1e-4f)
    }

    @Test
    fun ult_trigger_executesMultiStageCataclysm() {
        world.ultCharge = 100f
        world.ultActiveTimer = 0f

        val triggered = world.triggerUltimate()
        assertTrue(triggered)
        assertEquals(0f, world.ultCharge, 1e-4f)
        assertEquals(GameConfig.ULT_DURATION, world.ultActiveTimer, 1e-3f)
        assertEquals(1, world.ultStage)
        assertTrue(world.hitStopTimer > 0f)
    }

    @Test
    fun ult_stage2_vaporizesFragileHazards_andKnocksHeavyHazardsUpward() {
        // Spawn fragile Needle
        val needleIdx = world.obstacles.spawn(
            posX = 500f, posY = 800f,
            velX = 0f, velY = 300f,
            colRadius = 14f, mass = 0.5f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_NEEDLE
        )
        // Spawn heavy Crusher
        val crusherIdx = world.obstacles.spawn(
            posX = 500f, posY = 900f,
            velX = 0f, velY = 200f,
            colRadius = 36f, mass = 4.0f, colRestitution = 0.2f,
            obstacleType = GameConfig.TYPE_CRUSHER
        )

        // Advance Ult to Stage 2
        world.ultActiveTimer = GameConfig.ULT_DURATION - GameConfig.ULT_STAGE1_DURATION - 0.05f
        physicsSystem.stepFrame(0.016f)

        assertEquals(2, world.ultStage)
        // Only 1 obstacle remains (Needle was vaporized)
        assertEquals(1, world.obstacles.count)
        assertEquals(GameConfig.TYPE_CRUSHER, world.obstacles.type[0])
        // Crusher blasted upward with colossal recoil (vy <= -850)
        assertTrue("Crusher should have strong upward recoil <= -850f, got ${world.obstacles.vy[0]}", world.obstacles.vy[0] <= -850f)
    }

    @Test
    fun ult_stage3_refillsInkAndGrantsInvulnerability() {
        world.ink = 10f
        world.bubbleInvulnTimer = 0f

        // Advance Ult to Stage 3
        world.ultActiveTimer = 1.0f
        physicsSystem.stepFrame(0.016f)

        assertEquals(3, world.ultStage)
        assertEquals(GameConfig.INK_MAX, world.ink, 1e-4f)
        assertTrue("Invulnerability should be granted (~4.0s)", world.bubbleInvulnTimer >= 3.9f)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Zero-Allocation Hot Path Probe
    // ---------------------------------------------------------------------------------------------

    @Test
    fun zeroAllocation_probe_phase15Systems() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        world.droneLevel = 2
        world.droneOverdriveTimer = 3.0f
        world.ultActiveTimer = 2.0f
        world.activeProtocolsMask = 0xFF

        // Warm up JIT across all branches
        for (i in 0 until 4000) {
            physicsSystem.stepFrame(0.016f)
            directorSystem.step(0.016f)
            droneSystem.step(0.016f)
        }

        val before = AllocationProbe.allocatedBytes()

        val iterations = 5000
        for (i in 1..iterations) {
            physicsSystem.stepFrame(0.016f)
            directorSystem.step(0.016f)
            droneSystem.step(0.016f)
        }

        val after = AllocationProbe.allocatedBytes()
        val delta = after - before

        assertTrue("Phase 15 hot path must allocate 0 bytes per frame (delta = $delta bytes)", delta <= 128L)
    }

    private object AllocationProbe {
        private val bean: Any?
        private val counter: Method?

        init {
            var resolvedBean: Any? = null
            var resolvedCounter: Method? = null
            try {
                val factory = Class.forName("java.lang.management.ManagementFactory")
                val candidate = factory.getMethod("getThreadMXBean").invoke(null)
                val sunBean = Class.forName("com.sun.management.ThreadMXBean")
                if (sunBean.isInstance(candidate) &&
                    sunBean.getMethod("isThreadAllocatedMemorySupported").invoke(candidate) == true
                ) {
                    sunBean.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                        .invoke(candidate, true)
                    resolvedBean = candidate
                    resolvedCounter = sunBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                }
            } catch (ignored: Throwable) {
                resolvedBean = null
                resolvedCounter = null
            }
            bean = resolvedBean
            counter = resolvedCounter
        }

        val isAvailable: Boolean get() = counter != null

        @Suppress("DEPRECATION")
        fun allocatedBytes(): Long = (counter?.invoke(bean, Thread.currentThread().id) as? Long) ?: 0L

        fun warmUp() {
            for (i in 0 until 64) allocatedBytes()
        }
    }
}
