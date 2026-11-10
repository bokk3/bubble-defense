package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.engine.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import java.util.Random

/**
 * Unit verification suite for [PerspectiveCamera].
 *
 * Verifies:
 * 1. Bubble anchor coordinates project to the exact normalized position on screen.
 * 2. Closed-form projection and unprojection are exact bijectives on the ground plane.
 * 3. Perspective scaling decreases monotonically toward the horizon.
 * 4. Horizon singularity clamping prevents division by zero, NaNs, and infinities.
 * 5. Hot path execution guarantees zero heap allocation.
 */
class PerspectiveCameraTest {

    private lateinit var camera: PerspectiveCamera

    @Before
    fun setUp() {
        camera = PerspectiveCamera(1080f, 2400f)
    }

    @Test
    fun testBubbleAnchorProjection() {
        // The bubble at (500, 1476) should project to screen center X and 82% screen height
        val sx = camera.worldToScreenX(GameConfig.BUBBLE_CENTER_X, GameConfig.BUBBLE_CENTER_Y)
        val sy = camera.worldToScreenY(GameConfig.BUBBLE_CENTER_Y)

        val expectedSx = 1080f * 0.5f
        val expectedSy = 2400f * GameConfig.BUBBLE_NORMALIZED_Y

        assertEquals("Bubble screen X should align with screen center", expectedSx, sx, 0.001f)
        assertEquals("Bubble screen Y should align with 82% height", expectedSy, sy, 0.001f)

        // Perspective scale at the bubble depth should equal 1.0
        val scaleAtBubble = camera.scaleAtDepth(GameConfig.BUBBLE_CENTER_Y)
        assertEquals("Scale at bubble depth must equal 1.0", 1.0f, scaleAtBubble, 0.0001f)
    }

    @Test
    fun testMonotonicDepthScaling() {
        // Scale at horizon (Y=0) must be significantly smaller than at bubble (Y=1476)
        val scaleFar = camera.scaleAtDepth(0f)
        val scaleMid = camera.scaleAtDepth(800f)
        val scaleNear = camera.scaleAtDepth(1476f)

        assertTrue("Distant scale must be positive", scaleFar > 0f)
        assertTrue("Mid scale must be greater than distant scale", scaleMid > scaleFar)
        assertTrue("Near scale must be greater than mid scale", scaleNear > scaleMid)
        assertEquals(1.0f, scaleNear, 0.0001f)
    }

    @Test
    fun testRoundTripBijectionAcrossPlayableLane() {
        val random = Random(42)
        val iterations = 5000

        for (i in 0 until iterations) {
            val wx = 100f + random.nextFloat() * 800f // Inside [100, 900]
            val wy = 200f + random.nextFloat() * 1400f // Inside [200, 1600]

            // 1. Forward project to screen
            val sx = camera.worldToScreenX(wx, wy)
            val sy = camera.worldToScreenY(wy)

            // 2. Reverse unproject back to world ground plane
            val recoveredWx = camera.screenToWorldX(sx, sy)
            val recoveredWy = camera.screenToWorldY(sy)

            assertEquals("Recovered X must match original X", wx, recoveredWx, 0.05f)
            assertEquals("Recovered Y must match original Y", wy, recoveredWy, 0.05f)
        }
    }

    @Test
    fun testPackedVec2ProjectionAndUnprojection() {
        val worldPos = Vec2(650f, 1100f)
        val screenPos = camera.worldToScreen(worldPos)
        val recoveredPos = camera.screenToWorld(screenPos)

        assertEquals("Packed Vec2 X roundtrip", worldPos.x, recoveredPos.x, 0.05f)
        assertEquals("Packed Vec2 Y roundtrip", worldPos.y, recoveredPos.y, 0.05f)
    }

    @Test
    fun testElevationProjection() {
        val worldX = 500f
        val worldY = 1200f
        val groundSy = camera.worldToScreenY(worldY, heightWu = 0f)
        val elevatedSy = camera.worldToScreenY(worldY, heightWu = 80f)

        // In screen space (+Y is down), positive height must project HIGHER on screen (smaller sy)
        assertTrue("Elevated height must appear higher on screen (smaller sy)", elevatedSy < groundSy)
    }

    @Test
    fun testHorizonSingularitySafety() {
        val horizonY = camera.vanishingPointY

        // Touch at exact horizon
        val unprojectedYAtHorizon = camera.screenToWorldY(horizonY)
        assertFalse("Unprojected Y at horizon must not be NaN", unprojectedYAtHorizon.isNaN())
        assertFalse("Unprojected Y at horizon must not be Infinite", unprojectedYAtHorizon.isInfinite())

        // Touch above horizon (in sky)
        val unprojectedYInSky = camera.screenToWorldY(horizonY - 100f)
        assertFalse("Unprojected Y in sky must not be NaN", unprojectedYInSky.isNaN())
        assertFalse("Unprojected Y in sky must not be Infinite", unprojectedYInSky.isInfinite())

        // Check isDrawableScreenY filter
        assertFalse("Touches above horizon are not drawable", camera.isDrawableScreenY(horizonY - 5f))
        assertTrue("Touches well below horizon are drawable", camera.isDrawableScreenY(horizonY + 50f))
    }

    @Test
    fun testZeroAllocationHotPathProbe() {
        val probe = AllocationProbe()
        if (!probe.isAvailable) return
        probe.warmUp()

        // Warm up JIT
        for (i in 0 until 10_000) {
            val sx = camera.worldToScreenX(500f + (i % 200), 1000f + (i % 400))
            val sy = camera.worldToScreenY(1000f + (i % 400))
            val wx = camera.screenToWorldX(sx, sy)
            val wy = camera.screenToWorldY(sy)
            if (wx + wy < 0f) println("Warmup")
        }

        // Measure allocation over 50,000 iterations
        val startBytes = probe.allocatedBytes()
        for (i in 0 until 50_000) {
            val wxIn = 300f + (i % 400)
            val wyIn = 500f + (i % 900)
            val sx = camera.worldToScreenX(wxIn, wyIn)
            val sy = camera.worldToScreenY(wyIn)
            val wx = camera.screenToWorldX(sx, sy)
            val wy = camera.screenToWorldY(sy)
            if (wx + wy < 0f) println("Unreachable")
        }
        val endBytes = probe.allocatedBytes()
        val deltaBytes = endBytes - startBytes

        // Delta should be 0 bytes allocated per iteration (delta <= 128 bytes accommodates invocation boxing)
        assertTrue("Perspective projection must allocate 0 bytes on hot path (delta: $deltaBytes)", deltaBytes <= 128L)
    }

    private class AllocationProbe {
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
        fun allocatedBytes(): Long = counter?.invoke(bean, Thread.currentThread().id) as Long

        fun warmUp() {
            for (i in 0 until 64) allocatedBytes()
        }
    }
}
