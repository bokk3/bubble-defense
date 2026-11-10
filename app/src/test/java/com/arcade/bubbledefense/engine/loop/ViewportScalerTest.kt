package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.engine.math.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportScalerTest {

    private val eps = 1e-4f

    @Test
    fun identityResolution_scaleIsOne_offsetsAreZero() {
        val scaler = ViewportScaler(1000f, 1800f)
        assertEquals(1f, scaler.scale, eps)
        assertEquals(0f, scaler.offsetX, eps)
        assertEquals(0f, scaler.offsetY, eps)

        val worldPos = Vec2(500f, 900f)
        val screenPos = scaler.worldToScreen(worldPos)
        assertEquals(500f, screenPos.x, eps)
        assertEquals(900f, screenPos.y, eps)

        val roundTrip = scaler.screenToWorld(screenPos)
        assertEquals(500f, roundTrip.x, eps)
        assertEquals(900f, roundTrip.y, eps)
    }

    @Test
    fun wideScreen_pillarbox_horizontalOffsetsApplied() {
        // Screen width 1400, height 1800 -> limited by height -> scale = 1.0
        val scaler = ViewportScaler(1400f, 1800f)
        assertEquals(1f, scaler.scale, eps)
        assertEquals(200f, scaler.offsetX, eps) // (1400 - 1000) / 2
        assertEquals(0f, scaler.offsetY, eps)

        // Center of world (500, 900) should be at screen (700, 900)
        assertEquals(700f, scaler.worldToScreenX(500f), eps)
        assertEquals(900f, scaler.worldToScreenY(900f), eps)

        // Screen (700, 900) back to world
        assertEquals(500f, scaler.screenToWorldX(700f), eps)
        assertEquals(900f, scaler.screenToWorldY(900f), eps)
    }

    @Test
    fun tallScreen_letterbox_verticalOffsetsApplied() {
        // Screen width 1000, height 2200 -> limited by width -> scale = 1.0
        val scaler = ViewportScaler(1000f, 2200f)
        assertEquals(1f, scaler.scale, eps)
        assertEquals(0f, scaler.offsetX, eps)
        assertEquals(200f, scaler.offsetY, eps) // (2200 - 1800) / 2

        assertEquals(500f, scaler.worldToScreenX(500f), eps)
        assertEquals(1100f, scaler.worldToScreenY(900f), eps) // 900 + 200

        assertEquals(500f, scaler.screenToWorldX(500f), eps)
        assertEquals(900f, scaler.screenToWorldY(1100f), eps)
    }

    @Test
    fun realPhoneDimensions_1080x2400_roundTripsExact() {
        // 1080 / 1000 = 1.08; 2400 / 1800 = 1.33333 -> scale = 1.08
        val scaler = ViewportScaler(1080f, 2400f)
        assertEquals(1.08f, scaler.scale, eps)
        assertEquals(0f, scaler.offsetX, eps)
        val expectedHeight = 1800f * 1.08f // 1944
        assertEquals((2400f - expectedHeight) * 0.5f, scaler.offsetY, eps) // 228f

        val testPoints = arrayOf(
            Vec2(0f, 0f),
            Vec2(GameConfig.WORLD_WIDTH, GameConfig.WORLD_HEIGHT),
            Vec2(GameConfig.BUBBLE_CENTER_X, GameConfig.BUBBLE_CENTER_Y),
            Vec2(123.45f, 678.9f)
        )

        for (pt in testPoints) {
            val screen = scaler.worldToScreen(pt)
            val roundTrip = scaler.screenToWorld(screen)
            assertEquals(pt.x, roundTrip.x, 1e-3f)
            assertEquals(pt.y, roundTrip.y, 1e-3f)
        }
    }

    @Test
    fun distanceScaling() {
        val scaler = ViewportScaler(2000f, 3600f) // 2x uniform scale
        assertEquals(2f, scaler.scale, eps)

        val worldDist = 50f
        val screenDist = scaler.worldToScreenDist(worldDist)
        assertEquals(100f, screenDist, eps)
        assertEquals(worldDist, scaler.screenToWorldDist(screenDist), eps)
    }

    @Test
    fun containmentChecks() {
        val scaler = ViewportScaler(1200f, 1800f) // offsetX = 100f
        assertTrue(scaler.isInsideWorld(0f, 0f))
        assertTrue(scaler.isInsideWorld(1000f, 1800f))
        assertFalse(scaler.isInsideWorld(-1f, 500f))
        assertFalse(scaler.isInsideWorld(500f, 1801f))

        // Screen viewport is [100, 1100] horizontally
        assertTrue(scaler.isInsideViewport(100f, 0f))
        assertTrue(scaler.isInsideViewport(600f, 900f))
        assertTrue(scaler.isInsideViewport(1100f, 1800f))
        assertFalse(scaler.isInsideViewport(99f, 900f)) // in left pillarbox bar
        assertFalse(scaler.isInsideViewport(1101f, 900f)) // in right pillarbox bar
    }
}
