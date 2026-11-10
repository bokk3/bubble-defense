package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.systems.DirectorSystem
import com.arcade.bubbledefense.domain.systems.JuiceSystem
import com.arcade.bubbledefense.domain.systems.PhysicsSystem
import com.arcade.bubbledefense.domain.systems.ShieldInputSystem
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * Phase 5 120Hz Zero-Jank Macrobenchmark & Performance Audit.
 *
 * Verifies the system architecture against the non-functional requirements specified in
 * `docs/ARCHITECTURE.md` §10 and `kotlin-ux-architect/SKILL.md`:
 * 1. Zero heap allocation in per-step simulation loops (0ms GC pause overhead).
 * 2. Step execution time strictly below the 8.33ms frame budget for 120Hz displays.
 * 3. Structure-of-Arrays pool compaction stability under sustained maximum entity density.
 * 4. Sub-0.1ms instant retry and in-place pool reset latency.
 */
class Macrobenchmark120HzTest {

    private lateinit var world: GameWorld
    private lateinit var inputSystem: ShieldInputSystem
    private lateinit var physicsSystem: PhysicsSystem
    private lateinit var directorSystem: DirectorSystem
    private lateinit var juiceSystem: JuiceSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 1337)
        inputSystem = ShieldInputSystem(world)
        physicsSystem = PhysicsSystem(world)
        directorSystem = DirectorSystem(world, initialSeed = 1337)
        juiceSystem = JuiceSystem(world)
    }

    @Test
    fun sustained120HzSimulation_zeroHeapAllocations() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        world.state = GameState.PLAYING

        // Warm up JIT for 3,000 steps simulating full gameplay
        for (step in 0 until 3000) {
            val tNanos = step * 8_333_333L
            simulatePlayerInput(step, tNanos)

            world.gameTime += GameConfig.FIXED_DT
            directorSystem.step(GameConfig.FIXED_DT)
            inputSystem.step(GameConfig.FIXED_DT)
            physicsSystem.stepFrame(GameConfig.FIXED_DT)
            juiceSystem.step(GameConfig.FIXED_DT)

            // Keep simulation alive if bubble damaged
            if (world.bubbleHp < 2) world.bubbleHp = GameConfig.BUBBLE_MAX_HP
        }

        // Measure allocated bytes across 10,000 sustained 120Hz steps (~83 seconds of gameplay)
        val before = AllocationProbe.allocatedBytes()

        val steps = 10_000
        for (step in 0 until steps) {
            val tNanos = (3000 + step) * 8_333_333L
            simulatePlayerInput(step, tNanos)

            world.gameTime += GameConfig.FIXED_DT
            directorSystem.step(GameConfig.FIXED_DT)
            inputSystem.step(GameConfig.FIXED_DT)
            physicsSystem.stepFrame(GameConfig.FIXED_DT)
            juiceSystem.step(GameConfig.FIXED_DT)

            if (world.bubbleHp < 2) world.bubbleHp = GameConfig.BUBBLE_MAX_HP
        }

        val after = AllocationProbe.allocatedBytes()
        val delta = after - before

        // delta <= 128 bytes accounts for reflection invocation overhead; 0 bytes per step
        assertTrue(
            "Sustained 120Hz simulation must allocate 0 bytes per step (delta = $delta bytes over $steps steps)",
            delta <= 128L
        )
    }

    @Test
    fun frameBudgetAudit_stepExecutionTimeWellBelow8Ms() {
        world.state = GameState.PLAYING

        // Warm up JIT
        for (step in 0 until 2000) {
            simulatePlayerInput(step, step * 8_333_333L)
            world.gameTime += GameConfig.FIXED_DT
            directorSystem.step(GameConfig.FIXED_DT)
            inputSystem.step(GameConfig.FIXED_DT)
            physicsSystem.stepFrame(GameConfig.FIXED_DT)
            juiceSystem.step(GameConfig.FIXED_DT)
            if (world.bubbleHp < 2) world.bubbleHp = GameConfig.BUBBLE_MAX_HP
        }

        val measuredSteps = 2000
        val startTimeNanos = System.nanoTime()

        for (step in 0 until measuredSteps) {
            simulatePlayerInput(step, step * 8_333_333L)
            world.gameTime += GameConfig.FIXED_DT
            directorSystem.step(GameConfig.FIXED_DT)
            inputSystem.step(GameConfig.FIXED_DT)
            physicsSystem.stepFrame(GameConfig.FIXED_DT)
            juiceSystem.step(GameConfig.FIXED_DT)
            if (world.bubbleHp < 2) world.bubbleHp = GameConfig.BUBBLE_MAX_HP
        }

        val totalTimeNanos = System.nanoTime() - startTimeNanos
        val avgTimePerStepMs = (totalTimeNanos.toDouble() / measuredSteps.toDouble()) / 1_000_000.0

        // 120Hz frame budget is 8.33ms. Our pure zero-alloc engine should execute in < 1.0ms on modern CPUs.
        assertTrue(
            "Average step time ($avgTimePerStepMs ms) must be well below 8.33ms 120Hz frame budget",
            avgTimePerStepMs < 2.0
        )
    }

    @Test
    fun poolCompactionAndCapacity_underIntenseStress() {
        world.reset()

        // Saturate obstacle pool to maximum capacity
        for (i in 0 until GameConfig.OBSTACLE_POOL_CAPACITY) {
            world.obstacles.spawn(
                posX = 100f + (i % 10) * 80f,
                posY = 200f + (i / 10) * 100f,
                velX = 0f,
                velY = 200f,
                colRadius = 24f,
                mass = 1f,
                colRestitution = 0.75f,
                obstacleType = GameConfig.TYPE_DRIFTER
            )
        }
        assertEquals(GameConfig.OBSTACLE_POOL_CAPACITY, world.obstacles.count)

        // Rapid swap-removes and re-spawns
        for (cycle in 0 until 50) {
            // Remove every other obstacle
            var i = 0
            while (i < world.obstacles.count) {
                world.obstacles.removeAt(i)
                i++
            }

            // Fill back to capacity
            while (world.obstacles.count < world.obstacles.capacity) {
                world.obstacles.spawn(
                    posX = 500f, posY = 400f,
                    velX = 50f, velY = 150f,
                    colRadius = 20f, mass = 1f, colRestitution = 0.7f,
                    obstacleType = GameConfig.TYPE_NEEDLE
                )
            }

            assertEquals(GameConfig.OBSTACLE_POOL_CAPACITY, world.obstacles.count)
        }

        // Verify array elements are contiguous and valid
        for (idx in 0 until world.obstacles.count) {
            assertTrue(world.obstacles.x[idx] > 0f)
            assertTrue(world.obstacles.y[idx] > 0f)
        }
    }

    @Test
    fun instantRetryLatency_andResetZeroAllocation() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        // Warm up reset
        for (i in 0 until 500) {
            world.reset()
            inputSystem.reset()
            directorSystem.reset()
        }

        val before = AllocationProbe.allocatedBytes()
        val startNanos = System.nanoTime()

        val resetCycles = 1000
        for (i in 0 until resetCycles) {
            world.reset()
            inputSystem.reset()
            directorSystem.reset()
        }

        val totalNanos = System.nanoTime() - startNanos
        val after = AllocationProbe.allocatedBytes()
        val delta = after - before

        val avgResetNanos = totalNanos.toDouble() / resetCycles.toDouble()
        val avgResetMs = avgResetNanos / 1_000_000.0

        // Sub-300ms instant retry requires pool reset < 0.1ms
        assertTrue("Average reset time ($avgResetMs ms) must be < 0.1ms", avgResetMs < 0.1)
        assertTrue("Reset must allocate 0 bytes (delta = $delta bytes over $resetCycles resets)", delta <= 128L)
    }

    private fun simulatePlayerInput(step: Int, timeNanos: Long) {
        val swipePhase = step % 60
        when (swipePhase) {
            0 -> {
                // Touch down
                world.inputRing.push(300f, 1000f, timeNanos, 0, InputRingBuffer.ACTION_DOWN)
            }
            in 1..30 -> {
                // Active swipe across tactical zone
                val px = 300f + swipePhase * 15f
                val py = 1000f - swipePhase * 5f
                world.inputRing.push(px, py, timeNanos, 0, InputRingBuffer.ACTION_MOVE)
            }
            31 -> {
                // Touch up
                world.inputRing.push(750f, 850f, timeNanos, 0, InputRingBuffer.ACTION_UP)
            }
        }
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
