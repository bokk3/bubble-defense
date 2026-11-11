package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import com.arcade.bubbledefense.engine.pool.InputRingBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-refresh gesture input pipeline and shield segment lifecycle system.
 *
 * Responsibilities:
 * 1. Consumes 120Hz-240Hz pointer motion samples from [GameWorld.inputRing].
 * 2. Filters raw touch velocities using an Exponential Moving Average (EMA, tau = 20ms)
 *    to preserve swipe momentum for the parry mechanic.
 * 3. Enforces distance decimation ([GameConfig.MIN_SEGMENT_LENGTH] = 12 wu) to prevent zero-length
 *    degenerate lines during slow finger movements.
 * 4. Subdivides long strokes ([GameConfig.MAX_SEGMENT_LENGTH] = 48 wu) to maintain dense collision sampling.
 * 5. Manages ink consumption, cooldown gating (0.4s cooldown on finger lift), and empty-tank truncation.
 * 6. Advances shield segment ages and triggers procedural shatter particles on structural failure.
 *
 * Zero-allocation guarantee: All state is tracked in primitive arrays; hot paths allocate 0 heap objects.
 */
class ShieldInputSystem(private val world: GameWorld) {

    companion object {
        const val MAX_POINTERS: Int = 4
        private const val MIN_SEGMENT_LEN_SQ: Float = GameConfig.MIN_SEGMENT_LENGTH * GameConfig.MIN_SEGMENT_LENGTH
        private const val MAX_SWIPE_SPEED_SQ: Float = GameConfig.MAX_SWIPE_SPEED * GameConfig.MAX_SWIPE_SPEED
    }

    // Pre-allocated tracking state for up to MAX_POINTERS simultaneous touches
    private val pointerActive = BooleanArray(MAX_POINTERS)
    private val pointerRawId = IntArray(MAX_POINTERS) { -1 }
    private val pointerLastX = FloatArray(MAX_POINTERS)
    private val pointerLastY = FloatArray(MAX_POINTERS)
    private val pointerLastNanos = LongArray(MAX_POINTERS)
    private val pointerEmaVx = FloatArray(MAX_POINTERS)
    private val pointerEmaVy = FloatArray(MAX_POINTERS)
    private val pointerStrokeId = IntArray(MAX_POINTERS)

    private var nextStrokeId: Int = 1

    /**
     * Advances the input system and shield segment lifecycles by [dt] seconds.
     * Consumes all queued touch events from the world input ring buffer.
     */
    fun step(dt: Float) {
        // 1. Drain all pending pointer events from the ring buffer
        while (world.inputRing.size > 0) {
            val idx = world.inputRing.peek()
            if (idx < 0) break
            processTouchSample(
                world.inputRing.x[idx],
                world.inputRing.y[idx],
                world.inputRing.timestampNanos[idx],
                world.inputRing.pointerId[idx],
                world.inputRing.action[idx]
            )
            world.inputRing.pop()
        }

        // 2. Ink Regeneration Logic:
        // Ink only regenerates when NO fingers are actively drawing and the cooldown timer has elapsed.
        if (world.activePointerCount <= 0) {
            if (world.inkCooldownTimer > 0f) {
                world.inkCooldownTimer -= dt
                if (world.inkCooldownTimer < 0f) {
                    world.inkCooldownTimer = 0f
                }
            } else if (world.ink < GameConfig.INK_MAX) {
                val regenMultiplier = if (world.solarFlareState == 2) GameConfig.SOLAR_FLARE_INK_SLOW_RATIO else 1.0f
                world.ink = min(GameConfig.INK_MAX, world.ink + (GameConfig.INK_REGEN_RATE * regenMultiplier) * dt)
            }
        }

        // 3. Shield Segment Aging & Decay:
        // Segments naturally expire after SEGMENT_LIFETIME (1.5s) or shatter when integrity reaches <= 0.
        var i = 0
        while (i < world.shieldSegments.count) {
            world.shieldSegments.age[i] += dt
            if (world.shieldSegments.flashTimer[i] > 0f) {
                world.shieldSegments.flashTimer[i] -= dt
                if (world.shieldSegments.flashTimer[i] < 0f) {
                    world.shieldSegments.flashTimer[i] = 0f
                }
            }
            if (world.shieldSegments.rippleTimer[i] > 0f) {
                world.shieldSegments.rippleTimer[i] -= dt
                if (world.shieldSegments.rippleTimer[i] < 0f) {
                    world.shieldSegments.rippleTimer[i] = 0f
                }
            }

            val isExpired = world.shieldSegments.age[i] >= GameConfig.SEGMENT_LIFETIME
            val isBroken = world.shieldSegments.integrity[i] <= 0f

            if (isExpired || isBroken) {
                if (isBroken) {
                    spawnShatterParticles(i)
                }
                // O(1) swap-remove replaces slot i with the last element; re-inspect slot i
                world.shieldSegments.removeAt(i)
            } else {
                i++
            }
        }
    }

    /**
     * Processes a single touch sample in world coordinates.
     */
    fun processTouchSample(
        x: Float,
        y: Float,
        timestampNanos: Long,
        pointer: Int,
        action: Byte
    ) {
        when (action) {
            InputRingBuffer.ACTION_DOWN -> handleActionDown(x, y, timestampNanos, pointer)
            InputRingBuffer.ACTION_MOVE -> handleActionMove(x, y, timestampNanos, pointer)
            InputRingBuffer.ACTION_UP,
            InputRingBuffer.ACTION_CANCEL -> handleActionUpOrCancel(pointer)
        }
    }

    private fun handleActionDown(x: Float, y: Float, timestampNanos: Long, pointer: Int) {
        // 1. Direct tactile tap check on active Green Armor Crystals in time
        if (checkArmorCrystalTap(x, y)) {
            return // Tapped in time! Crystal absorbed, no shield line initiated
        }

        var slot = findSlotForPointer(pointer)
        if (slot < 0) {
            slot = findAvailableSlot()
            if (slot < 0) return // Max simultaneous pointers exceeded
            world.activePointerCount++
        }

        pointerActive[slot] = true
        pointerRawId[slot] = pointer
        pointerLastX[slot] = x
        pointerLastY[slot] = y
        pointerLastNanos[slot] = timestampNanos
        pointerEmaVx[slot] = 0f
        pointerEmaVy[slot] = 0f
        pointerStrokeId[slot] = nextStrokeId++

        // Reset ink cooldown timer on finger contact
        world.inkCooldownTimer = GameConfig.INK_REGEN_DELAY
    }

    private fun checkArmorCrystalTap(tx: Float, ty: Float): Boolean {
        val count = world.obstacles.count
        for (i in 0 until count) {
            if (world.obstacles.type[i] == GameConfig.TYPE_ARMOR_CRYSTAL) {
                val ox = world.obstacles.x[i]
                val oy = world.obstacles.y[i]
                // Valid timing window: in playable corridor
                if (oy in GameConfig.ARMOR_CRYSTAL_MIN_TAP_Y..GameConfig.ARMOR_CRYSTAL_MAX_TAP_Y) {
                    val dx = tx - ox
                    val dy = ty - oy
                    val hitRadius = world.obstacles.radius[i] + GameConfig.ARMOR_CRYSTAL_TAP_TOLERANCE
                    if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                        onArmorCrystalTapped(i, ox, oy)
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun onArmorCrystalTapped(index: Int, x: Float, y: Float) {
        // Nano-repair burst: restore 1 Shield Core HP or Overshield
        if (world.bubbleHp < GameConfig.BUBBLE_MAX_HP) {
            world.bubbleHp++
            world.floatingTexts.spawn(x, y - 20f, -80f, 0.45f, 1.4f, FloatingTextPool.TEXT_BOUNTY)
        } else {
            world.overshieldHp = min(GameConfig.MAX_OVERSHIELD_HP, world.overshieldHp + 1)
            world.floatingTexts.spawn(x, y - 20f, -80f, 0.45f, 1.4f, FloatingTextPool.TEXT_PLUS_100)
        }
        world.addScore(300)

        // Spawn emerald nano-particles
        val pCount = 14
        for (p in 0 until pCount) {
            val angle = (p * (2.0 * PI / pCount)).toFloat()
            val speed = 100f + (p % 4) * 35f
            world.particles.spawn(
                posX = x,
                posY = y,
                velX = cos(angle) * speed,
                velY = sin(angle) * speed,
                lifetime = 0.45f,
                particleSize = 4f,
                rot = angle,
                argbColor = 0xFF00FF99.toInt() // Radiant emerald
            )
        }

        // Remove crystal from pool
        world.obstacles.removeAt(index)
    }

    private fun handleActionMove(x: Float, y: Float, timestampNanos: Long, pointer: Int) {
        val slot = findSlotForPointer(pointer)
        if (slot < 0 || !pointerActive[slot]) return

        val lastX = pointerLastX[slot]
        val lastY = pointerLastY[slot]
        val dx = x - lastX
        val dy = y - lastY
        val distSq = dx * dx + dy * dy

        // Minimum distance decimation: skip micro-jitter below threshold
        if (distSq < MIN_SEGMENT_LEN_SQ) return

        val dist = sqrt(distSq)
        val elapsedNanos = timestampNanos - pointerLastNanos[slot]
        val dt = if (elapsedNanos > 0L) (elapsedNanos * 1e-9).toFloat() else 0.008f

        // Compute raw velocity
        var rawVx = dx / dt
        var rawVy = dy / dt
        val rawSpeedSq = rawVx * rawVx + rawVy * rawVy
        if (rawSpeedSq > MAX_SWIPE_SPEED_SQ) {
            val scale = GameConfig.MAX_SWIPE_SPEED / sqrt(rawSpeedSq)
            rawVx *= scale
            rawVy *= scale
        }

        // Exponential Moving Average (EMA) filter: alpha = dt / (tau + dt)
        val alpha = (dt / (GameConfig.SWIPE_VELOCITY_TAU + dt)).coerceIn(0f, 1f)
        val emaVx = pointerEmaVx[slot] * (1f - alpha) + rawVx * alpha
        val emaVy = pointerEmaVy[slot] * (1f - alpha) + rawVy * alpha
        pointerEmaVx[slot] = emaVx
        pointerEmaVy[slot] = emaVy

        // Determine subdivision count if segment exceeds MAX_SEGMENT_LENGTH
        val subdivisions = if (dist > GameConfig.MAX_SEGMENT_LENGTH) {
            ceil(dist / GameConfig.MAX_SEGMENT_LENGTH).toInt()
        } else {
            1
        }

        val stepLen = dist / subdivisions.toFloat()
        val invDist = 1f / dist
        val dirX = dx * invDist
        val dirY = dy * invDist

        var curStartX = lastX
        var curStartY = lastY
        val stroke = pointerStrokeId[slot]

        for (k in 0 until subdivisions) {
            if (world.ink <= 0f) {
                // Out of ink: stop emitting segments for this stroke
                curStartX = x
                curStartY = y
                break
            }

            val segmentCost = stepLen * GameConfig.INK_PER_WU
            if (world.ink >= segmentCost) {
                // Sufficient ink for full step
                val curEndX = if (k == subdivisions - 1) x else curStartX + dirX * stepLen
                val curEndY = if (k == subdivisions - 1) y else curStartY + dirY * stepLen

                world.shieldSegments.spawn(
                    curStartX, curStartY,
                    curEndX, curEndY,
                    emaVx, emaVy,
                    stroke, pointer
                )

                world.ink -= segmentCost
                curStartX = curEndX
                curStartY = curEndY
            } else {
                // Partial ink remaining: truncate segment to remaining capacity
                val availableLength = world.ink / GameConfig.INK_PER_WU
                if (availableLength >= GameConfig.MIN_SEGMENT_LENGTH) {
                    val curEndX = curStartX + dirX * availableLength
                    val curEndY = curStartY + dirY * availableLength

                    world.shieldSegments.spawn(
                        curStartX, curStartY,
                        curEndX, curEndY,
                        emaVx, emaVy,
                        stroke, pointer
                    )
                }
                world.ink = 0f
                curStartX = x
                curStartY = y
                break
            }
        }

        pointerLastX[slot] = curStartX
        pointerLastY[slot] = curStartY
        pointerLastNanos[slot] = timestampNanos

        // Reset cooldown timer on active drawing
        world.inkCooldownTimer = GameConfig.INK_REGEN_DELAY
    }

    private fun handleActionUpOrCancel(pointer: Int) {
        val slot = findSlotForPointer(pointer)
        if (slot >= 0 && pointerActive[slot]) {
            pointerActive[slot] = false
            pointerRawId[slot] = -1
            world.activePointerCount = max(0, world.activePointerCount - 1)
            world.inkCooldownTimer = GameConfig.INK_REGEN_DELAY
        }
    }

    private fun spawnShatterParticles(segmentIdx: Int) {
        val ax = world.shieldSegments.ax[segmentIdx]
        val ay = world.shieldSegments.ay[segmentIdx]
        val bx = world.shieldSegments.bx[segmentIdx]
        val by = world.shieldSegments.by[segmentIdx]
        val nx = world.shieldSegments.nx[segmentIdx]
        val ny = world.shieldSegments.ny[segmentIdx]

        val count = 8
        val invCount = 1f / (count + 1).toFloat()

        for (p in 1..count) {
            val t = p * invCount
            val px = ax + (bx - ax) * t
            val py = ay + (by - ay) * t

            // Random-like spread along normal and tangent
            val normalSpread = ((p % 3) - 1) * 60f
            val tangSpread = ((p % 5) - 2) * 40f
            val vx = nx * normalSpread - ny * tangSpread
            val vy = ny * normalSpread + nx * tangSpread

            world.particles.spawn(
                posX = px,
                posY = py,
                velX = vx,
                velY = vy,
                lifetime = 0.35f,
                particleSize = 8f,
                rot = 0f,
                argbColor = 0xFFFF0055.toInt() // Magenta shatter
            )
        }
    }

    private fun findSlotForPointer(pointer: Int): Int {
        for (i in 0 until MAX_POINTERS) {
            if (pointerActive[i] && pointerRawId[i] == pointer) return i
        }
        return -1
    }

    private fun findAvailableSlot(): Int {
        for (i in 0 until MAX_POINTERS) {
            if (!pointerActive[i]) return i
        }
        return -1
    }

    /** Resets all active pointer tracking slots. */
    fun reset() {
        for (i in 0 until MAX_POINTERS) {
            pointerActive[i] = false
            pointerRawId[i] = -1
            pointerLastX[i] = 0f
            pointerLastY[i] = 0f
            pointerLastNanos[i] = 0L
            pointerEmaVx[i] = 0f
            pointerEmaVy[i] = 0f
            pointerStrokeId[i] = 0
        }
        nextStrokeId = 1
    }
}
