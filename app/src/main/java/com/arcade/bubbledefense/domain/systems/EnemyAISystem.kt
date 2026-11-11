package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import kotlin.math.*

/**
 * High-performance zero-allocation Predatory Enemy AI System.
 *
 * Drives active multi-axis spatial maneuvers, telegraphing charge lines, gravitational
 * field distortions, and flocking swarms for predatory deep-space enemies:
 * - [GameConfig.TYPE_VOID_STALKER]: Flanks, orbits, and executes supersonic lunges.
 * - [GameConfig.TYPE_SNIPER_PLATFORM]: Anchors at horizon, projects targeting laser, fires railgun spike.
 * - [GameConfig.TYPE_GRAVITY_WEAVER]: Distorts local gravitational field, curving incoming debris.
 * - [GameConfig.TYPE_SWARMER]: Flocks toward bubble in erratic sinusoidal paths.
 *
 * Hot-path guarantee: 0 heap allocations per update.
 */
class EnemyAISystem(private val world: GameWorld) {

    companion object {
        const val STATE_STALKER_APPROACH: Byte = 0
        const val STATE_STALKER_ORBIT: Byte = 1
        const val STATE_STALKER_LUNGE: Byte = 2

        const val STATE_SNIPER_CHARGE: Byte = 0
        const val STATE_SNIPER_FIRE: Byte = 1
        const val STATE_SNIPER_RETREAT: Byte = 2
    }

    private val capacity = GameConfig.OBSTACLE_POOL_CAPACITY

    // Pre-allocated SoA arrays for AI state machine (1:1 with ObstaclePool)
    val aiState = ByteArray(capacity)
    val aiTimer = FloatArray(capacity)
    val aiCharge = FloatArray(capacity) // 0.0 to 1.0 for telegraph beams
    val aiTargetX = FloatArray(capacity)
    val aiTargetY = FloatArray(capacity)

    fun reset() {
        for (i in 0 until capacity) {
            aiState[i] = 0
            aiTimer[i] = 0f
            aiCharge[i] = 0f
            aiTargetX[i] = 0f
            aiTargetY[i] = 0f
        }
    }

    /**
     * Called when an obstacle is removed via swap-remove in [GameWorld].
     * Synchronizes the dense SoA packing in O(1) time.
     */
    fun onSwapRemove(index: Int, last: Int) {
        if (index != last && index in 0 until capacity && last in 0 until capacity) {
            aiState[index] = aiState[last]
            aiTimer[index] = aiTimer[last]
            aiCharge[index] = aiCharge[last]
            aiTargetX[index] = aiTargetX[last]
            aiTargetY[index] = aiTargetY[last]
        }
        if (last in 0 until capacity) {
            aiState[last] = 0
            aiTimer[last] = 0f
            aiCharge[last] = 0f
        }
    }

    /**
     * Initializes AI state when a new predatory obstacle is spawned at [index].
     */
    fun onSpawn(index: Int, type: Byte, targetX: Float, targetY: Float) {
        if (index !in 0 until capacity) return
        aiState[index] = 0
        aiTimer[index] = 0f
        aiCharge[index] = 0f
        aiTargetX[index] = targetX
        aiTargetY[index] = targetY
    }

    /**
     * Updates all active predatory enemies for the current fixed timestep.
     */
    fun update(dt: Float, bubbleX: Float, bubbleY: Float) {
        val pool = world.obstacles
        val count = pool.count

        for (i in 0 until count) {
            val type = pool.type[i]

            // Parried / deflected enemies have AI disabled
            val flags = pool.flags[i]
            if ((flags and (GameConfig.FLAG_PARRIED or GameConfig.FLAG_DEFLECTED)) != 0) {
                aiCharge[i] = 0f
                continue
            }

            when (type) {
                GameConfig.TYPE_VOID_STALKER -> updateVoidStalker(i, dt, bubbleX, bubbleY)
                GameConfig.TYPE_SNIPER_PLATFORM -> updateSniperPlatform(i, dt, bubbleX, bubbleY)
                GameConfig.TYPE_GRAVITY_WEAVER -> updateGravityWeaver(i, dt)
                GameConfig.TYPE_SWARMER -> updateSwarmer(i, dt, bubbleX)
            }
        }
    }

    private fun updateVoidStalker(i: Int, dt: Float, bx: Float, by: Float) {
        val pool = world.obstacles
        val px = pool.x[i]
        val py = pool.y[i]
        val timer = aiTimer[i] + dt
        aiTimer[i] = timer

        when (aiState[i]) {
            STATE_STALKER_APPROACH -> {
                // Fly from outer flank toward orbital staging ring (~360 wu from bubble)
                val dx = bx - px
                val dy = by - py
                val dist = sqrt(dx * dx + dy * dy)

                if (dist <= 380f || timer >= 1.5f) {
                    aiState[i] = STATE_STALKER_ORBIT
                    aiTimer[i] = 0f
                } else {
                    val invD = 1f / max(1f, dist)
                    pool.vx[i] = dx * invD * 280f
                    pool.vy[i] = dy * invD * 280f
                }
            }

            STATE_STALKER_ORBIT -> {
                // Orbit the bubble at distance while charging lunge thrusters
                val dx = px - bx
                val dy = py - by
                val currentAngle = atan2(dy, dx)
                val orbitRadius = 350f
                val orbitSpeed = 1.6f // rad/s
                val nextAngle = currentAngle + orbitSpeed * dt

                val targetX = bx + cos(nextAngle) * orbitRadius
                val targetY = by + sin(nextAngle) * orbitRadius
                pool.vx[i] = (targetX - px) * 8f
                pool.vy[i] = (targetY - py) * 8f

                // Charge telegraph intensity
                val chargeTime = 1.30f
                val chargeFrac = (timer / chargeTime).coerceIn(0f, 1f)
                aiCharge[i] = chargeFrac

                if (timer >= chargeTime) {
                    aiState[i] = STATE_STALKER_LUNGE
                    aiTimer[i] = 0f
                    aiCharge[i] = 1.0f

                    // Lock lunge vector straight at the bubble
                    val ldx = bx - px
                    val ldy = by - py
                    val ldist = sqrt(ldx * ldx + ldy * ldy)
                    val invL = 1f / max(1f, ldist)
                    pool.vx[i] = ldx * invL * GameConfig.STALKER_LUNGE_SPEED
                    pool.vy[i] = ldy * invL * GameConfig.STALKER_LUNGE_SPEED
                }
            }

            STATE_STALKER_LUNGE -> {
                // Maintain high-velocity lunge
                aiCharge[i] = max(0f, 1.0f - timer * 1.5f)
            }
        }
    }

    private fun updateSniperPlatform(i: Int, dt: Float, bx: Float, by: Float) {
        val pool = world.obstacles
        val px = pool.x[i]
        val py = pool.y[i]
        val timer = aiTimer[i] + dt
        aiTimer[i] = timer

        when (aiState[i]) {
            STATE_SNIPER_CHARGE -> {
                // Decelerate to near halt at upper horizon
                pool.vx[i] *= 0.92f
                pool.vy[i] = max(12f, pool.vy[i] * 0.90f)

                // Track laser target towards bubble
                aiTargetX[i] = bx
                aiTargetY[i] = by

                val chargeDuration = GameConfig.SNIPER_CHARGE_TIME
                val chargeFrac = (timer / chargeDuration).coerceIn(0f, 1f)
                aiCharge[i] = chargeFrac

                if (timer >= chargeDuration) {
                    aiState[i] = STATE_SNIPER_FIRE
                    aiTimer[i] = 0f
                    aiCharge[i] = 1.0f

                    // Spawn supersonic piercing kinetic spike
                    val dx = bx - px
                    val dy = by - py
                    val dist = sqrt(dx * dx + dy * dy)
                    val invD = 1f / max(1f, dist)
                    val spikeSpeed = GameConfig.SNIPER_SPIKE_SPEED

                    val spikeIdx = pool.spawn(
                        posX = px,
                        posY = py + pool.radius[i] + 4f,
                        velX = dx * invD * spikeSpeed,
                        velY = dy * invD * spikeSpeed,
                        colRadius = 16f,
                        mass = 0.6f,
                        colRestitution = 0.90f,
                        obstacleType = GameConfig.TYPE_NEEDLE,
                        obstacleFlags = GameConfig.FLAG_CCD
                    )
                    if (spikeIdx >= 0) {
                        onSpawn(spikeIdx, GameConfig.TYPE_NEEDLE, bx, by)
                    }
                }
            }

            STATE_SNIPER_FIRE -> {
                aiCharge[i] = max(0f, 1.0f - timer * 4f)
                if (timer >= 0.35f) {
                    aiState[i] = STATE_SNIPER_RETREAT
                    aiTimer[i] = 0f
                }
            }

            STATE_SNIPER_RETREAT -> {
                aiCharge[i] = 0f
                // Drift outward
                pool.vx[i] += (if (px < 500f) -40f else 40f) * dt
                pool.vy[i] += 80f * dt
            }
        }
    }

    private fun updateGravityWeaver(weaverIdx: Int, dt: Float) {
        val pool = world.obstacles
        val wx = pool.x[weaverIdx]
        val wy = pool.y[weaverIdx]
        val range = GameConfig.WEAVER_GRAVITY_RANGE
        val rangeSq = range * range
        val strength = GameConfig.WEAVER_GRAVITY_STRENGTH

        // Distort nearby active obstacles toward weaver
        val count = pool.count
        for (j in 0 until count) {
            if (j == weaverIdx) continue
            // Skip parried/deflected entities
            val f = pool.flags[j]
            if ((f and (GameConfig.FLAG_PARRIED or GameConfig.FLAG_DEFLECTED)) != 0) continue

            val ox = pool.x[j]
            val oy = pool.y[j]
            val dx = wx - ox
            val dy = wy - oy
            val dSq = dx * dx + dy * dy

            if (dSq in 1f..rangeSq) {
                val dist = sqrt(dSq)
                val pull = (1f - dist / range) * strength * dt
                val invD = 1f / dist
                pool.vx[j] += dx * invD * pull
                pool.vy[j] += dy * invD * pull
            }
        }
    }

    private fun updateSwarmer(i: Int, dt: Float, bx: Float) {
        val pool = world.obstacles
        val px = pool.x[i]
        val age = pool.age[i]
        val seed = pool.seed[i].toFloat()

        // Sinusoidal undulating wave with gentle tracking toward bubble X
        val waveVx = sin(age * 6.5f + seed * 0.1f) * 200f
        val trackVx = (bx - px).coerceIn(-120f, 120f)
        pool.vx[i] = waveVx + trackVx * 0.4f
        pool.vy[i] = GameConfig.SWARMER_SPEED
    }
}
