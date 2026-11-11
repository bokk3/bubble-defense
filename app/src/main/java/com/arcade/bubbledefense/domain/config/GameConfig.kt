package com.arcade.bubbledefense.domain.config

/**
 * Canonical balance constants, pool capacities, and engine tuning knobs for Bubble Defense.
 *
 * Every constant here matches `docs/PHYSICS_SPEC.md` and `docs/GAME_DESIGN.md`.
 * Coordinate space: Virtual World Space 1000 x 1800 wu, origin top-left, **+Y points DOWN**.
 */
object GameConfig {

    // ---------------------------------------------------------------------------------------------
    // Virtual World & Coordinate Bounds (wu)
    // ---------------------------------------------------------------------------------------------

    /** Virtual world width in world units (wu). */
    const val WORLD_WIDTH: Float = 1000f

    /** Virtual world height in world units (wu). */
    const val WORLD_HEIGHT: Float = 1800f

    /** Bubble normalized horizontal anchor coordinate in `[0, 1]`. */
    const val BUBBLE_NORMALIZED_X: Float = 0.5f

    /** Bubble normalized vertical anchor coordinate in `[0, 1]`. */
    const val BUBBLE_NORMALIZED_Y: Float = 0.82f

    /** Bubble center X in world units: `WORLD_WIDTH * BUBBLE_NORMALIZED_X`. */
    const val BUBBLE_CENTER_X: Float = 500f

    /** Bubble center Y in world units: `WORLD_HEIGHT * BUBBLE_NORMALIZED_Y`. */
    const val BUBBLE_CENTER_Y: Float = 1476f

    /** Off-screen top spawn baseline coordinate (wu) emerging at 2.5D horizon vanishing point. */
    const val SPAWN_LINE_Y: Float = 30f

    /** Despawn horizontal left bound (wu). */
    const val DESPAWN_MIN_X: Float = -150f

    /** Despawn horizontal right bound (wu). */
    const val DESPAWN_MAX_X: Float = 1150f

    /** Despawn vertical upper bound (wu). */
    const val DESPAWN_MIN_Y: Float = -400f

    /** Despawn vertical lower bound (wu). */
    const val DESPAWN_MAX_Y: Float = 1950f

    // ---------------------------------------------------------------------------------------------
    // Simulation Loop & Timestep
    // ---------------------------------------------------------------------------------------------

    /** Fixed simulation timestep (seconds) corresponding to 120Hz physics. */
    const val FIXED_DT: Float = 1f / 120f

    /** Number of physics collision sub-steps per fixed frame step. */
    const val SUBSTEPS: Int = 4

    /** Duration of each narrow-phase sub-step: `FIXED_DT / SUBSTEPS` (1/480 s). */
    const val SUB_DT: Float = FIXED_DT / SUBSTEPS.toFloat()

    /** Maximum allowed frame delta time (s) before clamping to prevent spiral of death. */
    const val MAX_FRAME_TIME: Float = 0.05f

    /** Maximum number of fixed steps executed per render frame during lag recovery. */
    const val MAX_STEPS_PER_FRAME: Int = 6

    /** Hit-stop freeze frame real-time duration on Perfect Parry (s). */
    const val HIT_STOP_PARRY_DURATION: Float = 0.028f

    /** Hit-stop freeze frame real-time duration on Crusher impact (s). */
    const val HIT_STOP_CRUSHER_DURATION: Float = 0.020f

    /** Hit-stop freeze frame duration on Bubble pop / fatal impact (s). */
    const val HIT_STOP_POP_DURATION: Float = 0.140f

    // ---------------------------------------------------------------------------------------------
    // Physics & Collision Parameters
    // ---------------------------------------------------------------------------------------------

    /** Downward acceleration due to gravity (wu/s^2, +Y is down). */
    const val GRAVITY_Y: Float = 520f

    /** Maximum clamped linear obstacle velocity magnitude (wu/s). */
    const val MAX_OBSTACLE_SPEED: Float = 3600f

    /** Shield segment capsule half-thickness radius (wu). Total core thickness is 12 wu. */
    const val SHIELD_RADIUS: Float = 6f

    /** Maximum anti-sinking positional projection distance applied in a single sub-step (wu). */
    const val MAX_PUSHOUT: Float = 24f

    /** Active parry window lifetime (s) during which swipe velocity transfers to obstacles. */
    const val PARRY_WINDOW: Float = 0.150f

    /** Multiplier applied to decayed swipe velocity during parry impulse resolution. */
    const val PARRY_GAIN: Float = 1.0f

    /** Minimum normal velocity (wu/s) required along contact normal to trigger a Parry. */
    const val PARRY_MIN_NORMAL_SPEED: Float = 250f

    /** Maximum clamped swipe velocity magnitude stored in a shield segment (wu/s). */
    const val MAX_SWIPE_SPEED: Float = 3000f

    /** Exponential moving average filter time constant (s) for pointer swipe velocity. */
    const val SWIPE_VELOCITY_TAU: Float = 0.020f

    /** Structural integrity damage sustained by shield per unit of normal impulse (integrity / (mass * wu/s)). */
    const val IMPACT_DAMAGE_PER_IMPULSE: Float = 0.05f

    /** Minimum cooldown (s) before the same obstacle can damage the same shield segment again. */
    const val SHIELD_DAMAGE_COOLDOWN: Float = 0.10f

    /** Flat structural integrity drained by Crusher impacts (fraction of max integrity = 60). */
    const val CRUSHER_INTEGRITY_DRAIN: Float = 60f

    /** Downward displacement (wu) applied to segment endpoints upon Crusher impact. */
    const val CRUSHER_RECOIL_DISTANCE: Float = 14f

    /** Angular spread (radians) of Splitter child shards: 25 degrees. */
    const val SPLIT_SPREAD_ANGLE: Float = 0.43633232f

    /** Speed multiplier applied to Splitter child shards relative to parent impact velocity. */
    const val SPLIT_SPEED_MULTIPLIER: Float = 1.4f

    /** CCD swept time-of-impact conservative back-off fraction. */
    const val CCD_TOI_BACKOFF: Float = 1e-4f

    /** Maximum TOI continuous collision substep iterations. */
    const val MAX_TOI_ITERATIONS: Int = 2

    /** Collision tolerance distance below which normal direction is considered degenerate (wu). */
    const val COLLISION_EPSILON: Float = 1e-6f

    /** Squared segment length at or below which segment is treated as a degenerate point (wu^2). */
    const val DEGENERATE_SEGMENT_LENGTH_SQ: Float = 1e-8f

    // ---------------------------------------------------------------------------------------------
    // Bubble (Target) Properties
    // ---------------------------------------------------------------------------------------------

    /** Collision radius of the bubble membrane in world units (wu). */
    const val BUBBLE_RADIUS: Float = 64f

    /** Maximum health points (membrane layers) of the bubble. */
    const val BUBBLE_MAX_HP: Int = 3

    /** Invulnerability duration (s) after bubble membrane absorbs damage. */
    const val BUBBLE_INVULN_TIME: Float = 1.0f

    /** Distance from bubble surface within which the proximity warning ring illuminates (wu). */
    const val WARNING_RING_RANGE: Float = 220f

    /** Vertex count used for procedural soft-body sine-wave harmonic deformation. */
    const val BUBBLE_VERTICES: Int = 64

    // ---------------------------------------------------------------------------------------------
    // Ink Economy & Stroke Decimation
    // ---------------------------------------------------------------------------------------------

    /** Maximum ink capacity available in the ink tank. */
    const val INK_MAX: Float = 100f

    /** Ink consumed per world unit of drawn shield length (1250 wu total per full tank). */
    const val INK_PER_WU: Float = 0.08f

    /** Cooldown duration (s) required after all fingers lift before ink begins regenerating. */
    const val INK_REGEN_DELAY: Float = 0.40f

    /** Rate of ink regeneration per second after cooldown completes (0 to 100 in 1.82s). */
    const val INK_REGEN_RATE: Float = 55f

    /** Instant ink/energy refund granted upon executing a Perfect Parry (wu equivalent: 25% of max). */
    const val PARRY_INK_REFUND: Float = 25f

    /** Minimum ink required to initiate a new stroke segment (wu equivalent: 50 wu). */
    const val INK_MIN_TO_START: Float = 4f

    /** Minimum stroke segment length (wu) below which pointer samples are decimated. */
    const val MIN_SEGMENT_LENGTH: Float = 12f

    /** Maximum stroke segment length (wu) before fast flicks are subdivided. */
    const val MAX_SEGMENT_LENGTH: Float = 48f

    /** Maximum simultaneous pointer drag strokes tracked in parallel. */
    const val MAX_POINTERS: Int = 4

    /** Default lifespan (s) of an intact shield segment before natural fade-out. */
    const val SEGMENT_LIFETIME: Float = 1.5f
    const val SHIELD_LIFETIME: Float = SEGMENT_LIFETIME

    /** Maximum structural integrity of a newly committed shield segment. */
    const val SEGMENT_INTEGRITY: Float = 100f

    /** Duration (s) at the end of a segment's life during which opacity fades to 0. */
    const val SEGMENT_FADE_TIME: Float = 0.30f

    // ---------------------------------------------------------------------------------------------
    // Pre-Allocated Entity Pool Capacities (Power-of-Two for Ring Buffers)
    // ---------------------------------------------------------------------------------------------

    /** Maximum concurrent active falling obstacles in simulation. */
    const val OBSTACLE_POOL_CAPACITY: Int = 128

    /** Maximum concurrent active shield segments. */
    const val SHIELD_SEGMENT_POOL_CAPACITY: Int = 256

    /** Maximum concurrent active spark and shatter particles. */
    const val PARTICLE_POOL_CAPACITY: Int = 512

    /** Maximum concurrent floating combat text popups. */
    const val FLOATING_TEXT_POOL_CAPACITY: Int = 32

    /** Capacity of the pointer input sample ring buffer. */
    const val INPUT_RING_CAPACITY: Int = 256

    // ---------------------------------------------------------------------------------------------
    // Obstacle Archetype Identifiers & Archetype Specs
    // ---------------------------------------------------------------------------------------------

    const val TYPE_DRIFTER: Byte = 0
    const val TYPE_NEEDLE: Byte = 1
    const val TYPE_CRUSHER: Byte = 2
    const val TYPE_SPLITTER: Byte = 3
    const val TYPE_SHARD: Byte = 4
    const val TYPE_PRISM_CARRIER: Byte = 5

    const val FLAG_HAS_SPLIT: Int = 1 shl 0
    const val FLAG_CCD: Int = 1 shl 1
    const val FLAG_DEFLECTED: Int = 1 shl 2
    const val FLAG_PARRIED: Int = 1 shl 3

    // Prism Carrier (Peaceful Emerald Bounty Orb)
    const val PRISM_CARRIER_RADIUS: Float = 22f
    const val PRISM_CARRIER_MASS: Float = 0.8f
    const val PRISM_CARRIER_RESTITUTION: Float = 0.85f
    const val PRISM_CARRIER_MIN_SPEED: Float = 200f
    const val PRISM_CARRIER_MAX_SPEED: Float = 320f
    const val PRISM_CARRIER_SPAWN_INTERVAL: Float = 14.0f
    const val PRISM_CARRIER_PRISMS_AWARDED: Int = 5
    const val PRISM_CARRIER_SCORE_AWARDED: Int = 250

    // Drifter
    const val DRIFTER_RADIUS: Float = 28f
    const val DRIFTER_MASS: Float = 1.0f
    const val DRIFTER_RESTITUTION: Float = 0.75f
    const val DRIFTER_MIN_SPEED: Float = 260f
    const val DRIFTER_MAX_SPEED: Float = 380f
    const val DRIFTER_THREAT_COST: Float = 1.0f

    // Needle
    const val NEEDLE_RADIUS: Float = 7f
    const val NEEDLE_MASS: Float = 0.35f
    const val NEEDLE_RESTITUTION: Float = 0.60f
    const val NEEDLE_MIN_SPEED: Float = 900f
    const val NEEDLE_MAX_SPEED: Float = 1400f
    const val NEEDLE_THREAT_COST: Float = 1.5f
    const val NEEDLE_UNLOCK_TIME: Float = 20f

    // Crusher
    const val CRUSHER_RADIUS: Float = 46f
    const val CRUSHER_MASS: Float = 4.0f
    const val CRUSHER_RESTITUTION: Float = 0.30f
    const val CRUSHER_MIN_SPEED: Float = 180f
    const val CRUSHER_MAX_SPEED: Float = 260f
    const val CRUSHER_THREAT_COST: Float = 4.0f
    const val CRUSHER_UNLOCK_TIME: Float = 75f

    // Splitter
    const val SPLITTER_RADIUS: Float = 32f
    const val SPLITTER_MASS: Float = 1.2f
    const val SPLITTER_RESTITUTION: Float = 0.70f
    const val SPLITTER_MIN_SPEED: Float = 300f
    const val SPLITTER_MAX_SPEED: Float = 420f
    const val SPLITTER_THREAT_COST: Float = 2.5f
    const val SPLITTER_UNLOCK_TIME: Float = 45f

    // Shard (Splitter child)
    const val SHARD_RADIUS: Float = 14f
    const val SHARD_MASS: Float = 0.4f
    const val SHARD_RESTITUTION: Float = 0.80f

    // ---------------------------------------------------------------------------------------------
    // Wave Director & Sawtooth Pacing
    // ---------------------------------------------------------------------------------------------

    const val WAVE_PERIOD: Float = 20f
    const val WAVE_CRUNCH_DURATION: Float = 14f
    const val WAVE_HARVEST_DURATION: Float = 6f
    const val MAX_THREAT_BUDGET: Float = 10f
    const val BASE_THREAT_RATE: Float = 2.4f
    const val THREAT_RATE_ACCEL: Float = 0.08f
    const val SPAWN_COOLDOWN_TIME: Float = 0.20f
    const val THREAT_TELEGRAPH_TIME: Float = 0.350f

    // ---------------------------------------------------------------------------------------------
    // Scoring, Altitude & Combo Multipliers
    // ---------------------------------------------------------------------------------------------

    const val ASCENT_SPEED_BASE: Float = 420f
    const val ASCENT_ACCEL: Float = 4.0f
    const val ASCENT_SPEED_MAX: Float = 950f
    const val WU_PER_METER: Float = 12f

    const val COMBO_TIMEOUT: Float = 3.0f
    const val COMBO_STEP: Float = 0.25f
    const val COMBO_MAX_MULTIPLIER: Float = 4.0f

    const val SCORE_DEFLECT_BONUS: Int = 5
    const val SCORE_CLEARED_BONUS: Int = 10
    const val SCORE_SPLIT_BONUS: Int = 10
    const val SCORE_PARRY_BASE_BONUS: Int = 50
    const val SCORE_CRUSHER_PARRY_BONUS: Int = 100

    // ---------------------------------------------------------------------------------------------
    // Sensory Amplification & Juice Matrix (`kinetic-flow-architect`)
    // ---------------------------------------------------------------------------------------------

    const val TRAUMA_DEFLECT: Float = 0.10f
    const val TRAUMA_PARRY: Float = 0.28f
    const val TRAUMA_CRUSHER: Float = 0.50f
    const val TRAUMA_BUBBLE_HIT: Float = 0.60f
    const val TRAUMA_POP: Float = 0.90f
    const val TRAUMA_DECAY_RATE: Float = 1.6f

    const val MAX_SHAKE_OFFSET_PX: Float = 24f
    const val MAX_SHAKE_ROTATION_RAD: Float = 0.03f
    const val SHAKE_NOISE_FREQUENCY_HZ: Float = 18f

    // ---------------------------------------------------------------------------------------------
    // Flow-State Economy & Quarantined SDK (`arcade-economy-architect`)
    // ---------------------------------------------------------------------------------------------

    const val CLUTCH_REVIVE_FUSE_SECONDS: Float = 4.0f
    const val CLUTCH_REVIVE_MIN_PB_RATIO: Float = 0.60f
    const val CLUTCH_REVIVE_INVULN_SECONDS: Float = 2.5f
    const val CLUTCH_REVIVE_RAMP_DURATION: Float = 1.2f
    const val INTERSTITIAL_MIN_COOLDOWN_SECONDS: Float = 180f
    const val INTERSTITIAL_MIN_RUN_DURATION: Float = 45f
    const val INTERSTITIAL_MIN_RUNS_BEFORE_FIRST: Int = 3
    const val PRISMS_PER_COMBO_MILESTONE: Int = 1
    const val COMBO_MILESTONE_STEP: Int = 5

    // ---------------------------------------------------------------------------------------------
    // 2.5D Perspective Camera & Highway Lane Tuning
    // ---------------------------------------------------------------------------------------------

    /** Default enablement of 2.5D perspective projection mode. */
    const val CAMERA_PERSPECTIVE_ENABLED: Boolean = true

    /** Normalized vertical screen position of the horizon vanishing point (below top HUD). */
    const val CAMERA_HORIZON_RATIO_Y: Float = 0.28f

    /** Longitudinal world Y position of the camera station (wu). */
    const val CAMERA_Y_WU: Float = 1850f

    /** Elevation height of the camera station above the ground plane (wu). */
    const val CAMERA_HEIGHT_WU: Float = 450f

    /** Focal depth distance from camera to bubble anchor (1850 - 1476 = 374 wu). */
    const val CAMERA_BUBBLE_DEPTH_D: Float = CAMERA_Y_WU - BUBBLE_CENTER_Y

    /** Left curb boundary of the playable highway lane in world units (wu). */
    const val LANE_LEFT_CURB_X: Float = 100f

    /** Right curb boundary of the playable highway lane in world units (wu). */
    const val LANE_RIGHT_CURB_X: Float = 900f

    /** Playable highway lane width (wu): `LANE_RIGHT_CURB_X - LANE_LEFT_CURB_X`. */
    const val LANE_WIDTH_WU: Float = LANE_RIGHT_CURB_X - LANE_LEFT_CURB_X

    /** Vertical extrusion height of neon shield energy walls above the ground lane (wu). */
    const val SHIELD_EXTRUSION_HEIGHT_WU: Float = 80f

    /** Longitudinal spacing between forward-scrolling floor crosslines (wu). */
    const val FLOOR_CROSSLINE_SPACING_WU: Float = 100f
}
