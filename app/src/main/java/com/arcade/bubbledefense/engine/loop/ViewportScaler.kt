package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.engine.math.Vec2
import kotlin.math.min

/**
 * Maps coordinates bidirectionally between the 1000x1800 Virtual World Space and physical screen pixels.
 *
 * Implements uniform aspect-ratio letterboxing as specified in `docs/ARCHITECTURE.md` §2.
 * All simulation physics run strictly in Virtual World Space (wu), while rendering and touch input
 * convert between world and screen coordinates through this class with zero heap allocations.
 *
 * Units:
 * - World Space: 1000 x 1800 wu, origin top-left, +X right, **+Y down**.
 * - Screen Space: Physical device pixels (px), origin top-left of Canvas, +X right, +Y down.
 */
class ViewportScaler(
    initialScreenWidth: Float = GameConfig.WORLD_WIDTH,
    initialScreenHeight: Float = GameConfig.WORLD_HEIGHT
) {
    /** Current physical screen/canvas width in pixels. */
    var screenWidth: Float = initialScreenWidth
        private set

    /** Current physical screen/canvas height in pixels. */
    var screenHeight: Float = initialScreenHeight
        private set

    val screenWidthPx: Float inline get() = screenWidth
    val screenHeightPx: Float inline get() = screenHeight

    /** Uniform scaling factor `s = min(Sw / 1000, Sh / 1800)`. */
    var scale: Float = 1f
        private set

    /** Reciprocal of scale (`1f / scale`) to replace division with multiplication on hot paths. */
    var invScale: Float = 1f
        private set

    /** Horizontal letterbox offset in physical screen pixels. */
    var offsetX: Float = 0f
        private set

    /** Vertical letterbox offset in physical screen pixels. */
    var offsetY: Float = 0f
        private set

    /** Scaled world width in screen pixels (`1000 * scale`). */
    var viewportPixelWidth: Float = GameConfig.WORLD_WIDTH
        private set

    /** Scaled world height in screen pixels (`1800 * scale`). */
    var viewportPixelHeight: Float = GameConfig.WORLD_HEIGHT
        private set

    init {
        update(initialScreenWidth, initialScreenHeight)
    }

    /**
     * Updates viewport dimensions when the physical Canvas size changes.
     * Computes the uniform letterbox scale factor and offsets.
     */
    fun update(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height

        if (width <= 0f || height <= 0f) {
            scale = 1f
            invScale = 1f
            offsetX = 0f
            offsetY = 0f
            viewportPixelWidth = GameConfig.WORLD_WIDTH
            viewportPixelHeight = GameConfig.WORLD_HEIGHT
            return
        }

        val scaleX = width / GameConfig.WORLD_WIDTH
        val scaleY = height / GameConfig.WORLD_HEIGHT
        scale = min(scaleX, scaleY)
        invScale = if (scale > 0f) 1f / scale else 1f

        viewportPixelWidth = GameConfig.WORLD_WIDTH * scale
        viewportPixelHeight = GameConfig.WORLD_HEIGHT * scale

        offsetX = (width - viewportPixelWidth) * 0.5f
        offsetY = (height - viewportPixelHeight) * 0.5f
    }

    // ---------------------------------------------------------------------------------------------
    // Zero-Allocation Coordinate Conversions (Primitives & Packed Vec2)
    // ---------------------------------------------------------------------------------------------

    /** Converts a horizontal world coordinate (wu) to physical screen pixels (px). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreenX(worldX: Float): Float = worldX * scale + offsetX

    /** Converts a vertical world coordinate (wu) to physical screen pixels (px). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreenY(worldY: Float): Float = worldY * scale + offsetY

    /** Converts a physical screen pixel X coordinate to world units (wu). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorldX(screenX: Float): Float = (screenX - offsetX) * invScale

    /** Converts a physical screen pixel Y coordinate to world units (wu). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorldY(screenY: Float): Float = (screenY - offsetY) * invScale

    /** Converts a scalar distance from world units (wu) to screen pixels (px). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreenDist(worldDist: Float): Float = worldDist * scale

    /** Scaled distance in screen pixels (px) from world units (wu). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun scaleDistance(worldDist: Float): Float = worldDist * scale

    /** Converts a scalar distance from screen pixels (px) to world units (wu). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorldDist(screenDist: Float): Float = screenDist * invScale

    /**
     * Converts a [Vec2] from world coordinates (wu) to physical screen pixels (px).
     * Packed value class execution guarantees zero heap allocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun worldToScreen(worldPos: Vec2): Vec2 =
        Vec2(worldToScreenX(worldPos.x), worldToScreenY(worldPos.y))

    /**
     * Converts a [Vec2] from physical screen pixels (px) to world units (wu).
     * Packed value class execution guarantees zero heap allocation.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun screenToWorld(screenPos: Vec2): Vec2 =
        Vec2(screenToWorldX(screenPos.x), screenToWorldY(screenPos.y))

    /** Returns `true` if the specified world coordinate lies within the `[0, 1000] x [0, 1800]` world rect. */
    fun isInsideWorld(worldX: Float, worldY: Float): Boolean =
        worldX in 0f..GameConfig.WORLD_WIDTH && worldY in 0f..GameConfig.WORLD_HEIGHT

    /** Returns `true` if the specified screen coordinate falls inside the active letterboxed game viewport. */
    fun isInsideViewport(screenX: Float, screenY: Float): Boolean =
        screenX >= offsetX && screenX <= (offsetX + viewportPixelWidth) &&
                screenY >= offsetY && screenY <= (offsetY + viewportPixelHeight)
}
