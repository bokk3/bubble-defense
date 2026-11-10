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

        // Test Harvest phase: cycleTime = 15s in [14s, 20s) (phi = 0.35)
        world.reset()
        world.gameTime = 0f
        world.directorWaveTimer = 15f
        world.directorThreatBudget = 0f
        world.directorSpawnCooldown = 999f

        director.step(dt)
        val harvestBudgetGain = world.directorThreatBudget

        // Crunch rate (phi=1.4) should be exactly 4x higher than harvest rate (phi=0.35)
        assertEquals(1.4f / 0.35f, crunchBudgetGain / harvestBudgetGain, 0.05f)
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
