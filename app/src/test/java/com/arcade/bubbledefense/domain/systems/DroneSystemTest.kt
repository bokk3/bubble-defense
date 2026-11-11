package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

class DroneSystemTest {

    private lateinit var world: GameWorld
    private lateinit var droneSystem: DroneSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 1337)
        droneSystem = DroneSystem(world, synth = null)
    }

    @Test
    fun level0_doesNotTrackOrFire() {
        world.droneLevel = 0
        world.obstacles.spawn(
            posX = 200f, posY = 1000f,
            velX = 0f, velY = 100f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        droneSystem.step(0.016f)

        assertFalse(droneSystem.hasTarget[0])
        assertFalse(droneSystem.hasTarget[1])
        assertEquals(0f, droneSystem.beamTimer[0], 1e-6f)
    }

    @Test
    fun level1_leftDroneAcquiresClosestThreatToBubble() {
        world.droneLevel = 1
        // Spawn obstacle A further up
        world.obstacles.spawn(
            posX = 300f, posY = 700f,
            velX = 0f, velY = 100f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        // Spawn obstacle B closer to bubble (Y = 1476)
        world.obstacles.spawn(
            posX = 300f, posY = 1250f,
            velX = 0f, velY = 100f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        droneSystem.step(0.016f)

        assertTrue("Left drone acquires target", droneSystem.hasTarget[0])
        assertEquals("Target index should be closest threat (index 1)", 1, droneSystem.targetIndex[0])
        assertFalse("Right drone inactive at Level 1", droneSystem.hasTarget[1])
    }

    @Test
    fun level2_dualDronesTargetRespectiveSectors() {
        world.droneLevel = 2
        // Left sector obstacle
        world.obstacles.spawn(
            posX = 200f, posY = 1000f,
            velX = 0f, velY = 100f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        // Right sector obstacle
        world.obstacles.spawn(
            posX = 800f, posY = 1000f,
            velX = 0f, velY = 100f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        droneSystem.step(0.016f)

        assertTrue("Left drone targets left sector", droneSystem.hasTarget[0])
        assertEquals(0, droneSystem.targetIndex[0])

        assertTrue("Right drone targets right sector", droneSystem.hasTarget[1])
        assertEquals(1, droneSystem.targetIndex[1])
    }

    @Test
    fun friendlyPrismCarrier_isNeverTargeted() {
        world.droneLevel = 3
        // Spawn single emerald prism carrier in prime firing range
        world.obstacles.spawn(
            posX = 300f, posY = 1100f,
            velX = 0f, velY = 100f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_PRISM_CARRIER
        )

        droneSystem.step(0.016f)

        assertFalse("Drone 0 must ignore friendly prism carrier", droneSystem.hasTarget[0])
        assertFalse("Drone 1 must ignore friendly prism carrier", droneSystem.hasTarget[1])
    }

    @Test
    fun plasmaBeam_disintegratesFragileNeedle() {
        world.droneLevel = 1
        world.obstacles.spawn(
            posX = 200f, posY = 1000f,
            velX = 0f, velY = 200f,
            colRadius = 14f, mass = 0.6f, colRestitution = 0.7f,
            obstacleType = GameConfig.TYPE_NEEDLE
        )

        droneSystem.fireCooldown[0] = 0f
        droneSystem.step(0.016f)

        assertTrue("Beam triggered", droneSystem.beamTimer[0] > 0f)
        assertEquals("Needle should be disintegrated", 0, world.obstacles.count)
        assertTrue("Score awarded for disintegration", world.score > 0)
    }

    @Test
    fun plasmaBeam_knocksBackAndShrinksHeavyObstacle() {
        world.droneLevel = 1
        val initialVy = 200f
        val initialRadius = 32f
        world.obstacles.spawn(
            posX = 200f, posY = 1000f,
            velX = 0f, velY = initialVy,
            colRadius = initialRadius, mass = 3.0f, colRestitution = 0.3f,
            obstacleType = GameConfig.TYPE_CRUSHER
        )

        droneSystem.fireCooldown[0] = 0f
        droneSystem.step(0.016f)

        assertTrue("Beam triggered", droneSystem.beamTimer[0] > 0f)
        assertEquals("Heavy obstacle remains active", 1, world.obstacles.count)
        assertTrue("Obstacle radius shrunk", world.obstacles.radius[0] < initialRadius)
        assertTrue(
            "Knockback applied reducing vy",
            world.obstacles.vy[0] < initialVy
        )
    }

    @Test
    fun zeroAllocations_inHotPath() {
        if (!AllocationProbe.isAvailable) return

        world.droneLevel = 2
        world.obstacles.spawn(
            posX = 200f, posY = 1000f,
            velX = 0f, velY = 50f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        world.obstacles.spawn(
            posX = 800f, posY = 1000f,
            velX = 0f, velY = 50f,
            colRadius = 24f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        AllocationProbe.warmUp()

        // Warm up execution
        for (i in 0 until 500) {
            droneSystem.step(GameConfig.FIXED_DT)
        }

        val before = AllocationProbe.allocatedBytes()

        for (i in 0 until 5000) {
            droneSystem.step(GameConfig.FIXED_DT)
        }

        val after = AllocationProbe.allocatedBytes()
        val delta = after - before

        assertTrue("Expected zero heap allocations in hot path, got $delta bytes", delta <= 128L)
    }

    private object AllocationProbe {
        private val bean: Any?
        private val counter: Method?

        init {
            var resolvedBean: Any? = null
            var resolvedCounter: Method? = null
            try {
                val factory = Class.forName("java.lang.management.ManagementFactory")
                val candidate = factory.getMethod("getThreadMXBean").invoke(null)
                val sunBean = Class.forName("com.sun.management.ThreadMXBean")
                if (sunBean.isInstance(candidate) &&
                    sunBean.getMethod("isThreadAllocatedMemorySupported").invoke(candidate) == true
                ) {
                    sunBean.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType)
                        .invoke(candidate, true)
                    resolvedBean = candidate
                    resolvedCounter = sunBean.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                }
            } catch (ignored: Throwable) {
                resolvedBean = null
                resolvedCounter = null
            }
            bean = resolvedBean
            counter = resolvedCounter
        }

        val isAvailable: Boolean get() = counter != null

        @Suppress("DEPRECATION")
        fun allocatedBytes(): Long = (counter?.invoke(bean, Thread.currentThread().id) as? Long) ?: 0L

        fun warmUp() {
            for (i in 0 until 64) allocatedBytes()
        }
    }
}
