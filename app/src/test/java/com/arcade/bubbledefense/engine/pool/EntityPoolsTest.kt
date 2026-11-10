package com.arcade.bubbledefense.engine.pool

import com.arcade.bubbledefense.domain.config.GameConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class EntityPoolsTest {

    private val eps = 1e-4f

    @Test
    fun obstaclePool_spawnAndSwapRemove_maintainsDenseArray() {
        val pool = ObstaclePool(capacity = 4)
        assertEquals(0, pool.count)

        // Spawn 3 obstacles
        val i0 = pool.spawn(10f, 20f, 0f, 100f, 28f, 1f, 0.75f, GameConfig.TYPE_DRIFTER)
        val i1 = pool.spawn(30f, 40f, 0f, 200f, 7f, 0.35f, 0.60f, GameConfig.TYPE_NEEDLE)
        val i2 = pool.spawn(50f, 60f, 0f, 300f, 46f, 4.0f, 0.30f, GameConfig.TYPE_CRUSHER)

        assertEquals(0, i0)
        assertEquals(1, i1)
        assertEquals(2, i2)
        assertEquals(3, pool.count)

        // Remove the middle entity (index 1) -> slot 2 should be moved into slot 1
        val lastId = pool.id[2]
        pool.removeAt(1)

        assertEquals(2, pool.count)
        assertEquals(lastId, pool.id[1]) // Crusher was moved to slot 1
        assertEquals(50f, pool.x[1], eps)
        assertEquals(60f, pool.y[1], eps)
        assertEquals(GameConfig.TYPE_CRUSHER, pool.type[1])

        // Verify slot 0 is unchanged
        assertEquals(10f, pool.x[0], eps)
        assertEquals(GameConfig.TYPE_DRIFTER, pool.type[0])

        pool.clear()
        assertEquals(0, pool.count)
    }

    @Test
    fun obstaclePool_rejectionOnCapacityLimit() {
        val pool = ObstaclePool(capacity = 2)
        assertEquals(0, pool.spawn(0f, 0f, 0f, 0f, 10f, 1f, 0.5f, 0))
        assertEquals(1, pool.spawn(0f, 0f, 0f, 0f, 10f, 1f, 0.5f, 0))
        // Third spawn rejected
        assertEquals(-1, pool.spawn(0f, 0f, 0f, 0f, 10f, 1f, 0.5f, 0))
        assertEquals(2, pool.count)
    }

    @Test
    fun shieldSegmentPool_calculatesNormalAndLength_andEvictsOldestOnOverflow() {
        val pool = ShieldSegmentPool(capacity = 3)

        // Segment A(0,0) -> B(100,0)
        val idx0 = pool.spawn(0f, 0f, 100f, 0f, 0f, 0f, stroke = 1, pointer = 0)
        assertEquals(100f, pool.length[idx0], eps)
        // Normal to horizontal line from left to right is DOWN (0, 1) on screen
        assertEquals(0f, pool.nx[idx0], eps)
        assertEquals(1f, pool.ny[idx0], eps)

        // Segment A(0,0) -> B(0,100): dy=100 -> nx = -1, ny = 0
        val idx1 = pool.spawn(0f, 0f, 0f, 100f, 0f, 0f, stroke = 1, pointer = 0)
        assertEquals(100f, pool.length[idx1], eps)
        assertEquals(-1f, pool.nx[idx1], eps)
        assertEquals(0f, pool.ny[idx1], eps)

        // Segment 2
        val idx2 = pool.spawn(10f, 10f, 20f, 20f, 0f, 0f, stroke = 1, pointer = 0)
        assertEquals(3, pool.count)

        // Simulate aging: make idx1 the oldest
        pool.age[idx0] = 0.5f
        pool.age[idx1] = 1.2f // oldest
        pool.age[idx2] = 0.1f

        // Spawn a 4th segment: should evict idx1 (the oldest)
        val evictedIdx = pool.spawn(50f, 50f, 60f, 50f, 0f, 0f, stroke = 2, pointer = 0)
        assertEquals(idx1, evictedIdx)
        assertEquals(3, pool.count)
        assertEquals(50f, pool.ax[evictedIdx], eps)
        assertEquals(0f, pool.age[evictedIdx], eps)
    }

    @Test
    fun particlePool_spawnAndRemove() {
        val pool = ParticlePool(capacity = 4)
        pool.spawn(1f, 2f, 3f, 4f, 0.5f, 2f, 0f, 0xFFFFFFFF.toInt())
        pool.spawn(5f, 6f, 7f, 8f, 0.5f, 2f, 0f, 0xFFFFFFFF.toInt())
        assertEquals(2, pool.count)

        pool.removeAt(0)
        assertEquals(1, pool.count)
        assertEquals(5f, pool.x[0], eps)
    }

    @Test
    fun floatingTextPool_spawnAndRemove() {
        val pool = FloatingTextPool(capacity = 4)
        pool.spawn(100f, 200f, -90f, 0.7f, 1.4f, FloatingTextPool.TEXT_PARRY)
        assertEquals(1, pool.count)
        assertEquals(FloatingTextPool.TEXT_PARRY, pool.textId[0])
        assertEquals(-90f, pool.vy[0], eps)
    }

    @Test
    fun inputRingBuffer_fifoOrderAndWrapAround() {
        val ring = InputRingBuffer(capacity = 4) // capacity 4 -> mask 3
        assertEquals(0, ring.size)

        ring.push(10f, 20f, 1000L, 0, InputRingBuffer.ACTION_DOWN)
        ring.push(30f, 40f, 2000L, 0, InputRingBuffer.ACTION_MOVE)
        assertEquals(2, ring.size)

        // Peek first item
        val t0 = ring.peek()
        assertEquals(10f, ring.x[t0], eps)
        assertEquals(InputRingBuffer.ACTION_DOWN, ring.action[t0])
        assertTrue(ring.pop())

        assertEquals(1, ring.size)
        val t1 = ring.peek()
        assertEquals(30f, ring.x[t1], eps)
        assertEquals(InputRingBuffer.ACTION_MOVE, ring.action[t1])
        assertTrue(ring.pop())
        assertEquals(0, ring.size)
        assertFalse(ring.pop())

        // Test wrap-around overflow
        ring.push(1f, 1f, 1L, 0, InputRingBuffer.ACTION_DOWN)
        ring.push(2f, 2f, 2L, 0, InputRingBuffer.ACTION_MOVE)
        ring.push(3f, 3f, 3L, 0, InputRingBuffer.ACTION_MOVE)
        ring.push(4f, 4f, 4L, 0, InputRingBuffer.ACTION_MOVE)
        assertEquals(4, ring.size)
        assertEquals(0L, ring.droppedSamplesCount)

        // Push 5th item: overwrites oldest (1f, 1f) and drops it
        val pushed = ring.push(5f, 5f, 5L, 0, InputRingBuffer.ACTION_UP)
        assertFalse(pushed) // returned false because buffer was full and dropped oldest
        assertEquals(4, ring.size)
        assertEquals(1L, ring.droppedSamplesCount)

        // New oldest should be (2f, 2f)
        val headIdx = ring.peek()
        assertEquals(2f, ring.x[headIdx], eps)
    }
}
