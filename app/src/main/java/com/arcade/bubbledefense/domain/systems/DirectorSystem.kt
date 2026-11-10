package com.arcade.bubbledefense.domain.systems

import com.arcade.bubbledefense.domain.config.GameConfig
import com.arcade.bubbledefense.domain.world.GameWorld
import com.arcade.bubbledefense.engine.math.Vec2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sawtooth Wave AI Director system.
 *
 * Implements the pacing and threat choreography specified in `docs/GAME_DESIGN.md` §3
 * and `kinetic-flow-architect/SKILL.md`.
 *
 * Responsibilities:
 * 1. Executes 20-second Sawtooth Pacing Cycles:
 *    - 14s High-Intensity Crunch: dense spawns, multi-threat pressure (phi = 1.4x).
 *    - 6s Breather / Harvest Valley: relaxed threat rate (phi = 0.35x), Prism harvesting.
 * 2. Manages dynamic threat budget: dot{B}(t) = lambda(t) * phi(t), capped at [GameConfig.MAX_THREAT_BUDGET].
 * 3. Enforces archetype unlocking schedule:
 *    - Drifter: t >= 0s (cost 1.0)
 *    - Needle: t >= 20s (cost 1.5)
 *    - Splitter: t >= 45s (cost 2.5)
 *    - Crusher: t >= 75s (cost 4.0)
 * 4. Dispatches top-bezel telegraph warning indicators for high-speed Needles and Splitters (350ms duration).
 * 5. Deterministic xorshift32 pseudo-random number generator for reproducible wave generation.
 *
 * Zero-allocation guarantee: All RNG, selection, and telegraphing use primitive arrays with 0 heap boxing.
 */
class DirectorSystem(
    private val world: GameWorld,
    initialSeed: Int = 1337
) {
    // 32-bit Xorshift PRNG state
    private var rngState: Int = if (initialSeed != 0) initialSeed else 1337

    /** Accumulator for soft currency prism generation during harvest valleys. */
    var harvestPrismTimer: Float = 0f

    /** Advances PRNG state and returns a pseudo-random integer. */
    fun nextInt(): Int {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return x
    }

    /** Returns a pseudo-random float in `[0f, 1f)`. */
    fun nextFloat(): Float = (nextInt().toLong() and 0xFFFFFFFFL).toFloat() / 4294967296.0f

    /** Returns a pseudo-random float in `[min, max]`. */
    fun nextFloat(min: Float, max: Float): Float = min + nextFloat() * (max - min)

    /**
     * Steps the wave director by [dt] seconds.
     */
    fun step(dt: Float) {
        world.directorWaveTimer += dt

        // 1. Sawtooth Pacing Phase calculation & Infinite Wave Tracking
        val cycleTime = world.directorWaveTimer % GameConfig.WAVE_PERIOD
        val isCrunch = cycleTime < GameConfig.WAVE_CRUNCH_DURATION
        val phi = if (isCrunch) 1.4f else 0.35f

        val calculatedWave = 1 + (world.directorWaveTimer / GameConfig.WAVE_PERIOD).toInt()
        if (calculatedWave != world.currentWave) {
            world.currentWave = calculatedWave
            world.waveAnnouncementTimer = 2.5f
        }
        world.wavePhaseName = if (isCrunch) "CRUNCH" else "HARVEST"
        if (world.waveAnnouncementTimer > 0f) {
            world.waveAnnouncementTimer -= dt
        }

        // 2. Threat Budget Accumulation: dot{B}(t) = (base + accel * t) * waveScale * phi
        val waveScale = 1.0f + 0.10f * (world.currentWave - 1)
        val baseRate = (GameConfig.BASE_THREAT_RATE + GameConfig.THREAT_RATE_ACCEL * world.gameTime) * waveScale
        val threatRate = baseRate * phi
        world.directorThreatBudget = min(
            GameConfig.MAX_THREAT_BUDGET,
            world.directorThreatBudget + threatRate * dt
        )

        // 3. Update Active Telegraph Warning Indicators
        var t = 0
        while (t < world.telegraphCount) {
            world.telegraphTimer[t] -= dt
            if (world.telegraphTimer[t] <= 0f) {
                // Swap-remove expired telegraph
                val last = --world.telegraphCount
                if (t != last) {
                    world.telegraphX[t] = world.telegraphX[last]
                    world.telegraphTimer[t] = world.telegraphTimer[last]
                    world.telegraphType[t] = world.telegraphType[last]
                }
            } else {
                t++
            }
        }

        // 4. Obstacle Spawning
        if (world.directorSpawnCooldown > 0f) {
            world.directorSpawnCooldown -= dt
        }

        while (world.directorSpawnCooldown <= 0f &&
            world.directorThreatBudget >= GameConfig.DRIFTER_THREAT_COST &&
            world.obstacles.count < world.obstacles.capacity
        ) {
            val spawned = trySpawnObstacle()
            if (!spawned) break
            world.directorSpawnCooldown = GameConfig.SPAWN_COOLDOWN_TIME
        }

        // 5. Soft Currency Prism Spawning during Harvest Valleys (6s Breather)
        if (!isCrunch) {
            harvestPrismTimer += dt
            if (harvestPrismTimer >= 2.0f) {
                harvestPrismTimer = 0f
                world.prismsCollected += 1
                val px = nextFloat(300f, 700f)
                val py = nextFloat(900f, 1300f)
                world.particles.spawn(
                    posX = px,
                    posY = py,
                    velX = 0f,
                    velY = -40f,
                    lifetime = 0.6f,
                    particleSize = 4f,
                    rot = 0f,
                    argbColor = 0xFF00FF99.toInt()
                )
            }
        }
    }

    private fun trySpawnObstacle(): Boolean {
        val t = world.gameTime
        val budget = world.directorThreatBudget

        // Check availability of archetypes based on time and threat cost
        val canCrusher = t >= GameConfig.CRUSHER_UNLOCK_TIME && budget >= GameConfig.CRUSHER_THREAT_COST
        val canSplitter = t >= GameConfig.SPLITTER_UNLOCK_TIME && budget >= GameConfig.SPLITTER_THREAT_COST
        val canNeedle = t >= GameConfig.NEEDLE_UNLOCK_TIME && budget >= GameConfig.NEEDLE_THREAT_COST
        val canDrifter = budget >= GameConfig.DRIFTER_THREAT_COST

        if (!canDrifter) return false

        // Compute selection weights
        val weightCrusher = if (canCrusher) 20 else 0
        val weightSplitter = if (canSplitter) 30 else 0
        val weightNeedle = if (canNeedle) 40 else 0
        val weightDrifter = 50

        val totalWeight = weightCrusher + weightSplitter + weightNeedle + weightDrifter
        val pick = (nextFloat() * totalWeight).toInt()

        val selectedType: Byte
        val cost: Float

        when {
            pick < weightCrusher -> {
                selectedType = GameConfig.TYPE_CRUSHER
                cost = GameConfig.CRUSHER_THREAT_COST
            }
            pick < weightCrusher + weightSplitter -> {
                selectedType = GameConfig.TYPE_SPLITTER
                cost = GameConfig.SPLITTER_THREAT_COST
            }
            pick < weightCrusher + weightSplitter + weightNeedle -> {
                selectedType = GameConfig.TYPE_NEEDLE
                cost = GameConfig.NEEDLE_THREAT_COST
            }
            else -> {
                selectedType = GameConfig.TYPE_DRIFTER
                cost = GameConfig.DRIFTER_THREAT_COST
            }
        }

        world.directorThreatBudget -= cost
        spawnArchetype(selectedType)
        return true
    }

    private fun spawnArchetype(type: Byte) {
        val spawnX = nextFloat(100f, 900f)
        val spawnY = GameConfig.SPAWN_LINE_Y

        // Target point in lower half (around bubble defense zone)
        val targetX = nextFloat(300f, 700f)
        val targetY = nextFloat(1200f, 1600f)

        val dx = targetX - spawnX
        val dy = targetY - spawnY
        val dist = sqrt(dx * dx + dy * dy)
        val dirX = if (dist > 1e-4f) dx / dist else 0f
        val dirY = if (dist > 1e-4f) dy / dist else 1f

        when (type) {
            GameConfig.TYPE_DRIFTER -> {
                val speed = nextFloat(GameConfig.DRIFTER_MIN_SPEED, GameConfig.DRIFTER_MAX_SPEED)
                world.obstacles.spawn(
                    posX = spawnX, posY = spawnY,
                    velX = dirX * speed, velY = dirY * speed,
                    colRadius = GameConfig.DRIFTER_RADIUS,
                    mass = GameConfig.DRIFTER_MASS,
                    colRestitution = GameConfig.DRIFTER_RESTITUTION,
                    obstacleType = GameConfig.TYPE_DRIFTER
                )
            }
            GameConfig.TYPE_NEEDLE -> {
                val speed = nextFloat(GameConfig.NEEDLE_MIN_SPEED, GameConfig.NEEDLE_MAX_SPEED)
                world.obstacles.spawn(
                    posX = spawnX, posY = spawnY,
                    velX = dirX * speed, velY = dirY * speed,
                    colRadius = GameConfig.NEEDLE_RADIUS,
                    mass = GameConfig.NEEDLE_MASS,
                    colRestitution = GameConfig.NEEDLE_RESTITUTION,
                    obstacleType = GameConfig.TYPE_NEEDLE,
                    obstacleFlags = GameConfig.FLAG_CCD
                )
                // Add top-bezel telegraph warning chevron
                addTelegraph(spawnX, type)
            }
            GameConfig.TYPE_SPLITTER -> {
                val speed = nextFloat(GameConfig.SPLITTER_MIN_SPEED, GameConfig.SPLITTER_MAX_SPEED)
                world.obstacles.spawn(
                    posX = spawnX, posY = spawnY,
                    velX = dirX * speed, velY = dirY * speed,
                    colRadius = GameConfig.SPLITTER_RADIUS,
                    mass = GameConfig.SPLITTER_MASS,
                    colRestitution = GameConfig.SPLITTER_RESTITUTION,
                    obstacleType = GameConfig.TYPE_SPLITTER,
                    angularVelocity = nextFloat(-2f, 2f)
                )
                addTelegraph(spawnX, type)
            }
            GameConfig.TYPE_CRUSHER -> {
                val speed = nextFloat(GameConfig.CRUSHER_MIN_SPEED, GameConfig.CRUSHER_MAX_SPEED)
                world.obstacles.spawn(
                    posX = spawnX, posY = spawnY,
                    velX = dirX * speed, velY = dirY * speed,
                    colRadius = GameConfig.CRUSHER_RADIUS,
                    mass = GameConfig.CRUSHER_MASS,
                    colRestitution = GameConfig.CRUSHER_RESTITUTION,
                    obstacleType = GameConfig.TYPE_CRUSHER,
                    angularVelocity = nextFloat(-1f, 1f)
                )
            }
        }
    }

    private fun addTelegraph(x: Float, type: Byte) {
        if (world.telegraphCount < GameWorld.MAX_TELEGRAPHS) {
            val idx = world.telegraphCount++
            world.telegraphX[idx] = x
            world.telegraphTimer[idx] = GameConfig.THREAT_TELEGRAPH_TIME
            world.telegraphType[idx] = type
        }
    }

    /** Resets the director state with a new seed. */
    fun reset(seed: Int = 1337) {
        rngState = if (seed != 0) seed else 1337
        harvestPrismTimer = 0f
    }
}
