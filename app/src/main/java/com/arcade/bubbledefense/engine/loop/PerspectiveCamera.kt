package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.engine.math.Vec2
import kotlin.math.max
import kotlin.math.min

/**
 * High-precision closed-form 2.5D perspective camera and coordinate transformer.
 *
 * Implements the projective geometry specified in `docs/ARCHITECTURE.md` §2 and
 * `plan_2_5d_perspective_lane.md`.
 *
 * Maps between the 2D Virtual World Highway Space (1000x1800 wu) and physical device screen pixels:
 * - Longitudinal axis (+Y): forward distance down the lane towards the player (+Y down).
 *   $Y = 0$: horizon spawn line (infinite distance in perspective).
 *   $Y = 1476$: Bubble anchor defense target.
 *   $Y = 1850$: Camera eye station behind the bubble.
 * - Transverse axis (X): lateral position across the lane ($X = 500$ center, $100$ left curb, $900$ right curb).
 * - Elevation (h): vertical height above the highway ground plane (wu).
 *
 * Performance:
 * Guarantees ZERO heap allocations in all projection and unprojection methods.
 * Operations are pure single-cycle algebraic primitives.
 */
class PerspectiveCamera(
    initialScreenWidth: Float = GameConfig.WORLD_WIDTH,
    initialScreenHeight: Float = GameConfig.WORLD_HEIGHT
) {
    // ---------------------------------------------------------------------------------------------
    // Camera Geometry & Viewport State
    // ---------------------------------------------------------------------------------------------

    var screenWidth: Float = initialScreenWidth
        private set

    var screenHeight: Float = initialScreenHeight
        private set

    /** Horizontal vanishing point on screen (px). */
    var vanishingPointX: Float = initialScreenWidth * 0.5f
        private set

    /** Vertical vanishing point / horizon line on screen (px). */
    var vanishingPointY: Float = initialScreenHeight * GameConfig.CAMERA_HORIZON_RATIO_Y
        private set

    /** Bubble projected screen Y coordinate (px). */
    var bubbleScreenY: Float = initialScreenHeight * GameConfig.BUBBLE_NORMALIZED_Y
        private set

    /** Longitudinal position of camera eye in world units (wu). */
    val cameraY: Float = GameConfig.CAMERA_Y_WU

    /** Camera focal reference depth to bubble target: `cameraY - BUBBLE_CENTER_Y` (374 wu). */
    val focalDepthD: Float = GameConfig.CAMERA_BUBBLE_DEPTH_D

    /** Perspective vertical projection constant $K = (S_{y,\text{bubble}} - V_y) \cdot D$. */
    var projectionK: Float = 1f
        private set

    /** Horizontal lane scale factor at depth D. */
    var laneScaleX: Float = 1f
        private set

    /** Reciprocal of laneScaleX to replace divisions with multiplications. */
    var invLaneScaleX: Float = 1f
        private set

    /** Elevation height scale factor. */
    var heightScaleY: Float = 1f
        private set

    // Minimum and maximum depth clamp thresholds
    val minZ: Float = 60f
    val maxZ: Float = 3500f

    init {
        update(initialScreenWidth, initialScreenHeight)
    }

    /**
     * Recomputes projection constants when physical screen/canvas dimensions change.
     */
    fun update(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height

        if (width <= 0f || height <= 0f) {
            vanishingPointX = 0f
            vanishingPointY = 0f
            bubbleScreenY = 0f
            projectionK = 1f
            laneScaleX = 1f
            invLaneScaleX = 1f
            heightScaleY = 1f
            return
        }

        vanishingPointX = width * 0.5f
        vanishingPointY = height * GameConfig.CAMERA_HORIZON_RATIO_Y
        bubbleScreenY = height * GameConfig.BUBBLE_NORMALIZED_Y

        // K calibrated so that at world Y = 1476 wu, projected sy lands exactly at bubbleScreenY
        val verticalSpan = max(10f, bubbleScreenY - vanishingPointY)
        projectionK = verticalSpan * focalDepthD

        // Lane width at the bubble occupies 86% of the screen width
        val laneTargetWidthPx = width * 0.86f
        laneScaleX = laneTargetWidthPx / GameConfig.LANE_WIDTH_WU
        invLaneScaleX = if (laneScaleX > 0f) 1f / laneScaleX else 1f
        heightScaleY = laneScaleX
    }

    // ---------------------------------------------------------------------------------------------
    // Forward Perspective Projection (World -> Screen)
    // ---------------------------------------------------------------------------------------------

    /**
     * Calculates the perspective scale factor $s(Z) = D / Z$ for an entity at world Y.
     * Scale equals 1.0 at the bubble ($Y = 1476\text{ wu}$), shrinking toward 0 at the horizon.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun scaleAtDepth(worldY: Float): Float {
        val z = (cameraY - worldY).coerceIn(minZ, maxZ)
        return focalDepthD / z
    }

    /**
     * Projects horizontal world X coordinate to screen pixel X.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreenX(worldX: Float, worldY: Float): Float {
        val scale = scaleAtDepth(worldY)
        val relX = worldX - GameConfig.BUBBLE_CENTER_X
        return vanishingPointX + (relX * scale * laneScaleX)
    }

    /**
     * Projects vertical world Y coordinate and height above ground to screen pixel Y.
     *
     * @param worldY Longitudinal position along lane (wu).
     * @param heightWu Vertical elevation above the ground plane (wu).
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreenY(worldY: Float, heightWu: Float = 0f): Float {
        val z = (cameraY - worldY).coerceIn(minZ, maxZ)
        val groundSy = vanishingPointY + (projectionK / z)
        return if (heightWu != 0f) {
            val scale = focalDepthD / z
            groundSy - (heightWu * scale * heightScaleY)
        } else {
            groundSy
        }
    }

    /**
     * Projects a 2D world coordinate on the ground plane ($h = 0$) to screen pixels as a packed [Vec2].
     * Zero heap allocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreen(worldPos: Vec2): Vec2 {
        val z = (cameraY - worldPos.y).coerceIn(minZ, maxZ)
        val scale = focalDepthD / z
        val relX = worldPos.x - GameConfig.BUBBLE_CENTER_X
        val sx = vanishingPointX + (relX * scale * laneScaleX)
        val sy = vanishingPointY + (projectionK / z)
        return Vec2(sx, sy)
    }

    /**
     * Projects a 3D world coordinate $(X, Y, h)$ to screen pixels as a packed [Vec2].
     * Zero heap allocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreen(worldX: Float, worldY: Float, heightWu: Float): Vec2 {
        val z = (cameraY - worldY).coerceIn(minZ, maxZ)
        val scale = focalDepthD / z
        val relX = worldX - GameConfig.BUBBLE_CENTER_X
        val sx = vanishingPointX + (relX * scale * laneScaleX)
        val sy = vanishingPointY + (projectionK / z) - (heightWu * scale * heightScaleY)
        return Vec2(sx, sy)
    }

    /**
     * Scales a scalar distance or radius (such as circle collision bounds) at a given depth.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun scaleDistance(worldDist: Float, worldY: Float): Float {
        return worldDist * scaleAtDepth(worldY) * laneScaleX
    }

    // ---------------------------------------------------------------------------------------------
    // Reverse Screen-to-World Unprojection (Screen -> World Ground Plane)
    // ---------------------------------------------------------------------------------------------

    /**
     * Unprojects a physical screen pixel Y coordinate onto the 3D highway ground plane ($h = 0$).
     * Clamps touches near or above horizon to avoid division by zero.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorldY(screenY: Float): Float {
        // Enforce safety margin below horizon line
        val safeSy = max(screenY, vanishingPointY + 8f)
        val deltaSy = safeSy - vanishingPointY
        val z = (projectionK / deltaSy).coerceIn(minZ, maxZ)
        return cameraY - z
    }

    /**
     * Unprojects a physical screen pixel X coordinate at a given screen Y onto the ground plane.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorldX(screenX: Float, screenY: Float): Float {
        val safeSy = max(screenY, vanishingPointY + 8f)
        val deltaSy = safeSy - vanishingPointY
        val z = (projectionK / deltaSy).coerceIn(minZ, maxZ)
        val scale = focalDepthD / z
        val invScale = if (scale > 0f) 1f / scale else 1f

        val relSx = screenX - vanishingPointX
        return GameConfig.BUBBLE_CENTER_X + (relSx * invScale * invLaneScaleX)
    }

    /**
     * Unprojects physical screen pixel coordinates [screenPos] onto the 3D highway ground plane.
     * Packed value class execution guarantees zero heap allocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorld(screenPos: Vec2): Vec2 {
        val safeSy = max(screenPos.y, vanishingPointY + 8f)
        val deltaSy = safeSy - vanishingPointY
        val z = (projectionK / deltaSy).coerceIn(minZ, maxZ)
        val scale = focalDepthD / z
        val invScale = if (scale > 0f) 1f / scale else 1f

        val relSx = screenPos.x - vanishingPointX
        val wx = GameConfig.BUBBLE_CENTER_X + (relSx * invScale * invLaneScaleX)
        val wy = cameraY - z
        return Vec2(wx, wy)
    }

    /**
     * Returns true if the screen coordinate is safely below the horizon line where drawing is active.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun isDrawableScreenY(screenY: Float): Boolean =
        screenY >= vanishingPointY + 16f
}
