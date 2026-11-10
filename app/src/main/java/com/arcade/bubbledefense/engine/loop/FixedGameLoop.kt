package com.arcade.bubbledefense.engine.loop

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import kotlin.math.min

/**
 * Functional callback invoked at each fixed simulation step (`FIXED_DT = 1/120s`).
 */
fun interface FixedStepListener {
    fun onFixedStep(dt: Float, stepIndex: Long)
}

/**
 * Functional callback invoked at each rendered frame with the temporal interpolation alpha.
 */
fun interface FrameListener {
    fun onFrame(frameDt: Float, alpha: Float)
}

/**
 * Deterministic fixed-timestep game loop with accumulator and hit-stop time dilation.
 *
 * Implements the architecture specified in `docs/ARCHITECTURE.md` §3:
 * - Physics advances strictly in fixed steps `FIXED_DT = 1/120s`.
 * - Frame delta is clamped to `MAX_FRAME_TIME = 0.05s` to prevent the spiral of death.
 * - During Hit-Stop freeze frames (`hitStopTimer > 0`), the simulation accumulator pauses,
 *   freezing obstacle/shield motion while allowing shader glows and camera shake to decay smoothly.
 * - Render interpolation factor `alpha = accumulator / FIXED_DT` is supplied to [FrameListener].
 * - Can be driven by Compose `withFrameNanos` at runtime or stepped manually via [stepManual] in JVM tests.
 */
class FixedGameLoop(
    val world: GameWorld,
    private val fixedStepListener: FixedStepListener,
    private val frameListener: FrameListener? = null
) {
    /** Accumulated delta time waiting to be consumed by fixed simulation steps (s). */
    var accumulator: Float = 0f
        private set

    /** Total number of fixed simulation steps executed since inception. */
    var totalSteps: Long = 0L
        private set

    /** Monotonically increasing frame counter used for Compose Draw-phase invalidation. */
    var frameTick: Long = 0L
        private set

    /** Timestamp in nanoseconds of the previous frame. */
    private var lastFrameNanos: Long = 0L

    /** True if the loop is currently running and processing frames. */
    var isRunning: Boolean = false
        private set

    /**
     * Resets loop timing and accumulator state.
     */
    fun reset() {
        accumulator = 0f
        totalSteps = 0L
        frameTick = 0L
        lastFrameNanos = 0L
    }

    /**
     * Starts or resumes loop execution.
     */
    fun start(currentNanos: Long = 0L) {
        lastFrameNanos = currentNanos
        isRunning = true
    }

    /**
     * Pauses the loop.
     */
    fun pause() {
        isRunning = false
    }

    /**
     * Advances the loop by a single frame given the current wall-clock timestamp [frameTimeNanos].
     * Typically invoked by Compose `withFrameNanos` in production.
     */
    fun onFrame(frameTimeNanos: Long) {
        if (!isRunning) {
            lastFrameNanos = frameTimeNanos
            return
        }

        if (lastFrameNanos == 0L) {
            lastFrameNanos = frameTimeNanos
            return
        }

        val elapsedNanos = frameTimeNanos - lastFrameNanos
        lastFrameNanos = frameTimeNanos

        // Convert nanoseconds to seconds
        val frameDt = (elapsedNanos * 1e-9).toFloat()
        stepManual(frameDt)
    }

    /**
     * Advances the loop by [rawFrameDt] seconds.
     * Accessible directly by JVM tests to step simulation deterministically without display timing.
     */
    fun stepManual(rawFrameDt: Float) {
        // Spiral-of-death clamp
        val clampedDt = if (rawFrameDt < 0f) 0f else min(rawFrameDt, GameConfig.MAX_FRAME_TIME)

        // Handle Hit-Stop time dilation
        if (world.hitStopTimer > 0f) {
            world.hitStopTimer -= clampedDt
            if (world.hitStopTimer < 0f) {
                world.hitStopTimer = 0f
            }
            // Sim accumulator is frozen during hit-stop; render frame still executes
            frameTick++
            val alpha = if (GameConfig.FIXED_DT > 0f) (accumulator / GameConfig.FIXED_DT).coerceIn(0f, 1f) else 0f
            frameListener?.onFrame(clampedDt, alpha)
            return
        }

        // If game is paused for an external overlay (ad / shop), do not advance simulation
        if (world.state == GameState.PAUSED_FOR_EXTERNAL_OVERLAY) {
            frameTick++
            frameListener?.onFrame(clampedDt, 0f)
            return
        }

        // Scale time (e.g. slow-motion recovery or close call)
        val effectiveDt = clampedDt * world.timeScale
        accumulator += effectiveDt

        // Handle slow-mo timer decay
        if (world.slowMoTimer > 0f) {
            world.slowMoTimer -= clampedDt
            if (world.slowMoTimer <= 0f) {
                world.slowMoTimer = 0f
                world.timeScale = 1.0f
            }
        }

        // Execute fixed simulation steps
        var steps = 0
        while (accumulator >= GameConfig.FIXED_DT && steps < GameConfig.MAX_STEPS_PER_FRAME) {
            fixedStepListener.onFixedStep(GameConfig.FIXED_DT, totalSteps)
            totalSteps++
            world.fixedStepCount = totalSteps
            accumulator -= GameConfig.FIXED_DT
            steps++
        }

        // Calculate render interpolation factor
        val alpha = if (GameConfig.FIXED_DT > 0f) (accumulator / GameConfig.FIXED_DT).coerceIn(0f, 1f) else 0f

        // Invalidate render phase
        frameTick++
        frameListener?.onFrame(clampedDt, alpha)
    }
}
