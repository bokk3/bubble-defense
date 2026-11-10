package com.arcade.bubbledefense.engine.math

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Caller-owned, reusable contact record written by [CollisionMath] narrow-phase queries.
 *
 * Allocate ONE instance per system at init and reuse it every query; this is how the collision API
 * returns multiple values without allocating. All [Vec2] properties are stored as primitive `long`
 * fields (no boxing).
 *
 * Semantics after a successful query (`hit == true`):
 * - [normal]: unit vector pointing **from the shield toward the obstacle center**.
 * - [penetration]: overlap depth `R − dist ≥ 0` in wu (`R = r + r_shield`); `0` at exact tangency.
 * - [point]: closest point on the segment's core line (wu).
 * - [segmentT]: parameter of [point] along `A→B` in `[0, 1]` (`0` = cap A, `1` = cap B).
 * - [toi]: time of impact as a fraction of the swept displacement in `[0, 1]` (`0` for discrete queries).
 */
class Contact {
    var hit: Boolean = false
    var normal: Vec2 = Vec2.ZERO
    var penetration: Float = 0f
    var point: Vec2 = Vec2.ZERO
    var segmentT: Float = 0f
    var toi: Float = 0f

    /** Resets every field to its "no contact" value. */
    fun clear() {
        hit = false
        normal = Vec2.ZERO
        penetration = 0f
        point = Vec2.ZERO
        segmentT = 0f
        toi = 0f
    }
}

/**
 * Pure, stateless, allocation-free collision math for circle (obstacle) vs capsule (shield segment).
 *
 * Every equation here is specified in `docs/PHYSICS_SPEC.md` §2–§6; section numbers are referenced in
 * each KDoc. Preconditions for all functions: inputs are finite; radii and restitution are `≥ 0`.
 */
object CollisionMath {

    /** Distance below which the contact direction is considered undefined (wu). */
    const val COLLISION_EPSILON: Float = 1e-6f

    /** Squared segment length at or below which a segment is treated as a single point (wu²). */
    const val DEGENERATE_SEGMENT_LENGTH_SQ: Float = 1e-8f

    /** Squared displacement at or below which a sweep is treated as stationary (wu²). */
    const val MIN_SWEEP_LENGTH_SQ: Float = 1e-12f

    /** Sentinel returned by sweep helpers when there is no impact within `[0, 1]`. */
    const val NO_HIT: Float = -1f

    // ---------------------------------------------------------------------------------------------
    // §2 Closest point on segment
    // ---------------------------------------------------------------------------------------------

    /**
     * Parameter `t ∈ [0, 1]` of the point on segment `a→b` closest to [p] (PHYSICS_SPEC §2).
     * Returns `0` for a degenerate segment (`‖b − a‖² ≤ 1e-8`).
     */
    fun closestPointParameter(p: Vec2, a: Vec2, b: Vec2): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val lenSq = abx * abx + aby * aby
        if (lenSq <= DEGENERATE_SEGMENT_LENGTH_SQ) return 0f
        val t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq
        return clamp01(t)
    }

    /** Closest point to [p] on segment `a→b`: `a + t·(b − a)` (PHYSICS_SPEC §2). */
    fun closestPointOnSegment(p: Vec2, a: Vec2, b: Vec2): Vec2 {
        val t = closestPointParameter(p, a, b)
        return Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    /**
     * Unit geometric normal of segment `a→b`: `perp(b − a) / ‖b − a‖` (clockwise on screen).
     * Returns [Vec2.UP] for a degenerate segment.
     */
    fun segmentNormal(a: Vec2, b: Vec2): Vec2 {
        val ab = b - a
        val lenSq = ab.lengthSquared()
        if (lenSq <= DEGENERATE_SEGMENT_LENGTH_SQ) return Vec2.UP
        val inv = 1f / sqrt(lenSq)
        return Vec2(-ab.y * inv, ab.x * inv)
    }

    // ---------------------------------------------------------------------------------------------
    // §3 Discrete circle vs capsule
    // ---------------------------------------------------------------------------------------------

    /**
     * Discrete circle–capsule intersection (PHYSICS_SPEC §3).
     *
     * @param center obstacle center (wu)
     * @param radius obstacle collision radius `r` (wu)
     * @param velocity obstacle velocity (wu/s); only used to orient the fallback normal (§3.1)
     * @param a segment start (wu)
     * @param b segment end (wu)
     * @param shieldRadius shield half-thickness `r_s` (wu)
     * @param out receives the contact; cleared when there is no hit
     * @return `true` iff `dist ≤ r + r_s` (exact tangency counts as a hit with zero penetration)
     */
    fun circleVsSegment(
        center: Vec2,
        radius: Float,
        velocity: Vec2,
        a: Vec2,
        b: Vec2,
        shieldRadius: Float,
        out: Contact,
    ): Boolean {
        val ab = b - a
        val lenSq = ab.lengthSquared()
        val t = if (lenSq <= DEGENERATE_SEGMENT_LENGTH_SQ) 0f else clamp01(((center - a) dot ab) / lenSq)
        val closest = Vec2(a.x + ab.x * t, a.y + ab.y * t)
        val d = center - closest
        val distSq = d.lengthSquared()
        val reach = radius + shieldRadius
        if (distSq > reach * reach) {
            out.clear()
            return false
        }
        val dist = sqrt(distSq)
        out.hit = true
        out.normal = if (dist > COLLISION_EPSILON) d * (1f / dist) else fallbackNormal(ab, lenSq, velocity)
        out.penetration = reach - dist
        out.point = closest
        out.segmentT = t
        out.toi = 0f
        return true
    }

    /**
     * Deterministic contact normal used when the obstacle center lies exactly on the segment core
     * (PHYSICS_SPEC §3.1). [ab] is `b − a`, [lenSq] its squared length.
     */
    fun fallbackNormal(ab: Vec2, lenSq: Float, velocity: Vec2): Vec2 {
        if (lenSq > DEGENERATE_SEGMENT_LENGTH_SQ) {
            val inv = 1f / sqrt(lenSq)
            val n = Vec2(-ab.y * inv, ab.x * inv)
            val vn = n dot velocity
            return if (vn > 0f || (vn == 0f && n.y > 0f)) -n else n
        }
        val speedSq = velocity.lengthSquared()
        if (speedSq > VEC2_EPSILON_SQ) {
            val inv = -1f / sqrt(speedSq)
            return Vec2(velocity.x * inv, velocity.y * inv)
        }
        return Vec2.UP
    }

    // ---------------------------------------------------------------------------------------------
    // §4 Positional correction
    // ---------------------------------------------------------------------------------------------

    /**
     * Anti-sinking projection (PHYSICS_SPEC §4): `C_new = C + n̂ · (R − dist)`.
     * Leaves the obstacle exactly touching the capsule surface.
     */
    fun separate(center: Vec2, contact: Contact): Vec2 {
        val n = contact.normal
        val p = contact.penetration
        return Vec2(center.x + n.x * p, center.y + n.y * p)
    }

    /** Same as [separate] but with the push-out distance clamped to [maxPushOut] wu. */
    fun separateClamped(center: Vec2, contact: Contact, maxPushOut: Float): Vec2 {
        val n = contact.normal
        val p = if (contact.penetration > maxPushOut) maxPushOut else contact.penetration
        return Vec2(center.x + n.x * p, center.y + n.y * p)
    }

    // ---------------------------------------------------------------------------------------------
    // §5 Impulse response with active swipe velocity
    // ---------------------------------------------------------------------------------------------

    /**
     * Linear parry decay `w(age) = clamp(1 − age / window, 0, 1)` (PHYSICS_SPEC §5.1).
     * Returns `0` when [window] `≤ 0`.
     */
    fun parryDecay(age: Float, window: Float): Float {
        if (window <= 0f) return 0f
        return clamp01(1f - age / window)
    }

    /**
     * Effective shield surface velocity `V_surf = V_swipe · w(age) · gain` (PHYSICS_SPEC §5.1).
     */
    fun effectiveSwipeVelocity(swipeVelocity: Vec2, age: Float, window: Float, gain: Float): Vec2 {
        val k = parryDecay(age, window) * gain
        return Vec2(swipeVelocity.x * k, swipeVelocity.y * k)
    }

    /**
     * Restitution impulse against a kinematic shield (PHYSICS_SPEC §5.2):
     *
     * `V_rel = V − V_surf`, `v_n = V_rel · n̂`;
     * if `v_n < 0`: `V_new = V − (1 + e) · v_n · n̂`, otherwise `V` is returned unchanged.
     *
     * @param velocity obstacle velocity `V` (wu/s)
     * @param surfaceVelocity effective shield velocity `V_surf` (wu/s), see [effectiveSwipeVelocity]
     * @param normal unit contact normal `n̂` pointing toward the obstacle
     * @param restitution coefficient of restitution `e ∈ [0, 1]`
     */
    fun resolveVelocity(velocity: Vec2, surfaceVelocity: Vec2, normal: Vec2, restitution: Float): Vec2 {
        val vn = (velocity.x - surfaceVelocity.x) * normal.x + (velocity.y - surfaceVelocity.y) * normal.y
        if (vn >= 0f) return velocity
        val k = (1f + restitution) * vn
        return Vec2(velocity.x - normal.x * k, velocity.y - normal.y * k)
    }

    /**
     * Normal impulse magnitude `J = −(1 + e) · m · v_n` for approaching contacts, else `0`
     * (PHYSICS_SPEC §5.3). Units: mass · wu/s.
     */
    fun normalImpulse(
        mass: Float,
        velocity: Vec2,
        surfaceVelocity: Vec2,
        normal: Vec2,
        restitution: Float,
    ): Float {
        val vn = (velocity.x - surfaceVelocity.x) * normal.x + (velocity.y - surfaceVelocity.y) * normal.y
        if (vn >= 0f) return 0f
        return -(1f + restitution) * mass * vn
    }

    /**
     * Parry classification (PHYSICS_SPEC §5.4): the decayed surface velocity drives into the obstacle
     * along the contact normal at least [minNormalSpeed] wu/s.
     */
    fun isParry(surfaceVelocity: Vec2, normal: Vec2, minNormalSpeed: Float): Boolean =
        (surfaceVelocity dot normal) >= minNormalSpeed

    // ---------------------------------------------------------------------------------------------
    // §6.3 Swept circle vs capsule (CCD)
    // ---------------------------------------------------------------------------------------------

    /**
     * Earliest `s ∈ [0, 1]` at which the moving point `start + s·displacement` enters the circle
     * (`circleCenter`, [circleRadius]); returns `0` if it starts inside, [NO_HIT] otherwise.
     */
    fun rayVsCircle(start: Vec2, displacement: Vec2, circleCenter: Vec2, circleRadius: Float): Float {
        val mx = start.x - circleCenter.x
        val my = start.y - circleCenter.y
        val c = mx * mx + my * my - circleRadius * circleRadius
        if (c <= 0f) return 0f
        val a = displacement.lengthSquared()
        if (a <= MIN_SWEEP_LENGTH_SQ) return NO_HIT
        val b = mx * displacement.x + my * displacement.y
        if (b >= 0f) return NO_HIT
        val disc = b * b - a * c
        if (disc < 0f) return NO_HIT
        val s = (-b - sqrt(disc)) / a
        return if (s >= 0f && s <= 1f) s else NO_HIT
    }

    /**
     * Continuous collision of a circle moving by [displacement] during one sub-step against a shield
     * capsule (PHYSICS_SPEC §6.3). Prevents tunneling of fast, thin obstacles (Needles, Shards).
     *
     * On hit, [out] holds the contact evaluated at the impact position `start + toi·displacement`,
     * with `out.toi` the impact fraction. If the circle already overlaps at `start`, the discrete
     * contact is reported with `toi = 0` (including its non-zero penetration).
     *
     * @return `true` iff the capsule is touched for some `s ∈ [0, 1]`
     */
    fun sweptCircleVsSegment(
        start: Vec2,
        displacement: Vec2,
        radius: Float,
        a: Vec2,
        b: Vec2,
        shieldRadius: Float,
        out: Contact,
    ): Boolean {
        if (circleVsSegment(start, radius, displacement, a, b, shieldRadius, out)) {
            out.toi = 0f
            return true
        }
        if (displacement.lengthSquared() <= MIN_SWEEP_LENGTH_SQ) return false

        val reach = radius + shieldRadius
        val ab = b - a
        val lenSq = ab.lengthSquared()
        val degenerate = lenSq <= DEGENERATE_SEGMENT_LENGTH_SQ
        var toi = NO_HIT

        if (!degenerate) {
            val len = sqrt(lenSq)
            val axis = ab * (1f / len)
            val sideNormal = axis.perpendicular()
            val rel = start - a
            val h0 = rel dot sideNormal
            val hd = displacement dot sideNormal
            if (abs(h0) > reach && h0 * hd < 0f) {
                val target = if (h0 > 0f) reach else -reach
                val s = (target - h0) / hd
                if (s >= 0f && s <= 1f) {
                    val along = (rel + displacement * s) dot axis
                    if (along >= 0f && along <= len) toi = s
                }
            }
        }

        if (toi == NO_HIT) {
            val sA = rayVsCircle(start, displacement, a, reach)
            val sB = if (degenerate) NO_HIT else rayVsCircle(start, displacement, b, reach)
            toi = earliest(sA, sB)
        }
        if (toi == NO_HIT) return false

        val impact = start + displacement * toi
        val t = if (degenerate) 0f else clamp01(((impact - a) dot ab) / lenSq)
        val closest = Vec2(a.x + ab.x * t, a.y + ab.y * t)
        val d = impact - closest
        val dist = d.length()
        out.hit = true
        out.normal = if (dist > COLLISION_EPSILON) d * (1f / dist) else fallbackNormal(ab, lenSq, displacement)
        out.penetration = if (reach > dist) reach - dist else 0f
        out.point = closest
        out.segmentT = t
        out.toi = toi
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @Suppress("NOTHING_TO_INLINE")
    private inline fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    @Suppress("NOTHING_TO_INLINE")
    private inline fun earliest(s0: Float, s1: Float): Float = when {
        s0 == NO_HIT -> s1
        s1 == NO_HIT -> s0
        s0 <= s1 -> s0
        else -> s1
    }
}
