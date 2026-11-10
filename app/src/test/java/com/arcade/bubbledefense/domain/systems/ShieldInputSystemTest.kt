package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

class ShieldInputSystemTest {

    private lateinit var world: GameWorld
    private lateinit var inputSystem: ShieldInputSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 42)
        inputSystem = ShieldInputSystem(world)
    }

    @Test
    fun decimation_movementBelowMinLength_doesNotSpawnSegment() {
        // Pointer down at (100, 100)
        inputSystem.processTouchSample(100f, 100f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        assertEquals(0, world.shieldSegments.count)

        // Move only 5 wu (below GameConfig.MIN_SEGMENT_LENGTH = 12 wu)
        inputSystem.processTouchSample(105f, 100f, 2_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)
        assertEquals(0, world.shieldSegments.count)

        // Move another 5 wu (total 10 wu from origin, still < 12 wu)
        inputSystem.processTouchSample(110f, 100f, 3_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)
        assertEquals(0, world.shieldSegments.count)
    }

    @Test
    fun movementAboveMinLength_spawnsSegment_withCorrectEndpoints() {
        // Down at (100, 100)
        inputSystem.processTouchSample(100f, 100f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)

        // Move 20 wu to (120, 100) (> 12 wu, <= 48 wu)
        inputSystem.processTouchSample(120f, 100f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)

        assertEquals(1, world.shieldSegments.count)
        assertEquals(100f, world.shieldSegments.ax[0], 1e-3f)
        assertEquals(100f, world.shieldSegments.ay[0], 1e-3f)
        assertEquals(120f, world.shieldSegments.bx[0], 1e-3f)
        assertEquals(100f, world.shieldSegments.by[0], 1e-3f)
        assertEquals(20f, world.shieldSegments.length[0], 1e-3f)

        // Check normal: horizontal movement (+X) has normal pointing down (+Y) in virtual coordinates
        assertEquals(0f, world.shieldSegments.nx[0], 1e-3f)
        assertEquals(1f, world.shieldSegments.ny[0], 1e-3f)
    }

    @Test
    fun longMovement_subdividesIntoSegments_atOrBelowMaxLength() {
        // Move 100 wu in one jump (> MAX_SEGMENT_LENGTH = 48 wu)
        // ceil(100 / 48) = 3 subdivisions: each ~33.33 wu
        inputSystem.processTouchSample(0f, 0f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        inputSystem.processTouchSample(100f, 0f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)

        assertEquals(3, world.shieldSegments.count)
        for (i in 0 until 3) {
            assertTrue("Segment length must be <= 48", world.shieldSegments.length[i] <= 48f + 1e-3f)
        }
        assertEquals(0f, world.shieldSegments.ax[0], 1e-3f)
        assertEquals(100f, world.shieldSegments.bx[2], 1e-3f)
    }

    @Test
    fun inkConsumption_drainsProportionallyToLength() {
        val initialInk = world.ink
        assertEquals(GameConfig.INK_MAX, initialInk, 1e-3f)

        // Down and move 40 wu
        inputSystem.processTouchSample(100f, 100f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        inputSystem.processTouchSample(140f, 100f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)

        val expectedDrain = 40f * GameConfig.INK_PER_WU
        val expectedInk = initialInk - expectedDrain
        assertEquals(expectedInk, world.ink, 1e-3f)
    }

    @Test
    fun inkCooldown_gatesRegenerationUntilElapsed() {
        world.ink = 50f
        world.inkCooldownTimer = GameConfig.INK_REGEN_DELAY // 0.40s
        world.activePointerCount = 0

        // Step 0.20s: still in cooldown, no ink regen
        inputSystem.step(0.20f)
        assertEquals(50f, world.ink, 1e-3f)
        assertEquals(0.20f, world.inkCooldownTimer, 1e-3f)

        // Step another 0.20s: cooldown expires
        inputSystem.step(0.20f)
        assertEquals(0f, world.inkCooldownTimer, 1e-3f)
        assertEquals(50f, world.ink, 1e-3f)

        // Step 0.10s after cooldown: ink regens at INK_REGEN_RATE wu/s
        inputSystem.step(0.10f)
        val expected = 50f + (GameConfig.INK_REGEN_RATE * 0.10f)
        assertEquals(expected, world.ink, 1e-3f)
    }

    @Test
    fun inkExhaustion_truncatesLastSegment_andHaltsEmission() {
        // Set low ink: only enough for 15 wu
        val targetLen = 15f
        world.ink = targetLen * GameConfig.INK_PER_WU

        inputSystem.processTouchSample(0f, 0f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        // Drag 30 wu
        inputSystem.processTouchSample(30f, 0f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)

        // Truncated to 15 wu
        assertEquals(1, world.shieldSegments.count)
        assertEquals(15f, world.shieldSegments.length[0], 1e-2f)
        assertEquals(15f, world.shieldSegments.bx[0], 1e-2f)
        assertEquals(0f, world.ink, 1e-3f)

        // Subsequent drag with 0 ink emits nothing
        inputSystem.processTouchSample(60f, 0f, 33_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)
        assertEquals(1, world.shieldSegments.count)
    }

    @Test
    fun segmentAging_expiresAfterLifetime() {
        inputSystem.processTouchSample(0f, 0f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        inputSystem.processTouchSample(20f, 0f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)
        assertEquals(1, world.shieldSegments.count)

        // Step simulation past lifetime (1.5s)
        inputSystem.step(1.0f)
        assertEquals(1, world.shieldSegments.count)

        inputSystem.step(0.6f) // total 1.6s
        assertEquals(0, world.shieldSegments.count)
    }

    @Test
    fun brokenSegment_spawnsShatterParticles_andIsRemoved() {
        inputSystem.processTouchSample(0f, 0f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        inputSystem.processTouchSample(20f, 0f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)
        assertEquals(1, world.shieldSegments.count)
        assertEquals(0, world.particles.count)

        // Break integrity
        world.shieldSegments.integrity[0] = 0f

        inputSystem.step(0.016f)
        assertEquals(0, world.shieldSegments.count)
        assertEquals(8, world.particles.count) // Shatter particles spawned!
    }

    @Test
    fun multiPointer_tracksConcurrentStrokesIndependently() {
        // Pointer 0 down at (100, 100)
        inputSystem.processTouchSample(100f, 100f, 1_000_000L, pointer = 0, action = InputRingBuffer.ACTION_DOWN)
        // Pointer 1 down at (500, 500)
        inputSystem.processTouchSample(500f, 500f, 1_000_000L, pointer = 1, action = InputRingBuffer.ACTION_DOWN)

        assertEquals(2, world.activePointerCount)

        // Move pointer 0
        inputSystem.processTouchSample(130f, 100f, 17_000_000L, pointer = 0, action = InputRingBuffer.ACTION_MOVE)
        // Move pointer 1
        inputSystem.processTouchSample(500f, 530f, 17_000_000L, pointer = 1, action = InputRingBuffer.ACTION_MOVE)

        assertEquals(2, world.shieldSegments.count)
        assertEquals(0, world.shieldSegments.pointerId[0])
        assertEquals(1, world.shieldSegments.pointerId[1])

        // Lift pointer 0
        inputSystem.processTouchSample(130f, 100f, 20_000_000L, pointer = 0, action = InputRingBuffer.ACTION_UP)
        assertEquals(1, world.activePointerCount)

        // Lift pointer 1
        inputSystem.processTouchSample(500f, 530f, 20_000_000L, pointer = 1, action = InputRingBuffer.ACTION_UP)
        assertEquals(0, world.activePointerCount)
    }

    @Test
    fun zeroAllocation_probe_shieldInputSystem() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        // Warm up JIT
        for (i in 0 until 500) {
            world.inputRing.push(100f + (i % 20), 100f, (i * 1000).toLong(), 0, InputRingBuffer.ACTION_MOVE)
            inputSystem.step(0.008f)
        }

        world.reset()
        inputSystem.reset()
        world.inputRing.push(100f, 100f, 1_000_000L, 0, InputRingBuffer.ACTION_DOWN)
        inputSystem.step(0.008f)

        val before = AllocationProbe.allocatedBytes()

        val iterations = 5000
        for (i in 1..iterations) {
            val px = 100f + (i % 40) * 15f
            world.inputRing.push(px, 100f, (i * 16_000_000L), 0, InputRingBuffer.ACTION_MOVE)
            inputSystem.step(0.008f)
        }

        val after = AllocationProbe.allocatedBytes()
        val allocated = after - before

        assertTrue("ShieldInputSystem hot path must allocate 0 bytes per step (delta = $allocated bytes)", allocated <= 128L)
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
