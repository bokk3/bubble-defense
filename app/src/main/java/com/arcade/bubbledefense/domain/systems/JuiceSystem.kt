package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.math.Vec2
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sensory Amplification Matrix and Game Feel system ("Juice").
 *
 * Implements the tactile feedback loops specified in `docs/GAME_DESIGN.md` §4
 * and `kinetic-flow-architect/SKILL.md`.
 *
 * Responsibilities:
 * 1. Coordinates the 3-Tier Dopamine Clock feedback loops.
 * 2. Manages real-time Hit-Stop freeze frames (16ms-140ms) to accentuate impact crunch.
 * 3. Computes quadratic camera trauma shake (shake = trauma^2) driven by 18Hz smooth noise.
 * 4. Detects "Close Call" near-misses (< 80 wu to Bubble membrane), triggering 0.25x slow-mo.
 * 5. Provides Shepard-tone combo pitch multipliers: pitch = min(2.0, 1.0 + combo * 0.05).
 * 6. Dispatches directional particles inheriting reflected obstacle momentum.
 *
 * Zero-allocation guarantee: All calculations use primitive scalars with 0 heap boxing.
 */
class JuiceSystem(private val world: GameWorld) {

    companion object {
        private const val TWO_PI = (2.0 * PI).toFloat()
        private const val CLOSE_CALL_DISTANCE = 80f
        private const val CLOSE_CALL_SLOW_MO_DT = 0.080f
        private const val CLOSE_CALL_TIME_SCALE = 0.25f
        private const val FLAG_CLOSE_CALL_TRIGGERED = 1 shl 4
    }

    // Cached camera shake output (read by GameScreen / graphicsLayer)
    var shakeOffsetX: Float = 0f
        private set
    var shakeOffsetY: Float = 0f
        private set
    var shakeRotationRad: Float = 0f
        private set

    /**
     * Steps the sensory juice pipeline by [dt] seconds.
     */
    fun step(dt: Float) {
        // 1. Compute Camera Trauma Shake
        val trauma = world.trauma
        val intensity = trauma * trauma

        if (intensity > 0f) {
            val freq = GameConfig.SHAKE_NOISE_FREQUENCY_HZ
            val t = world.gameTime

            shakeOffsetX = sin(t * freq * TWO_PI) * intensity * GameConfig.MAX_SHAKE_OFFSET_PX
            shakeOffsetY = cos(t * freq * 1.3f * TWO_PI) * intensity * GameConfig.MAX_SHAKE_OFFSET_PX
            shakeRotationRad = sin(t * freq * 0.7f * TWO_PI) * intensity * GameConfig.MAX_SHAKE_ROTATION_RAD
        } else {
            shakeOffsetX = 0f
            shakeOffsetY = 0f
            shakeRotationRad = 0f
        }

        // 2. Close Call Proximity Detection (< 80 wu from Bubble)
        detectCloseCalls()
    }

    private fun detectCloseCalls() {
        val bx = world.bubbleX
        val by = world.bubbleY
        val threshold = world.bubbleRadius + CLOSE_CALL_DISTANCE
        val thresholdSq = threshold * threshold
        val bubbleReachSq = (world.bubbleRadius + 5f) * (world.bubbleRadius + 5f)

        val count = world.obstacles.count
        for (i in 0 until count) {
            val ox = world.obstacles.x[i]
            val oy = world.obstacles.y[i]
            val dx = ox - bx
            val dy = oy - by
            val distSq = dx * dx + dy * dy

            // Check if within close call reach, not overlapping bubble, and not already triggered
            val flags = world.obstacles.flags[i]
            val alreadyTriggered = (flags and FLAG_CLOSE_CALL_TRIGGERED) != 0

            if (distSq in (bubbleReachSq..thresholdSq) && !alreadyTriggered) {
                // Mark close call triggered on this obstacle
                world.obstacles.flags[i] = flags or FLAG_CLOSE_CALL_TRIGGERED

                // Trigger 80ms slow-mo (0.25x timeScale)
                world.timeScale = CLOSE_CALL_TIME_SCALE
                world.slowMoTimer = CLOSE_CALL_SLOW_MO_DT
                world.addTrauma(0.15f)

                // Spawn floating combat text & score
                world.floatingTexts.spawn(ox, oy, -60f, 0.45f, 1.15f, FloatingTextPool.TEXT_CLOSE_CALL)
                world.addScore(25)

                // Excite bubble wobble
                world.bubbleWobbleAmplitudes[0] = 0.08f
                world.bubbleWobbleAmplitudes[1] = 0.05f
            }
        }
    }

    /**
     * Calculates the Shepard-tone audio pitch scale factor for the current combo level.
     * Starts at 1.0x and increases by +0.05x per combo level up to a maximum of 2.0x.
     */
    fun getComboPitch(combo: Int): Float = (1.0f + combo * 0.05f).coerceAtMost(2.0f)

    /**
     * Dispatches directional impact particles inheriting obstacle reflection velocity.
     */
    fun spawnDirectionalSparks(
        x: Float,
        y: Float,
        exitVelocity: Vec2,
        normal: Vec2,
        count: Int,
        isParry: Boolean
    ) {
        val color = if (isParry) 0xFF00F0FF.toInt() else 0xFFFFB800.toInt()
        val speed = exitVelocity.length()

        for (p in 0 until count) {
            val angle = ((p.toFloat() / count.toFloat()) - 0.5f) * 1.8f
            val dir = normal.rotated(angle)
            val pSpeed = speed * (0.6f + (p % 3) * 0.25f)

            world.particles.spawn(
                posX = x,
                posY = y,
                velX = dir.x * pSpeed,
                velY = dir.y * pSpeed,
                lifetime = if (isParry) 0.38f else 0.20f,
                particleSize = if (isParry) 5f else 3f,
                rot = angle,
                argbColor = color
            )
        }
    }

    /** Triggers feedback on Perfect Parry. */
    fun onParry() {
        world.triggerHitStop(GameConfig.HIT_STOP_PARRY_DURATION)
        world.addTrauma(GameConfig.TRAUMA_PARRY)
    }

    /** Triggers feedback on Crusher impact. */
    fun onCrusherHit() {
        world.triggerHitStop(GameConfig.HIT_STOP_CRUSHER_DURATION)
        world.addTrauma(GameConfig.TRAUMA_CRUSHER)
    }

    /** Triggers feedback on Bubble Pop / Game Over. */
    fun onBubblePop() {
        world.triggerHitStop(GameConfig.HIT_STOP_POP_DURATION)
        world.addTrauma(GameConfig.TRAUMA_POP)
    }
}
