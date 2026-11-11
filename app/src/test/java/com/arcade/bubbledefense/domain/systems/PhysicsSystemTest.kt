package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameState
import com.arcade.bubbledefense.domain.world.GameWorld
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method
import kotlin.math.sqrt

class PhysicsSystemTest {

    private lateinit var world: GameWorld
    private lateinit var physicsSystem: PhysicsSystem

    @Before
    fun setUp() {
        world = GameWorld(initialSeed = 12345)
        physicsSystem = PhysicsSystem(world)
    }

    @Test
    fun integration_gravityAcceleratesObstacleDownwards() {
        // Spawn a drifter at (500, 100) with 0 velocity
        val idx = world.obstacles.spawn(
            posX = 500f, posY = 100f,
            velX = 0f, velY = 0f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        assertEquals(0, idx)

        // Step 1 sub-step: subDt = 1/480s
        val subDt = GameConfig.SUB_DT
        physicsSystem.stepSubstep(subDt)

        val expectedVy = GameConfig.GRAVITY_Y * subDt
        assertEquals(expectedVy, world.obstacles.vy[0], 1e-3f)
        assertTrue(world.obstacles.y[0] > 100f)
    }

    @Test
    fun terminalSpeed_clampsMaxObstacleVelocity() {
        // Spawn an obstacle with excessive downward speed (5000 wu/s > 3600 wu/s max)
        world.obstacles.spawn(
            posX = 500f, posY = 100f,
            velX = 0f, velY = 5000f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        val speed = sqrt(world.obstacles.vx[0] * world.obstacles.vx[0] + world.obstacles.vy[0] * world.obstacles.vy[0])
        assertEquals(GameConfig.MAX_OBSTACLE_SPEED, speed, 1e-2f)
    }

    @Test
    fun despawn_removesObstacleWhenOutOfBounds() {
        // Spawn beyond DESPAWN_MAX_Y (1950 wu)
        world.obstacles.spawn(
            posX = 500f, posY = 2000f,
            velX = 0f, velY = 100f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        assertEquals(1, world.obstacles.count)

        physicsSystem.stepSubstep(GameConfig.SUB_DT)
        assertEquals(0, world.obstacles.count)
    }

    @Test
    fun circleSegmentCollision_reflectsObstacleWithRestitution() {
        // Horizontal shield at Y = 500 from X = 400 to 600
        world.shieldSegments.spawn(
            startX = 400f, startY = 500f,
            endX = 600f, endY = 500f,
            velSwipeX = 0f, velSwipeY = 0f,
            stroke = 1, pointer = 0
        )

        // Drifter falling straight down toward the shield
        // Radius = 28, shield radius = 6 -> reach = 34 wu
        // Place center at Y = 480 (within reach 34, penetrates by 20 wu)
        world.obstacles.spawn(
            posX = 500f, posY = 480f,
            velX = 0f, velY = 400f,
            colRadius = GameConfig.DRIFTER_RADIUS, // 28
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION, // 0.75
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        // After impact: obstacle should bounce upwards (vy < 0)
        assertTrue("Obstacle vy should be negative (bounced up)", world.obstacles.vy[0] < 0f)
        // With e = 0.75 and initial vy ~400, post-bounce vy is approximately -300 wu/s
        assertEquals(-300f, world.obstacles.vy[0], 20f)
    }

    @Test
    fun parry_swipingIntoObstacle_transfersVelocityAndTriggersParryState() {
        // Shield drawn swiping upwards at 800 wu/s
        world.shieldSegments.spawn(
            startX = 400f, startY = 500f,
            endX = 600f, endY = 500f,
            velSwipeX = 0f, velSwipeY = -800f, // Upward swipe into falling hazard
            stroke = 1, pointer = 0
        )

        // Drifter falling down at (500, 480) with vy = 400
        world.obstacles.spawn(
            posX = 500f, posY = 480f,
            velX = 0f, velY = 400f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        val parriesBefore = world.perfectParriesTotal
        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        // Verified Parry!
        assertEquals(parriesBefore + 1, world.perfectParriesTotal)
        assertTrue(world.hitStopTimer > 0f)
        assertTrue(world.trauma > 0f)
        assertTrue((world.obstacles.flags[0] and GameConfig.FLAG_PARRIED) != 0)

        // Super-charged exit speed upwards
        assertTrue("Parried obstacle launches with intense upward speed", world.obstacles.vy[0] < -1000f)
    }

    @Test
    fun crusher_drains60PercentIntegrity_andAppliesRecoil() {
        val segIdx = world.shieldSegments.spawn(
            startX = 400f, startY = 500f,
            endX = 600f, endY = 500f,
            velSwipeX = 0f, velSwipeY = 0f,
            stroke = 1, pointer = 0
        )
        val initialAy = world.shieldSegments.ay[segIdx]

        // Crusher hitting the segment
        world.obstacles.spawn(
            posX = 500f, posY = 470f,
            velX = 0f, velY = 200f,
            colRadius = GameConfig.CRUSHER_RADIUS,
            mass = GameConfig.CRUSHER_MASS,
            colRestitution = GameConfig.CRUSHER_RESTITUTION,
            obstacleType = GameConfig.TYPE_CRUSHER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        // 60% integrity drain (100 - 60 = 40)
        assertEquals(40f, world.shieldSegments.integrity[segIdx], 1e-2f)

        // Recoil: endpoints displaced downwards (+Y) along -contact.normal
        assertTrue("Segment ay displaced downward", world.shieldSegments.ay[segIdx] > initialAy)
    }

    @Test
    fun splitter_fracturesIntoTwoShards_onFirstContact() {
        world.shieldSegments.spawn(
            startX = 400f, startY = 500f,
            endX = 600f, endY = 500f,
            velSwipeX = 0f, velSwipeY = 0f,
            stroke = 1, pointer = 0
        )

        // Splitter hitting shield
        world.obstacles.spawn(
            posX = 500f, posY = 480f,
            velX = 0f, velY = 350f,
            colRadius = GameConfig.SPLITTER_RADIUS,
            mass = GameConfig.SPLITTER_MASS,
            colRestitution = GameConfig.SPLITTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_SPLITTER
        )
        assertEquals(1, world.obstacles.count)

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        // Parent Splitter destroyed and replaced by 2 child Shards
        assertEquals(2, world.obstacles.count)
        assertEquals(GameConfig.TYPE_SHARD, world.obstacles.type[0])
        assertEquals(GameConfig.TYPE_SHARD, world.obstacles.type[1])

        // Shards have distinct rotated velocities
        assertNotEquals(world.obstacles.vx[0], world.obstacles.vx[1], 1f)
    }

    @Test
    fun bubbleCollision_dealsDamageAndTriggersInvulnerability() {
        assertEquals(GameConfig.BUBBLE_MAX_HP, world.bubbleHp)
        assertEquals(0f, world.bubbleInvulnTimer, 1e-3f)

        // Spawn obstacle touching the Bubble at (world.bubbleX, world.bubbleY)
        world.obstacles.spawn(
            posX = world.bubbleX,
            posY = world.bubbleY - world.bubbleRadius - 5f,
            velX = 0f, velY = 100f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        assertEquals(GameConfig.BUBBLE_MAX_HP - 1, world.bubbleHp)
        assertTrue(world.bubbleInvulnTimer > 0.9f)
        assertEquals(0, world.obstacles.count) // Collided obstacle destroyed
    }

    @Test
    fun bubbleCollision_fatalHit_triggersGameOverAndHitStop() {
        world.bubbleHp = 1 // 1 hit remaining

        world.obstacles.spawn(
            posX = world.bubbleX,
            posY = world.bubbleY - world.bubbleRadius - 5f,
            velX = 0f, velY = 100f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        assertEquals(0, world.bubbleHp)
        assertEquals(GameState.GAME_OVER, world.state)
        assertTrue(world.hitStopTimer >= GameConfig.HIT_STOP_POP_DURATION - 1e-3f)
    }

    @Test
    fun parriedObstacle_acceleratesUpwardsIntoHorizon_andDespawnsWithBonusScore() {
        world.reset()
        val initScore = world.score

        // Spawn obstacle flagged as PARRIED with upward velocity
        world.obstacles.spawn(
            posX = 500f, posY = 150f,
            velX = 0f, velY = -300f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER,
            obstacleFlags = GameConfig.FLAG_PARRIED
        )

        // Step sub-step: upward velocity should accelerate more negatively (thrust into horizon)
        val initialVy = world.obstacles.vy[0]
        physicsSystem.stepSubstep(GameConfig.SUB_DT)
        assertTrue("Parried obstacle should accelerate upward", world.obstacles.vy[0] < initialVy)

        // Move obstacle to horizon escape threshold (Y <= 60f)
        world.obstacles.y[0] = 50f
        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        // Obstacle should be cleared and bonus score awarded
        assertEquals(0, world.obstacles.count)
        assertTrue("Bonus score should be awarded on horizon escape", world.score > initScore)
    }

    @Test
    fun dodgedObstacle_fallsPastBubble_andDespawnsWithBonusScore() {
        world.reset()
        val initScore = world.score

        // Spawn normal obstacle far down past DESPAWN_MAX_Y (1950 wu)
        world.obstacles.spawn(
            posX = 500f, posY = GameConfig.DESPAWN_MAX_Y + 10f,
            velX = 0f, velY = 100f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)
        assertEquals(0, world.obstacles.count)
        assertEquals(initScore + GameConfig.SCORE_CLEARED_BONUS, world.score)
    }

    @Test
    fun bubbleCollision_decrementsHp_triggersInvuln_andGameOverOnZeroHp() {
        world.reset()
        world.state = GameState.PLAYING
        assertEquals(3, world.bubbleHp)

        // Hit 1: spawn obstacle right on bubble center
        world.obstacles.spawn(
            posX = world.bubbleX, posY = world.bubbleY,
            velX = 0f, velY = 0f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        assertEquals(2, world.bubbleHp)
        assertTrue(world.bubbleInvulnTimer > 0f)
        assertEquals(GameState.PLAYING, world.state)

        // Clear invulnerability timer and hit 2
        world.bubbleInvulnTimer = 0f
        world.obstacles.spawn(
            posX = world.bubbleX, posY = world.bubbleY,
            velX = 0f, velY = 0f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        assertEquals(1, world.bubbleHp)
        assertEquals(GameState.PLAYING, world.state)

        // Clear invulnerability timer and hit 3 (lethal)
        world.bubbleInvulnTimer = 0f
        world.obstacles.spawn(
            posX = world.bubbleX, posY = world.bubbleY,
            velX = 0f, velY = 0f,
            colRadius = 10f, mass = 1f, colRestitution = 0.5f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )
        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        assertEquals(0, world.bubbleHp)
        assertEquals(GameState.GAME_OVER, world.state)
    }

    @Test
    fun bubbleCollision_withPrismCarrier_harvestsPrismsAndDoesNotDamageHp() {
        world.reset()
        world.bubbleHp = 2 // Damaged bubble (2/3 HP)
        val initPrisms = world.prismsCollected
        val initScore = world.bonusScore

        world.obstacles.spawn(
            posX = world.bubbleX,
            posY = world.bubbleY - world.bubbleRadius - 5f,
            velX = 0f, velY = 100f,
            colRadius = GameConfig.PRISM_CARRIER_RADIUS,
            mass = GameConfig.PRISM_CARRIER_MASS,
            colRestitution = GameConfig.PRISM_CARRIER_RESTITUTION,
            obstacleType = GameConfig.TYPE_PRISM_CARRIER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        // Bubble HP should be repaired (+1 up to max), NOT damaged!
        assertEquals(3, world.bubbleHp)
        assertEquals(initPrisms + GameConfig.PRISM_CARRIER_PRISMS_AWARDED, world.prismsCollected)
        assertEquals(initScore + GameConfig.PRISM_CARRIER_SCORE_AWARDED, world.bonusScore)
        assertEquals(0, world.obstacles.count)
    }

    @Test
    fun shieldCollision_withObstacle_triggersKineticRipple() {
        world.reset()
        world.shieldSegments.spawn(
            startX = 400f, startY = 1000f,
            endX = 600f, endY = 1000f,
            velSwipeX = 0f, velSwipeY = 0f,
            stroke = 1, pointer = 0
        )

        world.obstacles.spawn(
            posX = 500f, posY = 985f,
            velX = 0f, velY = 300f,
            colRadius = GameConfig.DRIFTER_RADIUS,
            mass = GameConfig.DRIFTER_MASS,
            colRestitution = GameConfig.DRIFTER_RESTITUTION,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        physicsSystem.stepSubstep(GameConfig.SUB_DT)

        assertTrue("Shield segment should have non-zero ripple timer upon impact", world.shieldSegments.rippleTimer[0] > 0f)
        assertTrue("Shield segment should have non-zero ripple intensity", world.shieldSegments.rippleIntensity[0] > 0f)
    }

    @Test
    fun zeroAllocation_probe_physicsSystem() {
        if (!AllocationProbe.isAvailable) return
        AllocationProbe.warmUp()

        // Setup active shield and bouncing obstacle
        world.shieldSegments.spawn(
            startX = 100f, startY = 800f,
            endX = 900f, endY = 800f,
            velSwipeX = 0f, velSwipeY = 0f,
            stroke = 1, pointer = 0
        )
        world.obstacles.spawn(
            posX = 500f, posY = 400f,
            velX = 100f, velY = 200f,
            colRadius = 25f, mass = 1f, colRestitution = 0.8f,
            obstacleType = GameConfig.TYPE_DRIFTER
        )

        // Warm up JIT
        for (i in 0 until 3000) {
            if (world.shieldSegments.count == 0) {
                world.shieldSegments.spawn(100f, 800f, 900f, 800f, 0f, 0f, 1, 0)
            }
            if (world.obstacles.count == 0) {
                world.obstacles.spawn(500f, 400f, 100f, 200f, 25f, 1f, 0.8f, GameConfig.TYPE_DRIFTER)
            }
            physicsSystem.stepSubstep(GameConfig.SUB_DT)
        }

        val before = AllocationProbe.allocatedBytes()

        val iterations = 5000
        for (i in 1..iterations) {
            if (world.shieldSegments.count == 0) {
                world.shieldSegments.spawn(100f, 800f, 900f, 800f, 0f, 0f, 1, 0)
            }
            if (world.obstacles.count == 0) {
                world.obstacles.spawn(500f, 400f, 50f, 150f, 25f, 1f, 0.8f, GameConfig.TYPE_DRIFTER)
            }
            physicsSystem.stepSubstep(GameConfig.SUB_DT)
        }

        val after = AllocationProbe.allocatedBytes()
        val allocated = after - before

        assertTrue("PhysicsSystem hot path must allocate 0 bytes per sub-step (delta = $allocated bytes)", allocated <= 128L)
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
