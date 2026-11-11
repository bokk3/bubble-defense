package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

class DirectorSystemTest {

    private lateinit var world: GameWorld
    private lateinit var director: DirectorSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 1337)
        director = DirectorSystem(world, initialSeed = 1337)
    }

    @Test
    fun prngDeterminism_sameSeedProducesIdenticalSequence() {
        val d1 = DirectorSystem(world, initialSeed = 999)
        val d2 = DirectorSystem(world, initialSeed = 999)

        for (i in 0 until 100) {
            assertEquals(d1.nextInt(), d2.nextInt())
            assertEquals(d1.nextFloat(), d2.nextFloat(), 1e-6f)
        }
    }

    @Test
    fun sawtoothBudgetRate_crunchAccumulatesFasterThanHarvest() {
        // Test Crunch phase: t = 2s, cycleTime = 2s < 14s (phi = 1.4)
        world.reset()
        world.gameTime = 0f
        world.directorWaveTimer = 0f
        world.directorThreatBudget = 0f
        world.directorSpawnCooldown = 999f // prevent spawn draining budget

        val dt = 1f
        director.step(dt)
        val crunchBudgetGain = world.directorThreatBudget

        // Test Harvest phase: cycleTime = 15s in [14s, 20s) (phi = 0.55)
        world.reset()
        world.gameTime = 0f
        world.directorWaveTimer = 15f
        world.directorThreatBudget = 0f
        world.directorSpawnCooldown = 999f

        director.step(dt)
        val harvestBudgetGain = world.directorThreatBudget

        // Crunch rate (phi=1.4) should be higher than harvest rate (phi=0.55)
        assertEquals(1.4f / 0.55f, crunchBudgetGain / harvestBudgetGain, 0.05f)
    }

    @Test
    fun threatBudgetCapping_neverExceedsMaxBudget() {
        world.reset()
        world.directorThreatBudget = GameConfig.MAX_THREAT_BUDGET - 0.5f
        world.directorSpawnCooldown = 999f

        // Step by 50 seconds of accumulation
        director.step(50f)
        assertEquals(GameConfig.MAX_THREAT_BUDGET, world.directorThreatBudget, 1e-4f)
    }

    @Test
    fun archetypeUnlocks_earlyGameOnlySpawnsDrifters() {
        world.reset()
        world.gameTime = 5f // t < NEEDLE_UNLOCK_TIME (20s)
        world.directorThreatBudget = 5f
        world.directorSpawnCooldown = 0f

        // Allow multiple spawns
        for (i in 0 until 5) {
            world.directorSpawnCooldown = 0f
            director.step(0.01f)
        }

        assertTrue(world.obstacles.count > 0)
        for (i in 0 until world.obstacles.count) {
            assertEquals(GameConfig.TYPE_DRIFTER, world.obstacles.type[i])
        }
    }

    @Test
    fun archetypeUnlocks_lateGameSpawnsDiverseThreats() {
        world.reset()
        world.gameTime = 100f // t > 75s (all archetypes unlocked: Drifter, Needle, Splitter, Crusher)
        world.directorThreatBudget = 8f
        world.directorSpawnCooldown = 0f

        var spawnedNeedleOrSplitterOrCrusher = false

        for (i in 0 until 50) {
            world.directorThreatBudget = 8f
            world.directorSpawnCooldown = 0f
            director.step(0.01f)

            for (o in 0 until world.obstacles.count) {
                val t = world.obstacles.type[o]
                if (t == GameConfig.TYPE_NEEDLE || t == GameConfig.TYPE_SPLITTER || t == GameConfig.TYPE_CRUSHER) {
                    spawnedNeedleOrSplitterOrCrusher = true
                    break
                }
            }
            if (spawnedNeedleOrSplitterOrCrusher) break
        }

        assertTrue("Expected late game to spawn advanced archetypes", spawnedNeedleOrSplitterOrCrusher)
    }

    @Test
    fun threatTelegraphs_registeredForHighSpeedArchetypes_andExpire() {
        world.reset()
        world.gameTime = 30f // Needles unlocked
        world.directorThreatBudget = 8f
        world.telegraphCount = 0

        // Force spawns until a Needle is spawned
        for (i in 0 until 100) {
            world.directorThreatBudget = 8f
            world.directorSpawnCooldown = 0f
            director.step(0.01f)
            if (world.telegraphCount > 0) break
        }

        assertTrue("Expected telegraph indicator to be registered", world.telegraphCount > 0)
        val initialCount = world.telegraphCount
        val timer = world.telegraphTimer[0]
        assertTrue(timer <= GameConfig.THREAT_TELEGRAPH_TIME)

        // Step by telegraph lifetime (0.35s + delta)
        director.step(GameConfig.THREAT_TELEGRAPH_TIME + 0.05f)
        assertEquals(0, world.telegraphCount)
    }

    @Test
    fun harvestPhase_generatesPrisms() {
        world.reset()
        world.prismsCollected = 0
        world.directorWaveTimer = 14.5f // in harvest valley [14s, 20s)
        world.directorSpawnCooldown = 999f

        // Step by 3 seconds of harvest time (exceeds 2.0s harvest threshold)
        director.step(3.0f)
        assertTrue("Expected soft currency prisms to be generated during harvest valley", world.prismsCollected > 0)
    }

    @Test
    fun prismCarrier_spawnsPeriodically() {
        world.reset()
        world.obstacles.clear()

        // Step past PRISM_CARRIER_SPAWN_INTERVAL (14.0s)
        director.step(GameConfig.PRISM_CARRIER_SPAWN_INTERVAL + 0.1f)

        var hasPrismCarrier = false
        for (i in 0 until world.obstacles.count) {
            if (world.obstacles.type[i] == GameConfig.TYPE_PRISM_CARRIER) {
                hasPrismCarrier = true
                break
            }
        }
        assertTrue("Expected a Prism Carrier obstacle to spawn after spawn interval", hasPrismCarrier)
    }

    @Test
    fun infiniteWaves_progressesBeyondWave1_andTriggersAnnouncements() {
        world.reset()
        assertEquals(1, world.currentWave)

        // Step to just before Wave 2 boundary (t = 19.9s)
        director.step(19.9f)
        assertEquals(1, world.currentWave)

        // Step across boundary into Wave 2 (dt = 0.2s)
        director.step(0.2f)
        assertEquals(2, world.currentWave)
        assertEquals("CRUNCH", world.wavePhaseName)
        assertTrue("Expected wave announcement timer to be active", world.waveAnnouncementTimer > 0f)

        // Step into Harvest valley of wave 2 (t = 20s + 15s = 35s)
        director.step(15f)
        assertEquals(2, world.currentWave)
        assertEquals("HARVEST", world.wavePhaseName)

        // Step to just before Wave 3 boundary (t = 39.9s)
        director.step(4.8f)
        assertEquals(2, world.currentWave)

        // Step across boundary into Wave 3 (dt = 0.2s)
        director.step(0.2f)
        assertEquals(3, world.currentWave)
        assertTrue("Expected wave announcement timer for Wave 3", world.waveAnnouncementTimer > 0f)
    }

    @Test
    fun infiniteWaves_dynamicScaling_scalesThreatRateWithWaveNumber() {
        world.reset()
        world.gameTime = 0f
        world.directorSpawnCooldown = 999f

        // Measure Wave 1 crunch accumulation
        world.directorThreatBudget = 0f
        world.directorWaveTimer = 0f
        director.step(1.0f)
        val wave1BudgetGain = world.directorThreatBudget

        // Fast-forward to Wave 5 (directorWaveTimer = 80s)
        world.reset()
        world.gameTime = 0f
        world.directorSpawnCooldown = 999f
        world.directorThreatBudget = 0f
        world.directorWaveTimer = 80f // Wave 5: 1 + 80/20 = 5

        director.step(1.0f)
        val wave5BudgetGain = world.directorThreatBudget

        // In wave 5, scale is 1.0 + 0.1 * 4 = 1.4x
        assertEquals(1.4f, wave5BudgetGain / wave1BudgetGain, 0.05f)
    }

    @Test
    fun zeroAllocation_hotPathProbe() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        world.reset()
        world.directorThreatBudget = 4f
        world.directorSpawnCooldown = 0.5f

        // Warm up JIT
        for (i in 0 until 2000) {
            director.step(GameConfig.FIXED_DT)
            if (world.obstacles.count >= 100) world.obstacles.clear()
        }

        val before = AllocationProbe.allocatedBytes()

        for (i in 0 until 5000) {
            director.step(GameConfig.FIXED_DT)
            if (world.obstacles.count >= 100) world.obstacles.clear()
        }

        val after = AllocationProbe.allocatedBytes()
        val allocated = after - before

        // Reflection invoke overhead is <= 128 bytes; 0 bytes per step
        assertTrue("Expected zero heap allocations in hot path, got $allocated bytes", allocated <= 128L)
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
