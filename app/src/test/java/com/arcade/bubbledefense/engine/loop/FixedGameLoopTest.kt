package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedGameLoopTest {

    private val eps = 1e-4f

    @Test
    fun standard60HzFrame_triggersTwoFixed120HzSteps() {
        val world = GameWorld()
        var stepCount = 0
        var totalSimTime = 0f

        val loop = FixedGameLoop(
            world = world,
            fixedStepListener = { dt, _ ->
                stepCount++
                totalSimTime += dt
            }
        )

        // Simulate a standard 60 FPS frame (approx 0.0166667s)
        // At 120Hz, one step is 1/120s = 0.00833333s. 0.0166667s fits exactly 2 steps.
        loop.stepManual(1f / 60f)

        assertEquals(2, stepCount)
        assertEquals(2 * GameConfig.FIXED_DT, totalSimTime, eps)
        // Accumulator should have the small remainder
        val expectedRemainder = (1f / 60f) - 2 * GameConfig.FIXED_DT
        assertEquals(expectedRemainder, loop.accumulator, eps)
    }

    @Test
    fun lagSpike_clampedToMaxFrameTime_preventingSpiralOfDeath() {
        val world = GameWorld()
        var stepCount = 0

        val loop = FixedGameLoop(
            world = world,
            fixedStepListener = { _, _ -> stepCount++ }
        )

        // Huge lag spike of 0.5 seconds
        loop.stepManual(0.50f)

        // Should be clamped to MAX_FRAME_TIME (0.05s).
        // 0.05s / (1/120s) = 6 steps exactly.
        assertEquals(6, stepCount)
        assertTrue(stepCount <= GameConfig.MAX_STEPS_PER_FRAME)
    }

    @Test
    fun hitStop_pausesSimulationAccumulator_whileDecayingTimer() {
        val world = GameWorld()
        var stepCount = 0
        var frameCount = 0

        val loop = FixedGameLoop(
            world = world,
            fixedStepListener = { _, _ -> stepCount++ },
            frameListener = { _, _ -> frameCount++ }
        )

        // Trigger a 28ms hit-stop freeze frame
        world.triggerHitStop(0.028f)
        assertEquals(0.028f, world.hitStopTimer, eps)

        // Execute a 16ms frame
        loop.stepManual(0.016f)

        // Simulation should NOT have advanced any fixed steps
        assertEquals(0, stepCount)
        // Render frame listener should still have executed
        assertEquals(1, frameCount)
        // Timer should have decayed by 16ms to 12ms
        assertEquals(0.012f, world.hitStopTimer, eps)

        // Execute another 16ms frame (exceeds remaining 12ms)
        loop.stepManual(0.016f)

        // Timer is now 0
        assertEquals(0f, world.hitStopTimer, eps)
        // Simulation was still paused for this frame as the hit-stop concluded
        assertEquals(0, stepCount)

        // Next frame runs normally: 16ms advances 2 steps
        loop.stepManual(0.0166667f)
        assertEquals(2, stepCount)
    }

    @Test
    fun pausedForExternalOverlay_doesNotAdvanceSimulation() {
        val world = GameWorld()
        var stepCount = 0
        world.state = GameState.PAUSED_FOR_EXTERNAL_OVERLAY

        val loop = FixedGameLoop(
            world = world,
            fixedStepListener = { _, _ -> stepCount++ }
        )

        loop.stepManual(0.033f)
        assertEquals(0, stepCount)
        assertEquals(0f, loop.accumulator, eps)
    }

    @Test
    fun alphaInterpolationFactor_matchesRemainder() {
        val world = GameWorld()
        var lastAlpha = 0f

        val loop = FixedGameLoop(
            world = world,
            fixedStepListener = { _, _ -> },
            frameListener = { _, alpha -> lastAlpha = alpha }
        )

        // 1.5 fixed steps of time: 1.5 * (1/120) = 0.0125s
        val testDt = 1.5f * GameConfig.FIXED_DT
        loop.stepManual(testDt)

        // Accumulator should have 0.5 * FIXED_DT remaining -> alpha = 0.5
        assertEquals(0.5f, lastAlpha, 1e-3f)
    }

    @Test
    fun zeroAllocation_fixedLoop_allocatesNothing() {
        val counter = getAllocationCounter() ?: return
        val bean = getManagementBean() ?: return

        val world = GameWorld()
        // Pre-populate entities in pools
        for (i in 0 until 64) {
            world.obstacles.spawn(100f + i * 10f, 200f, 0f, 300f, 28f, 1f, 0.75f, GameConfig.TYPE_DRIFTER)
            world.shieldSegments.spawn(50f, 500f, 150f, 500f, 0f, 0f, 1, 0)
        }

        var stepCounter = 0
        val loop = FixedGameLoop(
            world = world,
            fixedStepListener = { dt, step ->
                stepCounter++
                // Update obstacle positions without allocations
                for (i in 0 until world.obstacles.count) {
                    world.obstacles.y[i] += world.obstacles.vy[i] * dt
                }
            },
            frameListener = { _, _ -> }
        )

        // Warm up JIT
        for (i in 0 until 2000) {
            loop.stepManual(GameConfig.FIXED_DT)
        }

        @Suppress("DEPRECATION")
        val threadId = Thread.currentThread().id
        val before = counter.invoke(bean, threadId) as Long

        val iterations = 50_000
        for (i in 0 until iterations) {
            loop.stepManual(GameConfig.FIXED_DT)
        }

        @Suppress("DEPRECATION")
        val after = counter.invoke(bean, threadId) as Long
        val delta = after - before

        assertTrue(stepCounter > iterations)
        assertTrue("Loop allocated $delta bytes over $iterations steps", delta < 32 * 1024)
    }

    private fun getManagementBean(): Any? = try {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        factory.getMethod("getThreadMXBean").invoke(null)
    } catch (t: Throwable) {
        null
    }

    private fun getAllocationCounter(): java.lang.reflect.Method? = try {
        val sunBean = Class.forName("com.sun.management.ThreadMXBean")
        val bean = getManagementBean() ?: return null
        if (sunBean.isInstance(bean) && sunBean.getMethod("isThreadAllocatedMemorySupported").invoke(bean) == true) {
            sunBean.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
            sunBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        } else null
    } catch (t: Throwable) {
        null
    }
}
