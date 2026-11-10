package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.math.Vec2
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import kotlin.math.abs

class JuiceSystemTest {

    private lateinit var world: GameWorld
    private lateinit var juice: JuiceSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 42)
        juice = JuiceSystem(world)
    }

    @Test
    fun cameraShake_zeroTrauma_producesZeroOffsets() {
        world.trauma = 0f
        juice.step(0.016f)

        assertEquals(0f, juice.shakeOffsetX, 1e-6f)
        assertEquals(0f, juice.shakeOffsetY, 1e-6f)
        assertEquals(0f, juice.shakeRotationRad, 1e-6f)
    }

    @Test
    fun cameraShake_withTrauma_producesBoundedQuadraticOffsets() {
        world.trauma = 0.8f
        world.gameTime = 0.123f
        juice.step(0.016f)

        // Shake should be bounded within max constants
        assertTrue("Shake X within bounds", abs(juice.shakeOffsetX) <= GameConfig.MAX_SHAKE_OFFSET_PX)
        assertTrue("Shake Y within bounds", abs(juice.shakeOffsetY) <= GameConfig.MAX_SHAKE_OFFSET_PX)
        assertTrue("Shake rotation within bounds", abs(juice.shakeRotationRad) <= GameConfig.MAX_SHAKE_ROTATION_RAD)
    }

    @Test
    fun closeCallDetection_triggersSlowMo_text_and_score() {
        world.reset()
        // Bubble at (500, 1476), radius = 64
        // Place obstacle at distance 70 wu from surface (total center dist = 64 + 70 = 134 wu < 64 + 80 = 144 wu)
        val obsX = world.bubbleX
        val obsY = world.bubbleY - (world.bubbleRadius + 50f)
        world.obstacles.spawn(
            posX = obsX, posY = obsY,
            velX = 0f, velY = 100f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        val initialScore = world.score
        val initialTexts = world.floatingTexts.count

        juice.step(GameConfig.FIXED_DT)

        // Should trigger slow-mo
        assertEquals(0.25f, world.timeScale, 1e-4f)
        assertTrue("Slow-mo timer active", world.slowMoTimer > 0f)

        // Should add trauma
        assertTrue("Trauma increased", world.trauma >= 0.15f)

        // Should award score
        assertEquals(initialScore + 25, world.score)

        // Should spawn floating text
        assertEquals(initialTexts + 1, world.floatingTexts.count)
        assertEquals(FloatingTextPool.TEXT_CLOSE_CALL, world.floatingTexts.textId[0])
    }

    @Test
    fun shepardToneComboPitch_scalesSmoothly_andCapsAtTwo() {
        assertEquals(1.0f, juice.getComboPitch(0), 1e-4f)
        assertEquals(1.25f, juice.getComboPitch(5), 1e-4f)
        assertEquals(1.50f, juice.getComboPitch(10), 1e-4f)
        assertEquals(2.0f, juice.getComboPitch(20), 1e-4f)
        assertEquals(2.0f, juice.getComboPitch(100), 1e-4f) // Capped at 2.0x
    }

    @Test
    fun directionalSparks_inheritsMomentumAndSetsColor() {
        world.particles.clear()
        val exitV = Vec2(300f, -400f)
        val normal = Vec2.UP

        // Spawn parry sparks
        juice.spawnDirectionalSparks(200f, 300f, exitV, normal, count = 12, isParry = true)
        assertEquals(12, world.particles.count)

        // Parry color: 0xFF00F0FF (Neon Cyan)
        assertEquals(0xFF00F0FF.toInt(), world.particles.color[0])

        // Particles have upward velocity (normal is UP)
        assertTrue("Particles should travel upward", world.particles.vy[0] < 0f)
    }

    @Test
    fun hitStopAndTraumaTriggers_activateCorrectly() {
        world.reset()
        juice.onParry()
        assertEquals(GameConfig.HIT_STOP_PARRY_DURATION, world.hitStopTimer, 1e-4f)
        assertEquals(GameConfig.TRAUMA_PARRY, world.trauma, 1e-4f)

        world.reset()
        juice.onCrusherHit()
        assertEquals(GameConfig.HIT_STOP_CRUSHER_DURATION, world.hitStopTimer, 1e-4f)
        assertEquals(GameConfig.TRAUMA_CRUSHER, world.trauma, 1e-4f)

        world.reset()
        juice.onBubblePop()
        assertEquals(GameConfig.HIT_STOP_POP_DURATION, world.hitStopTimer, 1e-4f)
        assertEquals(GameConfig.TRAUMA_POP, world.trauma, 1e-4f)
    }

    @Test
    fun zeroAllocation_hotPathProbe() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        world.reset()
        world.trauma = 0.5f

        // Warm up JIT
        for (i in 0 until 2000) {
            juice.step(GameConfig.FIXED_DT)
        }

        val before = AllocationProbe.allocatedBytes()

        for (i in 0 until 5000) {
            juice.step(GameConfig.FIXED_DT)
        }

        val after = AllocationProbe.allocatedBytes()
        val allocated = after - before

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
