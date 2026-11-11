package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.math.CollisionMath
import com.arcade.bubbledefense.engine.math.Contact
import com.arcade.bubbledefense.engine.math.Vec2
import com.arcade.bubbledefense.engine.pool.FloatingTextPool
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic physics simulation and narrowphase collision solver.
 *
 * Runs at 480Hz narrowphase resolution (4 sub-steps per 120Hz fixed frame step).
 *
 * Responsibilities:
 * 1. Semi-implicit Euler integration with gravity and terminal velocity clamping.
 * 2. Uniform grid broadphase (10x18 cells, 100 wu cell size) for O(1) candidate query.
 * 3. Exact Circle vs Capsule narrowphase collision resolution using [CollisionMath].
 * 4. Anti-sinking positional projection clamped to [GameConfig.MAX_PUSHOUT].
 * 5. Velocity impulse reflection incorporating active swipe velocity transfer (Parry).
 * 6. Archetype mechanics: Crusher 60% integrity drain & recoil, Splitter diamond fracturing.
 * 7. Bubble collision detection, damage, invulnerability timers, and Game Over transition.
 * 8. Particle and floating combat text physics updates.
 *
 * Zero-allocation guarantee: All broadphase and collision state is pre-allocated; 0 heap allocations per step.
 */
class PhysicsSystem(private val world: GameWorld) {

    companion object {
        private const val GRID_COLS = 10
        private const val GRID_ROWS = 18
        private const val TOTAL_CELLS = GRID_COLS * GRID_ROWS
        private const val CELL_SIZE = 100f
        private const val INV_CELL_SIZE = 1f / CELL_SIZE
        private const val MAX_GRID_LINKS = 1024
        private const val MAX_SPEED_SQ = GameConfig.MAX_OBSTACLE_SPEED * GameConfig.MAX_OBSTACLE_SPEED
        private const val SPLIT_ANGLE_RAD = 0.4363323f // 25 degrees
        private const val SPLIT_SPEED_MULT = 1.4f
    }

    // Reusable stateless contact record
    private val contact = Contact()

    // Pre-allocated Broadphase Uniform Grid arrays
    private val cellHead = IntArray(TOTAL_CELLS) { -1 }
    private val linkNext = IntArray(MAX_GRID_LINKS)
    private val linkSegmentIdx = IntArray(MAX_GRID_LINKS)
    private var linkCount: Int = 0

    // Duplicate segment check stamp
    private val visitStamp = IntArray(GameConfig.SHIELD_SEGMENT_POOL_CAPACITY)
    private var currentQueryStamp: Int = 1

    /**
     * Executes a full fixed frame step (subdivided into [GameConfig.SUBSTEPS] narrowphase passes).
     */
    fun stepFrame(dt: Float) {
        val subDt = dt / GameConfig.SUBSTEPS.toFloat()
        for (s in 0 until GameConfig.SUBSTEPS) {
            stepSubstep(subDt)
        }
    }

    /**
     * Advances the simulation by a single narrowphase sub-step [subDt] (1/480s).
     */
    fun stepSubstep(subDt: Float) {
        // 1. Advance particles & floating texts
        updateParticles(subDt)
        updateFloatingTexts(subDt)

        // 2. Timers, combo decay & trauma falloff
        updateTimersAndTrauma(subDt)

        // 3. Integrate obstacles (gravity, motion, clamp)
        integrateObstacles(subDt)

        // 4. Build broadphase grid for active shield segments
        buildBroadphaseGrid()

        // 5. Narrowphase collision resolution against shields
        resolveObstacleShieldCollisions(subDt)

        // 6. Bubble proximity check & collision
        resolveBubbleCollisions()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Integration & Movement
    // ---------------------------------------------------------------------------------------------

    private fun integrateObstacles(subDt: Float) {
        var i = 0
        while (i < world.obstacles.count) {
            val ox = world.obstacles.x[i]
            val oy = world.obstacles.y[i]
            val flags = world.obstacles.flags[i].toInt()
            val isParried = (flags and GameConfig.FLAG_PARRIED.toInt()) != 0
            val isDeflected = (flags and GameConfig.FLAG_DEFLECTED.toInt()) != 0

            // Despawn checks
            // 1. Horizon escape at top (deflected / parried into vanishing point)
            // Only despawn if moving UPWARD (-Y) towards the horizon!
            if (oy <= 60f && (isParried || isDeflected) && world.obstacles.vy[i] < 0f) {
                val bonus = (GameConfig.SCORE_CLEARED_BONUS * world.comboMultiplier).toInt()
                world.addScore(bonus)
                world.floatingTexts.spawn(ox, oy, -50f, 0.4f, 1.0f, FloatingTextPool.TEXT_PARRY)
                spawnSparkBurst(ox, oy, Vec2.UP, count = 8, isParry = true)
                world.obstacles.removeAt(i)
                continue
            }

            // 2. Bottom exit (dodged past bubble defense line)
            if (oy >= GameConfig.DESPAWN_MAX_Y) {
                if (!isParried && !isDeflected) {
                    world.addScore(GameConfig.SCORE_CLEARED_BONUS)
                }
                world.obstacles.removeAt(i)
                continue
            }

            // 3. Side and sub-horizon boundary exit
            if (ox < GameConfig.DESPAWN_MIN_X || ox > GameConfig.DESPAWN_MAX_X || oy < GameConfig.DESPAWN_MIN_Y) {
                world.obstacles.removeAt(i)
                continue
            }

            world.obstacles.prevX[i] = ox
            world.obstacles.prevY[i] = oy

            // Apply gravity (+Y down) or parry horizon escape thrust (-Y up)
            var nvy = if (isParried) {
                val thrustVy = world.obstacles.vy[i] - 700f * subDt
                if (thrustVy > -200f) -200f else thrustVy
            } else if (isDeflected) {
                val bounceVy = world.obstacles.vy[i] - 300f * subDt
                if (bounceVy > -100f) -100f else bounceVy
            } else {
                world.obstacles.vy[i] + GameConfig.GRAVITY_Y * subDt
            }
            var nvx = world.obstacles.vx[i]

            // Terminal speed clamp
            val speedSq = nvx * nvx + nvy * nvy
            if (speedSq > MAX_SPEED_SQ) {
                val scale = GameConfig.MAX_OBSTACLE_SPEED / sqrt(speedSq)
                nvx *= scale
                nvy *= scale
            }

            world.obstacles.vx[i] = nvx
            world.obstacles.vy[i] = nvy
            world.obstacles.x[i] = ox + nvx * subDt
            world.obstacles.y[i] = oy + nvy * subDt

            // Angular velocity integration
            world.obstacles.angle[i] += world.obstacles.angVel[i] * subDt
            world.obstacles.age[i] += subDt

            if (world.obstacles.hitCooldown[i] > 0f) {
                world.obstacles.hitCooldown[i] -= subDt
                if (world.obstacles.hitCooldown[i] < 0f) {
                    world.obstacles.hitCooldown[i] = 0f
                }
            }

            i++
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Broadphase Uniform Grid
    // ---------------------------------------------------------------------------------------------

    private fun buildBroadphaseGrid() {
        // Reset cell linked list heads
        cellHead.fill(-1)
        linkCount = 0

        val segmentCount = world.shieldSegments.count
        for (s in 0 until segmentCount) {
            val ax = world.shieldSegments.ax[s]
            val ay = world.shieldSegments.ay[s]
            val bx = world.shieldSegments.bx[s]
            val by = world.shieldSegments.by[s]

            val minX = min(ax, bx) - GameConfig.SHIELD_RADIUS
            val maxX = max(ax, bx) + GameConfig.SHIELD_RADIUS
            val minY = min(ay, by) - GameConfig.SHIELD_RADIUS
            val maxY = max(ay, by) + GameConfig.SHIELD_RADIUS

            val colStart = (minX * INV_CELL_SIZE).toInt().coerceIn(0, GRID_COLS - 1)
            val colEnd = (maxX * INV_CELL_SIZE).toInt().coerceIn(0, GRID_COLS - 1)
            val rowStart = (minY * INV_CELL_SIZE).toInt().coerceIn(0, GRID_ROWS - 1)
            val rowEnd = (maxY * INV_CELL_SIZE).toInt().coerceIn(0, GRID_ROWS - 1)

            for (r in rowStart..rowEnd) {
                val rowOffset = r * GRID_COLS
                for (c in colStart..colEnd) {
                    if (linkCount >= MAX_GRID_LINKS) break
                    val cellIdx = rowOffset + c
                    linkNext[linkCount] = cellHead[cellIdx]
                    linkSegmentIdx[linkCount] = s
                    cellHead[cellIdx] = linkCount
                    linkCount++
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Narrowphase Collision Resolution
    // ---------------------------------------------------------------------------------------------

    private fun resolveObstacleShieldCollisions(subDt: Float) {
        var obsIdx = 0
        while (obsIdx < world.obstacles.count) {
            currentQueryStamp++
            if (currentQueryStamp == 0) {
                visitStamp.fill(0)
                currentQueryStamp = 1
            }

            val ox = world.obstacles.x[obsIdx]
            val oy = world.obstacles.y[obsIdx]
            val prevOx = world.obstacles.prevX[obsIdx]
            val prevOy = world.obstacles.prevY[obsIdx]
            val r = world.obstacles.radius[obsIdx]
            val obsType = world.obstacles.type[obsIdx]
            val flags = world.obstacles.flags[obsIdx]
            val useCcd = (flags and GameConfig.FLAG_CCD) != 0

            val colStart = ((min(ox, prevOx) - r) * INV_CELL_SIZE).toInt().coerceIn(0, GRID_COLS - 1)
            val colEnd = ((max(ox, prevOx) + r) * INV_CELL_SIZE).toInt().coerceIn(0, GRID_COLS - 1)
            val rowStart = ((min(oy, prevOy) - r) * INV_CELL_SIZE).toInt().coerceIn(0, GRID_ROWS - 1)
            val rowEnd = ((max(oy, prevOy) + r) * INV_CELL_SIZE).toInt().coerceIn(0, GRID_ROWS - 1)

            var obstacleSplitOrDestroyed = false

            for (row in rowStart..rowEnd) {
                if (obstacleSplitOrDestroyed) break
                val rowOffset = row * GRID_COLS
                for (col in colStart..colEnd) {
                    if (obstacleSplitOrDestroyed) break
                    val cellIdx = rowOffset + col
                    var link = cellHead[cellIdx]

                    while (link != -1) {
                        val segIdx = linkSegmentIdx[link]
                        link = linkNext[link]

                        // Duplicate pair suppression
                        if (visitStamp[segIdx] == currentQueryStamp) continue
                        visitStamp[segIdx] = currentQueryStamp

                        val ax = world.shieldSegments.ax[segIdx]
                        val ay = world.shieldSegments.ay[segIdx]
                        val bx = world.shieldSegments.bx[segIdx]
                        val by = world.shieldSegments.by[segIdx]

                        // Perform collision test
                        val center = Vec2(ox, oy)
                        val prevCenter = Vec2(prevOx, prevOy)
                        val segA = Vec2(ax, ay)
                        val segB = Vec2(bx, by)

                        if (useCcd) {
                            val disp = Vec2(ox - prevOx, oy - prevOy)
                            CollisionMath.sweptCircleVsSegment(
                                prevCenter, disp, r,
                                segA, segB, GameConfig.SHIELD_RADIUS,
                                contact
                            )
                        } else {
                            val vel = Vec2(world.obstacles.vx[obsIdx], world.obstacles.vy[obsIdx])
                            CollisionMath.circleVsSegment(
                                center, r, vel,
                                segA, segB, GameConfig.SHIELD_RADIUS,
                                contact
                            )
                        }

                        if (contact.hit) {
                            // Resolve physical impact
                            obstacleSplitOrDestroyed = handleCollisionHit(obsIdx, segIdx, subDt)
                            if (obstacleSplitOrDestroyed) break
                        }
                    }
                }
            }

            if (!obstacleSplitOrDestroyed) {
                obsIdx++
            }
        }
    }

    private fun handleCollisionHit(obsIdx: Int, segIdx: Int, subDt: Float): Boolean {
        val ox = world.obstacles.x[obsIdx]
        val oy = world.obstacles.y[obsIdx]
        val vx = world.obstacles.vx[obsIdx]
        val vy = world.obstacles.vy[obsIdx]
        val mass = if (world.obstacles.invMass[obsIdx] > 0f) 1f / world.obstacles.invMass[obsIdx] else 1f
        val rest = world.obstacles.restitution[obsIdx]
        val obsType = world.obstacles.type[obsIdx]

        // 1. Anti-sinking positional projection (clamped to MAX_PUSHOUT)
        val correctedPos = CollisionMath.separateClamped(
            Vec2(ox, oy), contact, GameConfig.MAX_PUSHOUT
        )
        world.obstacles.x[obsIdx] = correctedPos.x
        world.obstacles.y[obsIdx] = correctedPos.y

        // 2. Active swipe velocity transfer (Parry mechanic)
        val segSwipeVx = world.shieldSegments.swipeVx[segIdx]
        val segSwipeVy = world.shieldSegments.swipeVy[segIdx]
        val segAge = world.shieldSegments.age[segIdx]

        val effSwipe = CollisionMath.effectiveSwipeVelocity(
            Vec2(segSwipeVx, segSwipeVy), segAge,
            GameConfig.PARRY_WINDOW, GameConfig.PARRY_GAIN
        )

        // 3. Velocity reflection
        val newV = CollisionMath.resolveVelocity(Vec2(vx, vy), effSwipe, contact.normal, rest)
        world.obstacles.vx[obsIdx] = newV.x
        world.obstacles.vy[obsIdx] = newV.y

        // 4. Calculate normal impulse for damage and feedback
        val impulse = CollisionMath.normalImpulse(mass, Vec2(vx, vy), effSwipe, contact.normal, rest)
        val isParry = CollisionMath.isParry(effSwipe, contact.normal, GameConfig.PARRY_MIN_NORMAL_SPEED)

        if (isParry) {
            // PERFECT PARRY
            world.obstacles.flags[obsIdx] = world.obstacles.flags[obsIdx] or GameConfig.FLAG_PARRIED
            world.perfectParriesTotal++
            world.parryTriggerCount++
            world.triggerHitStop(GameConfig.HIT_STOP_PARRY_DURATION)
            world.addTrauma(GameConfig.TRAUMA_PARRY)

            // Refill ink and grant speed boost
            world.ink = min(GameConfig.INK_MAX, world.ink + GameConfig.PARRY_INK_REFUND)
            world.ascentSpeed = min(GameConfig.ASCENT_SPEED_MAX, world.ascentSpeed + 150f)

            // Step combo
            world.comboCount++
            world.comboMultiplier = min(GameConfig.COMBO_MAX_MULTIPLIER, 1.0f + world.comboCount * GameConfig.COMBO_STEP)
            world.comboTimer = GameConfig.COMBO_TIMEOUT

            // Award soft currency Prism on combo milestone (every 5 combo levels)
            if (world.comboCount % GameConfig.COMBO_MILESTONE_STEP == 0) {
                world.prismsCollected += GameConfig.PRISMS_PER_COMBO_MILESTONE
            }

            val bonus = if (obsType == GameConfig.TYPE_CRUSHER) {
                (GameConfig.SCORE_CRUSHER_PARRY_BONUS * world.comboMultiplier).toInt()
            } else {
                (GameConfig.SCORE_PARRY_BASE_BONUS * world.comboMultiplier).toInt()
            }
            world.addScore(bonus)

            // Spawn floating text & parry spark burst
            world.floatingTexts.spawn(
                contact.point.x, contact.point.y - 10f,
                -80f, 0.45f, 1.25f, FloatingTextPool.TEXT_PARRY
            )
            spawnSparkBurst(contact.point.x, contact.point.y, contact.normal, count = 16, isParry = true)
        } else {
            // STANDARD BOUNCE
            world.obstacles.flags[obsIdx] = world.obstacles.flags[obsIdx] or GameConfig.FLAG_DEFLECTED
            world.addTrauma(GameConfig.TRAUMA_DEFLECT)
            world.addScore((GameConfig.SCORE_DEFLECT_BONUS * world.comboMultiplier).toInt())
            spawnSparkBurst(contact.point.x, contact.point.y, contact.normal, count = 6, isParry = false)
        }

        // 5. Shield segment damage & recoil
        if (obsType == GameConfig.TYPE_CRUSHER) {
            // Crusher drains 60% of max structural integrity and applies recoil
            world.shieldSegments.integrity[segIdx] -= GameConfig.CRUSHER_INTEGRITY_DRAIN
            val recoilDist = GameConfig.CRUSHER_RECOIL_DISTANCE
            world.shieldSegments.ax[segIdx] -= contact.normal.x * recoilDist
            world.shieldSegments.ay[segIdx] -= contact.normal.y * recoilDist
            world.shieldSegments.bx[segIdx] -= contact.normal.x * recoilDist
            world.shieldSegments.by[segIdx] -= contact.normal.y * recoilDist
            world.shieldSegments.recoil[segIdx] = recoilDist

            world.triggerHitStop(GameConfig.HIT_STOP_CRUSHER_DURATION)
            world.addTrauma(GameConfig.TRAUMA_CRUSHER)
            world.floatingTexts.spawn(
                contact.point.x, contact.point.y - 10f,
                -60f, 0.40f, 1.1f, FloatingTextPool.TEXT_CRUSH
            )
        } else {
            val damage = impulse * GameConfig.IMPACT_DAMAGE_PER_IMPULSE
            world.shieldSegments.integrity[segIdx] -= damage
        }

        world.shieldSegments.flashTimer[segIdx] = 0.08f
        world.obstacles.hitCooldown[obsIdx] = GameConfig.SHIELD_DAMAGE_COOLDOWN

        // 6. Splitter Archetype Fracturing
        if (obsType == GameConfig.TYPE_SPLITTER && (world.obstacles.flags[obsIdx] and GameConfig.FLAG_HAS_SPLIT) == 0) {
            fractureSplitter(obsIdx, newV, isParry)
            return true // Parent obstacle was removed/replaced
        }

        return false
    }

    private fun fractureSplitter(obsIdx: Int, exitVelocity: Vec2, isParried: Boolean) {
        val curX = world.obstacles.x[obsIdx]
        val curY = world.obstacles.y[obsIdx]
        val childFlags = GameConfig.FLAG_CCD or (if (isParried) GameConfig.FLAG_PARRIED else 0) or GameConfig.FLAG_HAS_SPLIT

        val v1 = exitVelocity.rotated(SPLIT_ANGLE_RAD) * SPLIT_SPEED_MULT
        val v2 = exitVelocity.rotated(-SPLIT_ANGLE_RAD) * SPLIT_SPEED_MULT

        // Remove the parent Splitter
        world.obstacles.removeAt(obsIdx)

        // Spawn 2 child Shards
        world.obstacles.spawn(
            curX - 8f, curY, v1.x, v1.y,
            GameConfig.SHARD_RADIUS,
            GameConfig.SHARD_MASS,
            GameConfig.SHARD_RESTITUTION,
            GameConfig.TYPE_SHARD,
            childFlags
        )
        world.obstacles.spawn(
            curX + 8f, curY, v2.x, v2.y,
            GameConfig.SHARD_RADIUS,
            GameConfig.SHARD_MASS,
            GameConfig.SHARD_RESTITUTION,
            GameConfig.TYPE_SHARD,
            childFlags
        )

        world.floatingTexts.spawn(curX, curY - 10f, -60f, 0.40f, 1.1f, FloatingTextPool.TEXT_SPLIT)
        world.addScore((GameConfig.SCORE_SPLIT_BONUS * world.comboMultiplier).toInt())
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Bubble Collisions & Proximity Warning
    // ---------------------------------------------------------------------------------------------

    private fun resolveBubbleCollisions() {
        val bx = world.bubbleX
        val by = world.bubbleY
        val br = world.bubbleRadius
        var minDistance = Float.MAX_VALUE

        var i = 0
        while (i < world.obstacles.count) {
            val ox = world.obstacles.x[i]
            val oy = world.obstacles.y[i]
            val r = world.obstacles.radius[i]

            val dx = ox - bx
            val dy = oy - by
            val dist = sqrt(dx * dx + dy * dy)
            val reach = r + br

            if (dist < minDistance) {
                minDistance = dist
            }

            if (dist <= reach) {
                // Obstacle hit the Bubble
                handleBubbleHit(i)
                // Obstacle removed, continue inspecting same index
                continue
            }

            i++
        }

        world.closestObstacleDistance = minDistance
    }

    private fun handleBubbleHit(obsIdx: Int) {
        val ox = world.obstacles.x[obsIdx]
        val oy = world.obstacles.y[obsIdx]

        if (world.bubbleInvulnTimer <= 0f) {
            world.bubbleHp--
            world.bubbleInvulnTimer = GameConfig.BUBBLE_INVULN_TIME
            world.addTrauma(GameConfig.TRAUMA_BUBBLE_HIT)

            // Excite soft-body wobble harmonics
            world.bubbleWobbleAmplitudes[0] = 0.12f
            world.bubbleWobbleAmplitudes[1] = 0.08f
            world.bubbleWobbleAmplitudes[2] = 0.04f

            if (world.bubbleHp <= 0) {
                // Game Over / Bubble Pop
                world.state = GameState.GAME_OVER
                world.triggerHitStop(GameConfig.HIT_STOP_POP_DURATION)
                world.addTrauma(GameConfig.TRAUMA_POP)
                spawnBubblePopParticles(world.bubbleX, world.bubbleY)
            }
        }

        // Colliding obstacle is destroyed
        spawnSparkBurst(ox, oy, Vec2.UP, count = 12, isParry = false)
        world.obstacles.removeAt(obsIdx)
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Particles, Text & Timers
    // ---------------------------------------------------------------------------------------------

    private fun updateParticles(subDt: Float) {
        var i = 0
        while (i < world.particles.count) {
            world.particles.prevX[i] = world.particles.x[i]
            world.particles.prevY[i] = world.particles.y[i]
            world.particles.x[i] += world.particles.vx[i] * subDt
            world.particles.y[i] += world.particles.vy[i] * subDt
            world.particles.life[i] += subDt

            if (world.particles.life[i] >= world.particles.maxLife[i]) {
                world.particles.removeAt(i)
            } else {
                i++
            }
        }
    }

    private fun updateFloatingTexts(subDt: Float) {
        var i = 0
        while (i < world.floatingTexts.count) {
            world.floatingTexts.y[i] += world.floatingTexts.vy[i] * subDt
            world.floatingTexts.life[i] += subDt

            if (world.floatingTexts.life[i] >= world.floatingTexts.maxLife[i]) {
                world.floatingTexts.removeAt(i)
            } else {
                i++
            }
        }
    }

    private fun updateTimersAndTrauma(subDt: Float) {
        // Bubble invulnerability timer
        if (world.bubbleInvulnTimer > 0f) {
            world.bubbleInvulnTimer -= subDt
            if (world.bubbleInvulnTimer < 0f) {
                world.bubbleInvulnTimer = 0f
            }
        }

        // Combo timeout decay
        if (world.comboTimer > 0f) {
            world.comboTimer -= subDt
            if (world.comboTimer <= 0f) {
                world.comboTimer = 0f
                world.comboCount = 0
                world.comboMultiplier = 1.0f
            }
        }

        // Screen shake trauma decay
        if (world.trauma > 0f) {
            world.trauma -= GameConfig.TRAUMA_DECAY_RATE * subDt
            if (world.trauma < 0f) {
                world.trauma = 0f
            }
        }

        // Soft-body wobble harmonic relaxation
        world.bubbleWobbleAmplitudes[0] = max(0.015f, world.bubbleWobbleAmplitudes[0] - 0.08f * subDt)
        world.bubbleWobbleAmplitudes[1] = max(0.008f, world.bubbleWobbleAmplitudes[1] - 0.05f * subDt)
        world.bubbleWobbleAmplitudes[2] = max(0.004f, world.bubbleWobbleAmplitudes[2] - 0.03f * subDt)
    }

    private fun spawnSparkBurst(x: Float, y: Float, normal: Vec2, count: Int, isParry: Boolean) {
        val color = if (isParry) 0xFF00F0FF.toInt() else 0xFFFFB800.toInt() // Neon Cyan vs Amber
        val baseSpeed = if (isParry) 260f else 140f

        for (p in 0 until count) {
            val angle = ((p.toFloat() / count.toFloat()) - 0.5f) * 2.2f
            val dir = normal.rotated(angle)
            val speed = baseSpeed * (0.6f + (p % 3) * 0.3f)

            world.particles.spawn(
                posX = x,
                posY = y,
                velX = dir.x * speed,
                velY = dir.y * speed,
                lifetime = if (isParry) 0.38f else 0.22f,
                particleSize = if (isParry) 5f else 3f,
                rot = angle,
                argbColor = color
            )
        }
    }

    private fun spawnBubblePopParticles(bx: Float, by: Float) {
        val count = 32
        val step = (Math.PI * 2 / count).toFloat()
        for (p in 0 until count) {
            val theta = p * step
            val dir = Vec2.fromAngle(theta, 1f)
            val speed = 180f + (p % 4) * 50f

            world.particles.spawn(
                posX = bx + dir.x * 20f,
                posY = by + dir.y * 20f,
                velX = dir.x * speed,
                velY = dir.y * speed,
                lifetime = 0.60f,
                particleSize = 6f,
                rot = theta,
                argbColor = 0xFF00F0FF.toInt()
            )
        }
    }
}
