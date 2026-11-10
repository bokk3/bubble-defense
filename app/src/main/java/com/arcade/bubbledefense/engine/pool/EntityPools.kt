package com.arcade.bubbledefense.engine.pool

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.engine.math.CollisionMath
import com.arcade.bubbledefense.engine.math.Vec2
import kotlin.math.sqrt

/**
 * Pre-allocated Structure-of-Arrays (SoA) memory pools and ring buffers for Bubble Defense.
 *
 * Implements the memory architecture specified in `docs/ARCHITECTURE.md` §4.
 * Guarantees zero heap allocation on the hot path:
 * - All storage resides in fixed-capacity primitive arrays allocated once during startup.
 * - Entity deletion uses O(1) swap-remove (dense packing).
 * - Capacities are powers of two to allow bitwise masking for ring cursors.
 */

// -------------------------------------------------------------------------------------------------
// 1. ObstaclePool (Max 128 active entities)
// -------------------------------------------------------------------------------------------------

/**
 * Pre-allocated Structure-of-Arrays pool for active obstacles and falling hazards.
 * Capacity is strictly fixed at [GameConfig.OBSTACLE_POOL_CAPACITY] (128).
 */
class ObstaclePool(val capacity: Int = GameConfig.OBSTACLE_POOL_CAPACITY) {
    var count: Int = 0
        private set

    private var nextEntityId: Int = 1

    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val prevX = FloatArray(capacity)
    val prevY = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val radius = FloatArray(capacity)
    val invMass = FloatArray(capacity)
    val restitution = FloatArray(capacity)
    val angle = FloatArray(capacity)
    val angVel = FloatArray(capacity)
    val age = FloatArray(capacity)
    val hitCooldown = FloatArray(capacity)
    val type = ByteArray(capacity)
    val flags = IntArray(capacity)
    val lastSegment = IntArray(capacity)
    val id = IntArray(capacity)
    val seed = IntArray(capacity)

    /**
     * Allocates a new obstacle slot.
     * Returns the slot index `0 until count`, or `-1` if the pool is full.
     */
    fun spawn(
        posX: Float,
        posY: Float,
        velX: Float,
        velY: Float,
        colRadius: Float,
        mass: Float,
        colRestitution: Float,
        obstacleType: Byte,
        obstacleFlags: Int = 0,
        randomSeed: Int = 0,
        initialAngle: Float = 0f,
        angularVelocity: Float = 0f
    ): Int {
        if (count >= capacity) return -1
        val index = count++
        x[index] = posX
        y[index] = posY
        prevX[index] = posX
        prevY[index] = posY
        vx[index] = velX
        vy[index] = velY
        radius[index] = colRadius
        invMass[index] = if (mass > 0f) 1f / mass else 0f
        restitution[index] = colRestitution
        angle[index] = initialAngle
        angVel[index] = angularVelocity
        age[index] = 0f
        hitCooldown[index] = 0f
        type[index] = obstacleType
        flags[index] = obstacleFlags
        lastSegment[index] = -1
        id[index] = nextEntityId++
        seed[index] = randomSeed
        return index
    }

    /**
     * Removes the obstacle at [index] in O(1) time using swap-remove.
     * The last active entity is moved into slot [index], preserving dense array packing.
     */
    fun removeAt(index: Int) {
        if (index < 0 || index >= count) return
        val last = --count
        if (index != last) {
            x[index] = x[last]
            y[index] = y[last]
            prevX[index] = prevX[last]
            prevY[index] = prevY[last]
            vx[index] = vx[last]
            vy[index] = vy[last]
            radius[index] = radius[last]
            invMass[index] = invMass[last]
            restitution[index] = restitution[last]
            angle[index] = angle[last]
            angVel[index] = angVel[last]
            age[index] = age[last]
            hitCooldown[index] = hitCooldown[last]
            type[index] = type[last]
            flags[index] = flags[last]
            lastSegment[index] = lastSegment[last]
            id[index] = id[last]
            seed[index] = seed[last]
        }
    }

    /** Resets the pool count to 0 in O(1) time without garbage generation. */
    fun clear() {
        count = 0
    }
}

// -------------------------------------------------------------------------------------------------
// 2. ShieldSegmentPool (Max 256 active line segments)
// -------------------------------------------------------------------------------------------------

/**
 * Pre-allocated Structure-of-Arrays pool for drawn shield line segments.
 * Capacity is strictly fixed at [GameConfig.SHIELD_SEGMENT_POOL_CAPACITY] (256).
 */
class ShieldSegmentPool(val capacity: Int = GameConfig.SHIELD_SEGMENT_POOL_CAPACITY) {
    var count: Int = 0
        private set

    private var nextSegmentId: Int = 1

    val ax = FloatArray(capacity)
    val ay = FloatArray(capacity)
    val bx = FloatArray(capacity)
    val by = FloatArray(capacity)
    val nx = FloatArray(capacity)
    val ny = FloatArray(capacity)
    val swipeVx = FloatArray(capacity)
    val swipeVy = FloatArray(capacity)
    val age = FloatArray(capacity)
    val integrity = FloatArray(capacity)
    val recoil = FloatArray(capacity)
    val length = FloatArray(capacity)
    val strokeId = IntArray(capacity)
    val pointerId = IntArray(capacity)
    val flashTimer = FloatArray(capacity)
    val id = IntArray(capacity)

    /**
     * Commits a new shield segment.
     * Computes the geometric unit normal and length on insertion.
     * If capacity is reached, evicts the oldest active segment (max age).
     *
     * @return the slot index of the newly added segment in `0 until count`.
     */
    fun spawn(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        velSwipeX: Float,
        velSwipeY: Float,
        stroke: Int,
        pointer: Int,
        initialIntegrity: Float = GameConfig.SEGMENT_INTEGRITY
    ): Int {
        val index = if (count < capacity) {
            count++
        } else {
            // Pool overflow policy: evict the oldest segment
            var oldestIdx = 0
            var maxAge = age[0]
            for (i in 1 until capacity) {
                if (age[i] > maxAge) {
                    maxAge = age[i]
                    oldestIdx = i
                }
            }
            oldestIdx
        }

        ax[index] = startX
        ay[index] = startY
        bx[index] = endX
        by[index] = endY

        val dx = endX - startX
        val dy = endY - startY
        val lenSq = dx * dx + dy * dy
        val len = if (lenSq > CollisionMath.DEGENERATE_SEGMENT_LENGTH_SQ) sqrt(lenSq) else 0f
        length[index] = len

        if (len > 0f) {
            val invLen = 1f / len
            nx[index] = -dy * invLen
            ny[index] = dx * invLen
        } else {
            nx[index] = 0f
            ny[index] = -1f
        }

        // Clamp swipe velocity to MAX_SWIPE_SPEED
        val swipeLenSq = velSwipeX * velSwipeX + velSwipeY * velSwipeY
        if (swipeLenSq > GameConfig.MAX_SWIPE_SPEED * GameConfig.MAX_SWIPE_SPEED) {
            val scale = GameConfig.MAX_SWIPE_SPEED / sqrt(swipeLenSq)
            swipeVx[index] = velSwipeX * scale
            swipeVy[index] = velSwipeY * scale
        } else {
            swipeVx[index] = velSwipeX
            swipeVy[index] = velSwipeY
        }

        age[index] = 0f
        integrity[index] = initialIntegrity
        recoil[index] = 0f
        strokeId[index] = stroke
        pointerId[index] = pointer
        flashTimer[index] = 0f
        id[index] = nextSegmentId++
        return index
    }

    /** Removes the segment at [index] using O(1) swap-remove. */
    fun removeAt(index: Int) {
        if (index < 0 || index >= count) return
        val last = --count
        if (index != last) {
            ax[index] = ax[last]
            ay[index] = ay[last]
            bx[index] = bx[last]
            by[index] = by[last]
            nx[index] = nx[last]
            ny[index] = ny[last]
            swipeVx[index] = swipeVx[last]
            swipeVy[index] = swipeVy[last]
            age[index] = age[last]
            integrity[index] = integrity[last]
            recoil[index] = recoil[last]
            length[index] = length[last]
            strokeId[index] = strokeId[last]
            pointerId[index] = pointerId[last]
            flashTimer[index] = flashTimer[last]
            id[index] = id[last]
        }
    }

    fun clear() {
        count = 0
    }
}

// -------------------------------------------------------------------------------------------------
// 3. ParticlePool (Max 512 spark & line-fragment particles)
// -------------------------------------------------------------------------------------------------

/**
 * Pre-allocated pool for sparks, debris, and line shatter particles.
 * Capacity is strictly fixed at [GameConfig.PARTICLE_POOL_CAPACITY] (512).
 */
class ParticlePool(val capacity: Int = GameConfig.PARTICLE_POOL_CAPACITY) {
    var count: Int = 0
        private set

    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val prevX = FloatArray(capacity)
    val prevY = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    val size = FloatArray(capacity)
    val rotation = FloatArray(capacity)
    val color = IntArray(capacity)

    /**
     * Spawns a particle.
     * Uses swap-remove for expired particles; if pool is full, overwrites rolling slot 0.
     */
    fun spawn(
        posX: Float,
        posY: Float,
        velX: Float,
        velY: Float,
        lifetime: Float,
        particleSize: Float,
        rot: Float,
        argbColor: Int
    ): Int {
        val index = if (count < capacity) count++ else (count - 1)
        x[index] = posX
        y[index] = posY
        prevX[index] = posX
        prevY[index] = posY
        vx[index] = velX
        vy[index] = velY
        life[index] = lifetime
        maxLife[index] = lifetime
        size[index] = particleSize
        rotation[index] = rot
        color[index] = argbColor
        return index
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= count) return
        val last = --count
        if (index != last) {
            x[index] = x[last]
            y[index] = y[last]
            prevX[index] = prevX[last]
            prevY[index] = prevY[last]
            vx[index] = vx[last]
            vy[index] = vy[last]
            life[index] = life[last]
            maxLife[index] = maxLife[last]
            size[index] = size[last]
            rotation[index] = rotation[last]
            color[index] = color[last]
        }
    }

    fun clear() {
        count = 0
    }
}

// -------------------------------------------------------------------------------------------------
// 4. FloatingTextPool (Max 32 active combat text popups)
// -------------------------------------------------------------------------------------------------

/**
 * Pre-allocated pool for world-space floating combat text popups ("PARRY!", "+50", "CLOSE CALL!").
 * Capacity is strictly fixed at [GameConfig.FLOATING_TEXT_POOL_CAPACITY] (32).
 */
class FloatingTextPool(val capacity: Int = GameConfig.FLOATING_TEXT_POOL_CAPACITY) {
    var count: Int = 0
        private set

    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    val scale = FloatArray(capacity)
    val textId = ByteArray(capacity)

    companion object {
        const val TEXT_PARRY: Byte = 0
        const val TEXT_PLUS_5: Byte = 1
        const val TEXT_PLUS_10: Byte = 2
        const val TEXT_PLUS_50: Byte = 3
        const val TEXT_PLUS_100: Byte = 4
        const val TEXT_SPLIT: Byte = 5
        const val TEXT_CRUSH: Byte = 6
        const val TEXT_CLOSE_CALL: Byte = 7
    }

    fun spawn(
        posX: Float,
        posY: Float,
        velY: Float,
        lifetime: Float,
        initialScale: Float,
        type: Byte
    ): Int {
        val index = if (count < capacity) count++ else (count - 1)
        x[index] = posX
        y[index] = posY
        vy[index] = velY
        life[index] = lifetime
        maxLife[index] = lifetime
        scale[index] = initialScale
        textId[index] = type
        return index
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= count) return
        val last = --count
        if (index != last) {
            x[index] = x[last]
            y[index] = y[last]
            vy[index] = vy[last]
            life[index] = life[last]
            maxLife[index] = maxLife[last]
            scale[index] = scale[last]
            textId[index] = textId[last]
        }
    }

    fun clear() {
        count = 0
    }
}

// -------------------------------------------------------------------------------------------------
// 5. InputRingBuffer (Max 256 pointer event samples)
// -------------------------------------------------------------------------------------------------

/**
 * Circular ring buffer decoupling high-frequency UI touch events (120Hz-240Hz)
 * from the fixed-timestep simulation thread.
 * Capacity is strictly fixed at [GameConfig.INPUT_RING_CAPACITY] (256, power of two).
 */
class InputRingBuffer(val capacity: Int = GameConfig.INPUT_RING_CAPACITY) {
    companion object {
        const val ACTION_DOWN: Byte = 0
        const val ACTION_MOVE: Byte = 1
        const val ACTION_UP: Byte = 2
        const val ACTION_CANCEL: Byte = 3
    }

    private val mask = capacity - 1

    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val timestampNanos = LongArray(capacity)
    val pointerId = IntArray(capacity)
    val action = ByteArray(capacity)

    var head: Int = 0
        private set
    var tail: Int = 0
        private set
    var size: Int = 0
        private set

    var droppedSamplesCount: Long = 0L
        private set

    /** Pushes an incoming touch sample. Overwrites the oldest sample if the buffer is full. */
    fun push(
        posX: Float,
        posY: Float,
        timestamp: Long,
        pointer: Int,
        touchAction: Byte
    ): Boolean {
        val index = head
        x[index] = posX
        y[index] = posY
        timestampNanos[index] = timestamp
        pointerId[index] = pointer
        action[index] = touchAction

        head = (head + 1) and mask
        if (size < capacity) {
            size++
            return true
        } else {
            // Buffer full: advance tail, dropping the oldest unconsumed sample
            tail = (tail + 1) and mask
            droppedSamplesCount++
            return false
        }
    }

    /**
     * Reads the next sample from the tail without removing it.
     * Returns the internal index in the buffer, or `-1` if the buffer is empty.
     */
    fun peek(): Int = if (size > 0) tail else -1

    /** Advances the tail by one, removing the oldest sample. */
    fun pop(): Boolean {
        if (size <= 0) return false
        tail = (tail + 1) and mask
        size--
        return true
    }

    fun clear() {
        head = 0
        tail = 0
        size = 0
        droppedSamplesCount = 0L
    }
}
