package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.audio.VoidSynthEngine
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Autonomous tactical defense drones operating along the sideline corridor rails.
 *
 * Enforces Zero-Allocation hot-loop constraints (`01-zero-alloc-hot-loop`):
 * - Target acquisition and beam tracking executed via dense primitive arrays.
 * - Targets hostile threats prioritizing lowest distance to defense bubble ($Y \approx 1476\text{ wu}$).
 * - Ignores friendly [GameConfig.TYPE_PRISM_CARRIER] bounties to preserve harvest opportunities.
 * - Delivers kinetic impulse knockback and disintegrates fragile hazards (Needles, Shards).
 */
class DroneSystem(
    val world: GameWorld,
    var synth: VoidSynthEngine? = null
) {
    companion object {
        const val MAX_DRONES = 2

        // Drone 0 = Left sideline shoulder, Drone 1 = Right sideline shoulder
        val DRONE_BASE_X = floatArrayOf(75f, 925f)
        const val DRONE_BASE_Y = 1320f
    }

    // Per-drone primitive state (guaranteed zero heap allocations)
    val droneX = FloatArray(MAX_DRONES)
    val droneY = FloatArray(MAX_DRONES)
    val droneAngle = FloatArray(MAX_DRONES)
    val fireCooldown = FloatArray(MAX_DRONES)
    val beamTimer = FloatArray(MAX_DRONES)
    val beamTargetX = FloatArray(MAX_DRONES)
    val beamTargetY = FloatArray(MAX_DRONES)
    val hasTarget = BooleanArray(MAX_DRONES)
    val targetIndex = IntArray(MAX_DRONES)

    init {
        reset()
    }

    /**
     * Resets all drone positions and weapons systems to idle ready state.
     */
    fun reset() {
        for (i in 0 until MAX_DRONES) {
            droneX[i] = DRONE_BASE_X[i]
            droneY[i] = DRONE_BASE_Y
            droneAngle[i] = 0f
            fireCooldown[i] = 0.4f + i * 0.35f
            beamTimer[i] = 0f
            beamTargetX[i] = 0f
            beamTargetY[i] = 0f
            hasTarget[i] = false
            targetIndex[i] = -1
        }
    }

    /**
     * Steps drone hover dynamics, targeting radar, and weapons systems.
     * Zero heap allocations.
     */
    fun step(dt: Float) {
        val level = world.droneLevel
        if (level <= 0) return

        val activeDroneCount = if (level >= 2) 2 else 1
        val fireInterval = when (level) {
            1 -> GameConfig.DRONE_FIRE_INTERVAL_L1
            2 -> GameConfig.DRONE_FIRE_INTERVAL_L2
            else -> GameConfig.DRONE_FIRE_INTERVAL_L3
        }

        val time = world.gameTime

        // If disrupted by solar EMP coronal surge, reboot drones without targeting
        if (world.droneDisruptedTimer > 0f) {
            world.droneDisruptedTimer -= dt
            if (world.droneDisruptedTimer < 0f) world.droneDisruptedTimer = 0f
            for (d in 0 until activeDroneCount) {
                droneX[d] = DRONE_BASE_X[d] + sin(time * 6f + d) * 3f // Shuddering reboot
                droneY[d] = DRONE_BASE_Y + sin(time * 4f + d) * 4f
                droneAngle[d] = sin(time * 12f + d) * 0.08f
                hasTarget[d] = false
                targetIndex[d] = -1
                beamTimer[d] = 0f
            }
            return
        }

        for (d in 0 until activeDroneCount) {
            // Hover animation: smooth zero-g sine-wave bobbing
            droneX[d] = DRONE_BASE_X[d] + sin(time * 3f + d * PI.toFloat()) * 8f
            droneY[d] = DRONE_BASE_Y + sin(time * 2.2f + d * 1.5f) * 16f
            droneAngle[d] = sin(time * 4f + d) * 0.15f

            // Decay active laser beam flash
            if (beamTimer[d] > 0f) {
                beamTimer[d] -= dt
                if (beamTimer[d] < 0f) beamTimer[d] = 0f
            }

            // Target acquisition: find closest hostile threat to bubble target
            var bestIdx = -1
            var bestDistToBubble = Float.MAX_VALUE

            val obsCount = world.obstacles.count
            for (i in 0 until obsCount) {
                val oType = world.obstacles.type[i]
                if (oType == GameConfig.TYPE_PRISM_CARRIER || oType == GameConfig.TYPE_ARMOR_CRYSTAL) continue // Preserve friendly bounties & armor crystals!

                val oy = world.obstacles.y[i]
                if (oy < GameConfig.DRONE_TARGET_RANGE_MIN_Y || oy > GameConfig.DRONE_TARGET_RANGE_MAX_Y) continue

                val ox = world.obstacles.x[i]
                // Left drone prefers left sector (X <= 600), Right drone prefers right sector (X >= 400)
                if (d == 0 && ox > 600f && activeDroneCount > 1) continue
                if (d == 1 && ox < 400f && activeDroneCount > 1) continue

                val distToBubble = (1476f - oy)
                if (distToBubble in 0f..bestDistToBubble) {
                    bestDistToBubble = distToBubble
                    bestIdx = i
                }
            }

            if (bestIdx != -1) {
                hasTarget[d] = true
                targetIndex[d] = bestIdx
                beamTargetX[d] = world.obstacles.x[bestIdx]
                beamTargetY[d] = world.obstacles.y[bestIdx]

                // Fire weapon when cooldown ready
                fireCooldown[d] -= dt
                if (fireCooldown[d] <= 0f) {
                    fireCooldown[d] = fireInterval
                    fireLaserAtTarget(d, bestIdx)
                }
            } else {
                hasTarget[d] = false
                targetIndex[d] = -1
                if (fireCooldown[d] > 0f) {
                    fireCooldown[d] -= dt
                }
            }
        }
    }

    private fun fireLaserAtTarget(droneIdx: Int, obsIdx: Int) {
        beamTimer[droneIdx] = GameConfig.DRONE_BEAM_DURATION
        val tx = world.obstacles.x[obsIdx]
        val ty = world.obstacles.y[obsIdx]
        beamTargetX[droneIdx] = tx
        beamTargetY[droneIdx] = ty

        // Trigger procedural laser audio chirp
        synth?.triggerPluck(660f, velocity = 0.85f)

        // Apply kinetic impulse knockback and damage
        val oType = world.obstacles.type[obsIdx]
        val r = world.obstacles.radius[obsIdx]

        // Kinetic upward knockback away from bubble
        world.obstacles.vy[obsIdx] -= GameConfig.DRONE_KNOCKBACK_FORCE

        // If fragile hazard (Needle, Shard) or small radius, disintegrate!
        if (oType == GameConfig.TYPE_NEEDLE || oType == GameConfig.TYPE_SHARD || r <= 16f) {
            spawnDroneImpactSparks(tx, ty)
            world.addScore(150)
            world.floatingTexts.spawn(tx, ty - 10f, -60f, 0.35f, 1.1f, FloatingTextPool.TEXT_PLUS_50)
            world.obstacles.removeAt(obsIdx)
        } else {
            // Shrink obstacle radius & deal damage
            world.obstacles.radius[obsIdx] = (r * 0.75f).coerceAtLeast(12f)
            spawnDroneImpactSparks(tx, ty)
            world.addScore(75)
        }
    }

    private fun spawnDroneImpactSparks(x: Float, y: Float) {
        val count = 10
        val pPool = world.particles
        for (i in 0 until count) {
            val angle = (i * (2.0 * PI / count)).toFloat()
            val speed = 120f + (i % 3) * 40f
            pPool.spawn(
                posX = x,
                posY = y,
                velX = cos(angle) * speed,
                velY = sin(angle) * speed,
                lifetime = 0.30f,
                particleSize = 3.5f,
                rot = angle,
                argbColor = 0xFF00F0FF.toInt() // Cyan plasma sparks
            )
        }
    }
}
