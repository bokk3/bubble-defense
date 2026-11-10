package com.arcade.bubbledefense.engine.math

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Squared-length threshold below which a vector is treated as the zero vector
 * (i.e. length below 1e-6 wu). Used by [Vec2.normalized] and [Vec2.clampLength].
 */
const val VEC2_EPSILON_SQ: Float = 1e-12f

/** Mask selecting the low 32 bits of a packed [Vec2]. */
const val VEC2_LOW_MASK: Long = 0xFFFF_FFFFL

/**
 * Packs two floats into one `Long`: [x] raw bits in the high 32 bits, [y] raw bits in the low 32 bits.
 *
 * `toRawBits()` is used (not `toBits()`) so every bit pattern, including `-0f` and NaN payloads,
 * round-trips exactly. The low word is masked to prevent sign extension of negative `y` values
 * from corrupting the high word.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun packFloats(x: Float, y: Float): Long =
    (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and VEC2_LOW_MASK)

/** Extracts the x component (high 32 bits) from a packed [Vec2] value. */
@Suppress("NOTHING_TO_INLINE")
inline fun unpackX(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())

/** Extracts the y component (low 32 bits) from a packed [Vec2] value. */
@Suppress("NOTHING_TO_INLINE")
inline fun unpackY(packed: Long): Float = Float.fromBits(packed.toInt())

/** Creates a [Vec2] from components without any heap allocation. */
@Suppress("NOTHING_TO_INLINE")
inline fun Vec2(x: Float, y: Float): Vec2 = Vec2(packFloats(x, y))

/** Scalar-on-the-left multiplication: `s * v`. */
@Suppress("NOTHING_TO_INLINE")
inline operator fun Float.times(v: Vec2): Vec2 = Vec2(this * v.x, this * v.y)

/**
 * Zero-allocation 2D vector in world units.
 *
 * Represented at runtime as a primitive `long` ([packed]); the JVM never allocates an object for it
 * unless it is boxed. Boxing happens ONLY when a `Vec2` is used as a nullable type (`Vec2?`), a generic
 * type argument (`List<Vec2>`, `Array<Vec2>`), or passed as `Any` — all banned on the hot path
 * (see `.cursorrules` §3). Store vectors in pools as `FloatArray` pairs or a `LongArray` of [packed].
 *
 * Coordinate convention: world space, **+Y points down** (screen convention). Consequently:
 * - [perpendicular] and positive-angle [rotated] turn **clockwise on screen**.
 * - [cross] `> 0` means `other` lies clockwise of `this` on screen.
 *
 * Equality (`==`) compares raw bits: `Vec2(0f, 0f) != Vec2(-0f, 0f)`. Use [approxEquals] for numeric
 * comparisons.
 */
@JvmInline
value class Vec2(val packed: Long) {

    /** X component (wu). */
    inline val x: Float get() = unpackX(packed)

    /** Y component (wu, +Y down). */
    inline val y: Float get() = unpackY(packed)

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun component1(): Float = x

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun component2(): Float = y

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun times(scalar: Float): Vec2 = Vec2(x * scalar, y * scalar)

    /** Component-wise division. Division by zero follows IEEE-754 (yields ±Infinity / NaN). */
    @Suppress("NOTHING_TO_INLINE")
    inline operator fun div(scalar: Float): Vec2 = Vec2(x / scalar, y / scalar)

    @Suppress("NOTHING_TO_INLINE")
    inline operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    /** Dot product: `x·ox + y·oy`. */
    @Suppress("NOTHING_TO_INLINE")
    inline infix fun dot(other: Vec2): Float = x * other.x + y * other.y

    /** 2D cross product (z of the 3D cross): `x·oy − y·ox`. `UNIT_X cross UNIT_Y == 1`. */
    @Suppress("NOTHING_TO_INLINE")
    inline infix fun cross(other: Vec2): Float = x * other.y - y * other.x

    @Suppress("NOTHING_TO_INLINE")
    inline fun lengthSquared(): Float = x * x + y * y

    @Suppress("NOTHING_TO_INLINE")
    inline fun length(): Float = sqrt(x * x + y * y)

    @Suppress("NOTHING_TO_INLINE")
    inline fun distanceSquaredTo(other: Vec2): Float {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun distanceTo(other: Vec2): Float = sqrt(distanceSquaredTo(other))

    /**
     * Unit-length copy of this vector, or [ZERO] when the length is below 1e-6 wu
     * (never returns NaN for a finite input).
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun normalized(): Vec2 {
        val lenSq = x * x + y * y
        if (lenSq <= VEC2_EPSILON_SQ) return ZERO
        val inv = 1f / sqrt(lenSq)
        return Vec2(x * inv, y * inv)
    }

    /** `(-y, x)`: +90° in y-up math convention, i.e. **clockwise on screen** (y-down). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun perpendicular(): Vec2 = Vec2(-y, x)

    /**
     * Mirror reflection about a surface with unit normal [normal]: `v − 2(v·n)n`.
     * The caller guarantees `‖normal‖ = 1`; length is then preserved exactly (up to float rounding).
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun reflect(normal: Vec2): Vec2 {
        val k = 2f * (x * normal.x + y * normal.y)
        return Vec2(x - normal.x * k, y - normal.y * k)
    }

    /** Linear interpolation: `this + (target − this)·t` (t is not clamped). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun lerp(target: Vec2, t: Float): Vec2 = Vec2(x + (target.x - x) * t, y + (target.y - y) * t)

    /** Returns this vector scaled down so its length is at most [maxLength] (`maxLength ≥ 0`). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun clampLength(maxLength: Float): Vec2 {
        val lenSq = x * x + y * y
        if (lenSq <= maxLength * maxLength || lenSq <= VEC2_EPSILON_SQ) return this
        val scale = maxLength / sqrt(lenSq)
        return Vec2(x * scale, y * scale)
    }

    /** Rotation by [radians]; positive angles rotate **clockwise on screen** (y-down). */
    @Suppress("NOTHING_TO_INLINE")
    inline fun rotated(radians: Float): Vec2 {
        val c = cos(radians)
        val s = sin(radians)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun withX(newX: Float): Vec2 = Vec2(newX, y)

    @Suppress("NOTHING_TO_INLINE")
    inline fun withY(newY: Float): Vec2 = Vec2(x, newY)

    @Suppress("NOTHING_TO_INLINE")
    inline fun isFinite(): Boolean = x.isFinite() && y.isFinite()

    /** Component-wise comparison with absolute tolerance [epsilon]. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun approxEquals(other: Vec2, epsilon: Float): Boolean =
        abs(x - other.x) <= epsilon && abs(y - other.y) <= epsilon

    /** Debug only — allocates a String. Never call on the hot path. */
    override fun toString(): String = "Vec2($x, $y)"

    companion object {
        /** `(0, 0)` — packed value `0L`. */
        val ZERO: Vec2 = Vec2(0L)
        val ONE: Vec2 = Vec2(1f, 1f)
        val UNIT_X: Vec2 = Vec2(1f, 0f)
        val UNIT_Y: Vec2 = Vec2(0f, 1f)

        /** Screen-up `(0, -1)`: away from the bubble, toward the spawn line. */
        val UP: Vec2 = Vec2(0f, -1f)

        /** Screen-down `(0, 1)`: direction of gravity. */
        val DOWN: Vec2 = Vec2(0f, 1f)

        /** Vector of [length] pointing at [radians] (0 = +X, positive = clockwise on screen). */
        @Suppress("NOTHING_TO_INLINE")
        inline fun fromAngle(radians: Float, length: Float): Vec2 =
            Vec2(cos(radians) * length, sin(radians) * length)
    }
}
