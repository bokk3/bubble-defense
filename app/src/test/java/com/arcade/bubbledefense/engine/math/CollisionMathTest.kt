package com.arcade.bubbledefense.engine.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Unit tests for [Vec2] and [CollisionMath]. Every expectation is derived by hand from the
 * equations in `docs/PHYSICS_SPEC.md`; section numbers are noted per group.
 *
 * World convention: +Y is DOWN, so a falling obstacle has positive vy and "up" is (0, -1).
 */
class CollisionMathTest {

    private val contact = Contact()

    // Archetype numbers from PHYSICS_SPEC §7 (GameConfig does not exist until Phase 2).
    private val shieldRadius = 6f
    private val drifterRadius = 28f
    private val needleRadius = 7f
    private val eDrifter = 0.75f
    private val eCrusher = 0.30f

    // Horizontal reference shield used by most tests: A(0,0) -> B(100,0).
    private val a = Vec2(0f, 0f)
    private val b = Vec2(100f, 0f)

    // =============================================================================================
    // Vec2 (PHYSICS_SPEC §1)
    // =============================================================================================

    @Test
    fun vec2_packUnpack_roundTripsExactRawBits() {
        val samples = floatArrayOf(
            0f, -0f, 1.5f, -2.25f, 123456.78f, -98765.43f,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, 1e-40f /* subnormal */,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN,
        )
        for (i in samples.indices) {
            for (j in samples.indices) {
                val v = Vec2(samples[i], samples[j])
                assertEquals(samples[i].toRawBits(), v.x.toRawBits())
                assertEquals(samples[j].toRawBits(), v.y.toRawBits())
            }
        }
    }

    @Test
    fun vec2_negativeY_doesNotSignExtendIntoX() {
        val v = Vec2(1f, -1f)
        assertEquals(1f, v.x, 0f)
        assertEquals(-1f, v.y, 0f)
        assertEquals(packFloats(1f, -1f), v.packed)
        assertEquals(0x3F800000_BF800000L, v.packed)
    }

    @Test
    fun vec2_zeroConstant_isPackedZero_andSignedZeroDiffersByBits() {
        assertEquals(0L, Vec2.ZERO.packed)
        assertEquals(Vec2.ZERO, Vec2(0f, 0f))
        assertNotEquals(Vec2.ZERO, Vec2(-0f, 0f))
        assertTrue(Vec2.ZERO.approxEquals(Vec2(-0f, 0f), 0f))
    }

    @Test
    fun vec2_arithmeticOperators() {
        val p = Vec2(3f, -4f)
        val q = Vec2(-1f, 2f)
        assertVec(Vec2(2f, -2f), p + q)
        assertVec(Vec2(4f, -6f), p - q)
        assertVec(Vec2(6f, -8f), p * 2f)
        assertVec(Vec2(6f, -8f), 2f * p)
        assertVec(Vec2(1.5f, -2f), p / 2f)
        assertVec(Vec2(-3f, 4f), -p)
        val (x, y) = p
        assertEquals(3f, x, 0f)
        assertEquals(-4f, y, 0f)
        assertVec(Vec2(7f, -4f), p.withX(7f))
        assertVec(Vec2(3f, 9f), p.withY(9f))
        assertVec(Vec2(1f, -1f), p.lerp(q, 0.5f))
    }

    @Test
    fun vec2_dotAndCross_signConvention() {
        assertEquals(1f, Vec2.UNIT_X cross Vec2.UNIT_Y, 0f)
        assertEquals(-1f, Vec2.UNIT_Y cross Vec2.UNIT_X, 0f)
        assertEquals(0f, Vec2.UNIT_X dot Vec2.UNIT_Y, 0f)
        assertEquals(11f, Vec2(3f, 4f) dot Vec2(1f, 2f), 0f)
        assertEquals(2f, Vec2(3f, 4f) cross Vec2(1f, 2f), 0f)
        assertEquals(0f, Vec2(2f, 4f) cross Vec2(1f, 2f), 0f) // parallel
    }

    @Test
    fun vec2_lengthAndDistance() {
        val v = Vec2(3f, 4f)
        assertEquals(25f, v.lengthSquared(), 0f)
        assertEquals(5f, v.length(), 0f)
        assertEquals(5f, Vec2(1f, 1f).distanceTo(Vec2(4f, 5f)), EPS)
        assertEquals(25f, Vec2(1f, 1f).distanceSquaredTo(Vec2(4f, 5f)), EPS)
    }

    @Test
    fun vec2_normalized_isUnitLength_andZeroSafe() {
        val n = Vec2(3f, -4f).normalized()
        assertVec(Vec2(0.6f, -0.8f), n)
        assertEquals(1f, n.length(), EPS)
        assertEquals(Vec2.ZERO, Vec2.ZERO.normalized())
        assertEquals(Vec2.ZERO, Vec2(1e-7f, -1e-7f).normalized())
        assertTrue(Vec2(1e-5f, 0f).normalized().approxEquals(Vec2.UNIT_X, EPS))
    }

    @Test
    fun vec2_perpendicular_isOrthogonal_andClockwiseOnScreen() {
        val v = Vec2(5f, 2f)
        val p = v.perpendicular()
        assertEquals(0f, v dot p, 0f)
        assertEquals(v.length(), p.length(), EPS)
        // +X rotated to +Y: on a y-down screen that is a clockwise quarter turn.
        assertVec(Vec2.DOWN, Vec2.UNIT_X.perpendicular())
    }

    @Test
    fun vec2_reflect_mirrorsAcrossNormal_andPreservesLength() {
        val v = Vec2(300f, 400f)
        val r = v.reflect(Vec2.UP)
        assertVec(Vec2(300f, -400f), r)
        assertEquals(v.length(), r.length(), EPS)
        // 45° slope: a vertical drop leaves horizontally.
        val slopeNormal = Vec2(1f, -1f).normalized()
        assertVec(Vec2(500f, 0f), Vec2(0f, 500f).reflect(slopeNormal), 1e-3f)
    }

    @Test
    fun vec2_clampLength() {
        assertVec(Vec2(6f, 8f), Vec2(6f, 8f).clampLength(20f))
        assertVec(Vec2(3f, 4f), Vec2(6f, 8f).clampLength(5f))
        assertEquals(Vec2.ZERO, Vec2.ZERO.clampLength(1f))
    }

    @Test
    fun vec2_rotated_andFromAngle() {
        assertVec(Vec2.DOWN, Vec2.UNIT_X.rotated((PI / 2).toFloat()))
        assertVec(Vec2(-1f, 0f), Vec2.UNIT_X.rotated(PI.toFloat()))
        assertVec(Vec2(0f, -2f), Vec2.fromAngle((-PI / 2).toFloat(), 2f))
        val v = Vec2(3f, 7f)
        assertEquals(v.length(), v.rotated(1.234f).length(), EPS)
    }

    @Test
    fun vec2_isFinite() {
        assertTrue(Vec2(1f, 2f).isFinite())
        assertFalse(Vec2(Float.NaN, 2f).isFinite())
        assertFalse(Vec2(1f, Float.POSITIVE_INFINITY).isFinite())
    }

    // =============================================================================================
    // Closest point on segment (PHYSICS_SPEC §2)
    // =============================================================================================

    @Test
    fun closestPoint_interiorProjection() {
        val p = Vec2(30f, -40f)
        assertEquals(0.3f, CollisionMath.closestPointParameter(p, a, b), EPS)
        assertVec(Vec2(30f, 0f), CollisionMath.closestPointOnSegment(p, a, b))
    }

    @Test
    fun closestPoint_clampsToCapA_beforeStart() {
        val p = Vec2(-50f, 10f)
        assertEquals(0f, CollisionMath.closestPointParameter(p, a, b), 0f)
        assertVec(a, CollisionMath.closestPointOnSegment(p, a, b))
    }

    @Test
    fun closestPoint_clampsToCapB_beyondEnd() {
        val p = Vec2(180f, -5f)
        assertEquals(1f, CollisionMath.closestPointParameter(p, a, b), 0f)
        assertVec(b, CollisionMath.closestPointOnSegment(p, a, b))
    }

    @Test
    fun closestPoint_obliqueSegment() {
        // A(0,0) -> B(100,100), P(100,0): projection t = (100*100)/(2*100^2) = 0.5 -> (50,50).
        val bb = Vec2(100f, 100f)
        assertEquals(0.5f, CollisionMath.closestPointParameter(Vec2(100f, 0f), a, bb), EPS)
        assertVec(Vec2(50f, 50f), CollisionMath.closestPointOnSegment(Vec2(100f, 0f), a, bb))
    }

    @Test
    fun closestPoint_degenerateSegment_returnsA() {
        val p = Vec2(10f, 10f)
        assertEquals(0f, CollisionMath.closestPointParameter(Vec2(50f, 50f), p, p), 0f)
        assertVec(p, CollisionMath.closestPointOnSegment(Vec2(50f, 50f), p, p))
    }

    @Test
    fun segmentNormal_isUnitPerpendicular() {
        assertVec(Vec2.DOWN, CollisionMath.segmentNormal(a, b))
        assertVec(Vec2.UP, CollisionMath.segmentNormal(b, a))
        assertVec(Vec2.UP, CollisionMath.segmentNormal(a, a))
    }

    // =============================================================================================
    // Circle vs segment (PHYSICS_SPEC §3)
    // =============================================================================================

    @Test
    fun circleVsSegment_clearMiss_clearsContact() {
        contact.hit = true // stale data must be cleared
        val hit = CollisionMath.circleVsSegment(Vec2(50f, -30f), 10f, FALLING, a, b, shieldRadius, contact)
        assertFalse(hit)
        assertFalse(contact.hit)
        assertEquals(0f, contact.penetration, 0f)
    }

    @Test
    fun circleVsSegment_grazingTangent_isHitWithZeroPenetration() {
        // reach = 10 + 6 = 16; center exactly 16 above the body.
        val hit = CollisionMath.circleVsSegment(Vec2(50f, -16f), 10f, FALLING, a, b, shieldRadius, contact)
        assertTrue(hit)
        assertEquals(0f, contact.penetration, 0f)
        assertVec(Vec2.UP, contact.normal)
        assertEquals(0.5f, contact.segmentT, EPS)
    }

    @Test
    fun circleVsSegment_justOutsideGrazing_misses() {
        val hit = CollisionMath.circleVsSegment(Vec2(50f, -16.01f), 10f, FALLING, a, b, shieldRadius, contact)
        assertFalse(hit)
    }

    @Test
    fun circleVsSegment_bodyHit_normalPerpendicularToSegment() {
        val hit = CollisionMath.circleVsSegment(Vec2(50f, -10f), 10f, FALLING, a, b, shieldRadius, contact)
        assertTrue(hit)
        assertVec(Vec2.UP, contact.normal)
        assertEquals(6f, contact.penetration, EPS)
        assertVec(Vec2(50f, 0f), contact.point)
        assertEquals(0.5f, contact.segmentT, EPS)
        assertEquals(0f, contact.toi, 0f)
    }

    @Test
    fun circleVsSegment_hitFromBelow_isTwoSided() {
        val hit = CollisionMath.circleVsSegment(Vec2(50f, 10f), 10f, RISING, a, b, shieldRadius, contact)
        assertTrue(hit)
        assertVec(Vec2.DOWN, contact.normal)
        assertEquals(6f, contact.penetration, EPS)
    }

    @Test
    fun circleVsSegment_endpointCapB_radialNormal() {
        // Closest point is B(100,0); d = (10,-10), dist = 14.1421 <= 16.
        val hit = CollisionMath.circleVsSegment(Vec2(110f, -10f), 10f, FALLING, a, b, shieldRadius, contact)
        assertTrue(hit)
        val inv = 1f / sqrt(2f)
        assertVec(Vec2(inv, -inv), contact.normal)
        assertEquals(16f - sqrt(200f), contact.penetration, EPS)
        assertEquals(1f, contact.segmentT, 0f)
        assertVec(b, contact.point)
    }

    @Test
    fun circleVsSegment_endpointCapA_onAxisExtension() {
        val hit = CollisionMath.circleVsSegment(Vec2(-9f, 0f), 10f, Vec2(300f, 0f), a, b, shieldRadius, contact)
        assertTrue(hit)
        assertVec(Vec2(-1f, 0f), contact.normal)
        assertEquals(7f, contact.penetration, EPS)
        assertEquals(0f, contact.segmentT, 0f)
    }

    @Test
    fun circleVsSegment_capGrazing_atExactReach_isHit() {
        // radius 14 + shield 6 = reach 20; center exactly 20 wu from cap B along a 3-4-5 diagonal,
        // d = (12, -16): every value is exactly representable, so dist^2 == reach^2 bit-exactly.
        val hit = CollisionMath.circleVsSegment(Vec2(112f, -16f), 14f, FALLING, a, b, shieldRadius, contact)
        assertTrue(hit)
        assertEquals(0f, contact.penetration, 0f)
        assertVec(Vec2(0.6f, -0.8f), contact.normal)
        assertFalse(CollisionMath.circleVsSegment(Vec2(112f, -16.01f), 14f, FALLING, a, b, shieldRadius, contact))
    }

    @Test
    fun circleVsSegment_centerOnSegment_fallbackOpposesVelocity() {
        val center = Vec2(50f, 0f)
        assertTrue(CollisionMath.circleVsSegment(center, 10f, FALLING, a, b, shieldRadius, contact))
        assertVec(Vec2.UP, contact.normal)
        assertEquals(16f, contact.penetration, EPS)

        assertTrue(CollisionMath.circleVsSegment(center, 10f, RISING, a, b, shieldRadius, contact))
        assertVec(Vec2.DOWN, contact.normal)

        // Zero velocity: deterministic tie-break toward screen-up (away from the bubble).
        assertTrue(CollisionMath.circleVsSegment(center, 10f, Vec2.ZERO, a, b, shieldRadius, contact))
        assertVec(Vec2.UP, contact.normal)
        // Same result regardless of segment winding.
        assertTrue(CollisionMath.circleVsSegment(center, 10f, Vec2.ZERO, b, a, shieldRadius, contact))
        assertVec(Vec2.UP, contact.normal)
    }

    @Test
    fun circleVsSegment_degenerateSegment_actsAsPoint() {
        val p = Vec2(0f, 0f)
        assertTrue(CollisionMath.circleVsSegment(Vec2(0f, -10f), 10f, FALLING, p, p, shieldRadius, contact))
        assertVec(Vec2.UP, contact.normal)
        assertEquals(6f, contact.penetration, EPS)

        // Center exactly on the point: fallback is -velocity direction.
        assertTrue(CollisionMath.circleVsSegment(p, 10f, Vec2(300f, 0f), p, p, shieldRadius, contact))
        assertVec(Vec2(-1f, 0f), contact.normal)

        // Center on point with zero velocity: UP.
        assertTrue(CollisionMath.circleVsSegment(p, 10f, Vec2.ZERO, p, p, shieldRadius, contact))
        assertVec(Vec2.UP, contact.normal)
    }

    // =============================================================================================
    // Positional correction (PHYSICS_SPEC §4)
    // =============================================================================================

    @Test
    fun positionalCorrection_restoresExactContactDistance_onObliqueSegment() {
        val sa = Vec2(100f, 200f)
        val sb = Vec2(400f, 350f)
        val center = Vec2(255f, 265f) // just above the middle of the segment, penetrating
        assertTrue(CollisionMath.circleVsSegment(center, drifterRadius, FALLING, sa, sb, shieldRadius, contact))
        assertTrue(contact.penetration > 0f)

        val corrected = CollisionMath.separate(center, contact)
        val closest = CollisionMath.closestPointOnSegment(corrected, sa, sb)
        assertEquals(drifterRadius + shieldRadius, corrected.distanceTo(closest), 1e-3f)

        // Re-test: still touching (tangent), zero penetration, same normal.
        val previousNormal = contact.normal
        assertTrue(CollisionMath.circleVsSegment(corrected, drifterRadius, FALLING, sa, sb, shieldRadius, contact))
        assertEquals(0f, contact.penetration, 1e-3f)
        assertVec(previousNormal, contact.normal, 1e-4f)
    }

    @Test
    fun positionalCorrection_clamped_limitsPushOut() {
        assertTrue(CollisionMath.circleVsSegment(Vec2(50f, 0f), 46f, FALLING, a, b, shieldRadius, contact))
        assertEquals(52f, contact.penetration, EPS) // Crusher fully embedded in a fresh line
        assertVec(Vec2(50f, -24f), CollisionMath.separateClamped(Vec2(50f, 0f), contact, 24f))
        assertVec(Vec2(50f, -52f), CollisionMath.separate(Vec2(50f, 0f), contact))
    }

    // =============================================================================================
    // Impulse reflection (PHYSICS_SPEC §5.2)
    // =============================================================================================

    @Test
    fun reflect_headOnDrop_drifterRestitution() {
        val v = CollisionMath.resolveVelocity(Vec2(0f, 400f), Vec2.ZERO, Vec2.UP, eDrifter)
        assertVec(Vec2(0f, -300f), v)
    }

    @Test
    fun reflect_headOnDrop_crusherLowRestitution() {
        val v = CollisionMath.resolveVelocity(Vec2(0f, 400f), Vec2.ZERO, Vec2.UP, eCrusher)
        assertVec(Vec2(0f, -120f), v)
    }

    @Test
    fun reflect_inelasticLimit_removesNormalComponentOnly() {
        val v = CollisionMath.resolveVelocity(Vec2(150f, 400f), Vec2.ZERO, Vec2.UP, 0f)
        assertVec(Vec2(150f, 0f), v)
    }

    @Test
    fun reflect_obliqueElastic_angleOfIncidenceEqualsAngleOfReflection() {
        val vIn = Vec2(300f, 400f)
        val vOut = CollisionMath.resolveVelocity(vIn, Vec2.ZERO, Vec2.UP, 1f)
        assertVec(Vec2(300f, -400f), vOut)
        val incidence = angleFromNormal(-vIn, Vec2.UP)
        val reflection = angleFromNormal(vOut, Vec2.UP)
        assertEquals(incidence, reflection, 1e-5f)
        assertEquals(vIn.length(), vOut.length(), EPS)
    }

    @Test
    fun reflect_elasticWithoutSwipe_equalsVec2Reflect_onSlopedShield() {
        val n = Vec2(1f, -1f).normalized()
        val vIn = Vec2(0f, 500f)
        val vOut = CollisionMath.resolveVelocity(vIn, Vec2.ZERO, n, 1f)
        assertVec(vIn.reflect(n), vOut, 1e-3f)
        assertVec(Vec2(500f, 0f), vOut, 1e-3f) // vertical drop deflected horizontally
    }

    @Test
    fun reflect_separatingContact_isUnchanged() {
        val v = Vec2(40f, -200f)
        assertEquals(v, CollisionMath.resolveVelocity(v, Vec2.ZERO, Vec2.UP, eDrifter))
        // Exactly tangential motion (v_n == 0) is also left untouched.
        val slide = Vec2(250f, 0f)
        assertEquals(slide, CollisionMath.resolveVelocity(slide, Vec2.ZERO, Vec2.UP, eDrifter))
    }

    @Test
    fun reflect_newtonRestitutionLaw_holdsForArbitraryContact() {
        val n = Vec2(-0.3f, -1f).normalized()
        val vIn = Vec2(120f, 700f)
        val surf = Vec2(-50f, -300f)
        val vOut = CollisionMath.resolveVelocity(vIn, surf, n, eDrifter)
        val vnBefore = (vIn - surf) dot n
        val vnAfter = (vOut - surf) dot n
        assertEquals(-eDrifter * vnBefore, vnAfter, 1e-2f)
        // Tangential component unchanged.
        val tangent = n.perpendicular()
        assertEquals(vIn dot tangent, vOut dot tangent, 1e-2f)
    }

    // =============================================================================================
    // Parry (PHYSICS_SPEC §5.1, §5.4)
    // =============================================================================================

    @Test
    fun parry_swipeIntoObstacle_addsNormalImpulse() {
        val passive = CollisionMath.resolveVelocity(Vec2(0f, 400f), Vec2.ZERO, Vec2.UP, eDrifter)
        val parried = CollisionMath.resolveVelocity(Vec2(0f, 400f), Vec2(0f, -600f), Vec2.UP, eDrifter)
        assertVec(Vec2(0f, -300f), passive)
        assertVec(Vec2(0f, -1350f), parried)
        assertEquals(4.5f, parried.length() / passive.length(), EPS)
    }

    @Test
    fun parry_preservesTangentialVelocity_andSteepensExitAngle() {
        val vIn = Vec2(300f, 400f)
        val passive = CollisionMath.resolveVelocity(vIn, Vec2.ZERO, Vec2.UP, eDrifter)
        val parried = CollisionMath.resolveVelocity(vIn, Vec2(0f, -600f), Vec2.UP, eDrifter)
        assertVec(Vec2(300f, -300f), passive)
        assertVec(Vec2(300f, -1350f), parried)
        val passiveAngleDeg = Math.toDegrees(angleFromNormal(passive, Vec2.UP).toDouble())
        val parriedAngleDeg = Math.toDegrees(angleFromNormal(parried, Vec2.UP).toDouble())
        assertEquals(45.0, passiveAngleDeg, 1e-3)
        assertEquals(Math.toDegrees(atan2(300.0, 1350.0)), parriedAngleDeg, 1e-3) // ≈ 12.53°
        assertTrue(parriedAngleDeg < passiveAngleDeg)
    }

    @Test
    fun parry_tangentialSwipe_addsNoNormalBoost() {
        val v = CollisionMath.resolveVelocity(Vec2(0f, 400f), Vec2(800f, 0f), Vec2.UP, eDrifter)
        assertVec(Vec2(0f, -300f), v)
        assertFalse(CollisionMath.isParry(Vec2(800f, 0f), Vec2.UP, PARRY_MIN_NORMAL_SPEED))
    }

    @Test
    fun parry_leadingCapContact_alignsNormalWithSwipe() {
        // Stroke tip B(100,0) slashing in +X into an obstacle sitting just beyond the tip.
        assertTrue(CollisionMath.circleVsSegment(Vec2(120f, 0f), 20f, Vec2.ZERO, a, b, shieldRadius, contact))
        assertVec(Vec2.UNIT_X, contact.normal)
        val swipe = Vec2(900f, 0f)
        assertTrue(CollisionMath.isParry(swipe, contact.normal, PARRY_MIN_NORMAL_SPEED))
        val v = CollisionMath.resolveVelocity(Vec2(0f, 300f), swipe, contact.normal, eDrifter)
        assertVec(Vec2(1575f, 300f), v) // (1 + 0.75) * 900 along +X, vertical motion untouched
    }

    @Test
    fun parry_retreatingShieldFasterThanObstacle_noImpulse() {
        val v = Vec2(0f, 400f)
        assertEquals(v, CollisionMath.resolveVelocity(v, Vec2(0f, 500f), Vec2.UP, eDrifter))
        assertEquals(0f, CollisionMath.normalImpulse(1f, v, Vec2(0f, 500f), Vec2.UP, eDrifter), 0f)
    }

    @Test
    fun parry_shieldSlamsRestingObstacle_launchesAtTwiceSurfaceSpeedWhenElastic() {
        val v = CollisionMath.resolveVelocity(Vec2.ZERO, Vec2(0f, -500f), Vec2.UP, 1f)
        assertVec(Vec2(0f, -1000f), v)
    }

    @Test
    fun parryDecay_isLinearOverWindow() {
        assertEquals(1f, CollisionMath.parryDecay(0f, PARRY_WINDOW), 0f)
        assertEquals(0.5f, CollisionMath.parryDecay(0.075f, PARRY_WINDOW), EPS)
        assertEquals(0f, CollisionMath.parryDecay(0.150f, PARRY_WINDOW), EPS)
        assertEquals(0f, CollisionMath.parryDecay(0.300f, PARRY_WINDOW), 0f)
        assertEquals(1f, CollisionMath.parryDecay(-0.01f, PARRY_WINDOW), 0f)
        assertEquals(0f, CollisionMath.parryDecay(0f, 0f), 0f)
    }

    @Test
    fun effectiveSwipeVelocity_scalesByDecayAndGain() {
        val swipe = Vec2(0f, -600f)
        assertVec(swipe, CollisionMath.effectiveSwipeVelocity(swipe, 0f, PARRY_WINDOW, 1f))
        assertVec(Vec2(0f, -300f), CollisionMath.effectiveSwipeVelocity(swipe, 0.075f, PARRY_WINDOW, 1f))
        assertVec(Vec2(0f, -450f), CollisionMath.effectiveSwipeVelocity(swipe, 0.075f, PARRY_WINDOW, 1.5f))
        assertVec(Vec2.ZERO, CollisionMath.effectiveSwipeVelocity(swipe, 0.2f, PARRY_WINDOW, 1f))
    }

    @Test
    fun isParry_thresholdIsInclusive_andWindowGated() {
        val n = Vec2.UP
        assertTrue(CollisionMath.isParry(Vec2(0f, -250f), n, PARRY_MIN_NORMAL_SPEED))
        assertFalse(CollisionMath.isParry(Vec2(0f, -249f), n, PARRY_MIN_NORMAL_SPEED))
        assertFalse(CollisionMath.isParry(Vec2(0f, 600f), n, PARRY_MIN_NORMAL_SPEED))
        // A 600 wu/s swipe is a parry at 50 ms (w = 2/3 -> 400) but not at 100 ms (w = 1/3 -> 200).
        val swipe = Vec2(0f, -600f)
        val at50 = CollisionMath.effectiveSwipeVelocity(swipe, 0.050f, PARRY_WINDOW, 1f)
        val at100 = CollisionMath.effectiveSwipeVelocity(swipe, 0.100f, PARRY_WINDOW, 1f)
        assertTrue(CollisionMath.isParry(at50, n, PARRY_MIN_NORMAL_SPEED))
        assertFalse(CollisionMath.isParry(at100, n, PARRY_MIN_NORMAL_SPEED))
    }

    @Test
    fun normalImpulse_magnitude() {
        // Crusher: J = (1 + 0.3) * 4 * 250 = 1300.
        assertEquals(1300f, CollisionMath.normalImpulse(4f, Vec2(0f, 250f), Vec2.ZERO, Vec2.UP, eCrusher), EPS)
        // Drifter at 350 wu/s (PHYSICS_SPEC §7 worked example): 612.5.
        assertEquals(612.5f, CollisionMath.normalImpulse(1f, Vec2(0f, 350f), Vec2.ZERO, Vec2.UP, eDrifter), 1e-3f)
        // Separating: zero.
        assertEquals(0f, CollisionMath.normalImpulse(1f, Vec2(0f, -350f), Vec2.ZERO, Vec2.UP, eDrifter), 0f)
    }

    // =============================================================================================
    // Swept CCD & sub-stepping (PHYSICS_SPEC §6)
    // =============================================================================================

    @Test
    fun swept_needleThatTunnelsDiscretely_isCaught_withExactToi() {
        val sa = Vec2(400f, 500f)
        val sb = Vec2(600f, 500f)
        val start = Vec2(500f, 450f)
        val displacement = Vec2(0f, 120f) // pathological: 14 400 wu/s at 120 Hz
        // Discrete test at the end position misses: the needle tunneled.
        assertFalse(
            CollisionMath.circleVsSegment(start + displacement, needleRadius, Vec2(0f, 14400f), sa, sb, shieldRadius, contact),
        )
        assertTrue(CollisionMath.sweptCircleVsSegment(start, displacement, needleRadius, sa, sb, shieldRadius, contact))
        // Side face at y = 500 - 13 = 487 -> s = 37 / 120.
        assertEquals(37f / 120f, contact.toi, EPS)
        assertVec(Vec2.UP, contact.normal)
        assertEquals(0f, contact.penetration, 1e-3f)
        assertVec(Vec2(500f, 500f), contact.point, 1e-3f)
        assertEquals(0.5f, contact.segmentT, EPS)
    }

    @Test
    fun swept_endpointCapHit_matchesAnalyticToi() {
        // Moving straight down at x = 110, 10 wu beyond cap B(100,0); reach = 13.
        val start = Vec2(110f, -40f)
        val displacement = Vec2(0f, 80f)
        assertTrue(CollisionMath.sweptCircleVsSegment(start, displacement, needleRadius, a, b, shieldRadius, contact))
        val impactY = -sqrt(13f * 13f - 10f * 10f) // -sqrt(69)
        assertEquals((impactY - (-40f)) / 80f, contact.toi, EPS)
        assertVec(Vec2(10f / 13f, impactY / 13f), contact.normal, 1e-4f)
        assertEquals(1f, contact.segmentT, 0f)
    }

    @Test
    fun swept_alongSegmentAxis_hitsLeadingCap() {
        val start = Vec2(-50f, 0f)
        val displacement = Vec2(200f, 0f)
        assertTrue(CollisionMath.sweptCircleVsSegment(start, displacement, needleRadius, a, b, shieldRadius, contact))
        assertEquals((-13f + 50f) / 200f, contact.toi, EPS) // 0.185
        assertVec(Vec2(-1f, 0f), contact.normal)
        assertEquals(0f, contact.segmentT, 0f)
    }

    @Test
    fun swept_parallelMotionOutsideReach_misses() {
        val start = Vec2(-50f, -30f)
        assertFalse(CollisionMath.sweptCircleVsSegment(start, Vec2(200f, 0f), needleRadius, a, b, shieldRadius, contact))
        assertFalse(contact.hit)
    }

    @Test
    fun swept_passingBesideCap_misses() {
        // x = 120 is 20 wu beyond cap B, reach is 13.
        val start = Vec2(120f, -40f)
        assertFalse(CollisionMath.sweptCircleVsSegment(start, Vec2(0f, 80f), needleRadius, a, b, shieldRadius, contact))
    }

    @Test
    fun swept_alreadyOverlapping_reportsToiZero_withPenetration() {
        val start = Vec2(50f, -5f)
        assertTrue(CollisionMath.sweptCircleVsSegment(start, Vec2(0f, 50f), needleRadius, a, b, shieldRadius, contact))
        assertEquals(0f, contact.toi, 0f)
        assertEquals(8f, contact.penetration, EPS)
        assertVec(Vec2.UP, contact.normal)
    }

    @Test
    fun swept_movingAway_misses() {
        assertFalse(CollisionMath.sweptCircleVsSegment(Vec2(50f, -40f), Vec2(0f, -100f), needleRadius, a, b, shieldRadius, contact))
    }

    @Test
    fun swept_stopsShort_misses() {
        // Needs 27 wu to touch (from -40 to -13), only moves 26.
        assertFalse(CollisionMath.sweptCircleVsSegment(Vec2(50f, -40f), Vec2(0f, 26f), needleRadius, a, b, shieldRadius, contact))
    }

    @Test
    fun swept_zeroDisplacement_outsideReach_misses() {
        assertFalse(CollisionMath.sweptCircleVsSegment(Vec2(50f, -40f), Vec2.ZERO, needleRadius, a, b, shieldRadius, contact))
    }

    @Test
    fun swept_degenerateSegment_actsAsCircle() {
        val p = Vec2(0f, 0f)
        assertTrue(CollisionMath.sweptCircleVsSegment(Vec2(0f, -40f), Vec2(0f, 80f), needleRadius, p, p, shieldRadius, contact))
        assertEquals(27f / 80f, contact.toi, EPS)
        assertVec(Vec2.UP, contact.normal)
    }

    @Test
    fun swept_diagonalBodyHit_toiConsistentWithDiscreteContact() {
        val sa = Vec2(100f, 200f)
        val sb = Vec2(400f, 350f)
        val start = Vec2(200f, 100f)
        val displacement = Vec2(150f, 300f)
        assertTrue(CollisionMath.sweptCircleVsSegment(start, displacement, needleRadius, sa, sb, shieldRadius, contact))
        val impact = start + displacement * contact.toi
        val closest = CollisionMath.closestPointOnSegment(impact, sa, sb)
        assertEquals(needleRadius + shieldRadius, impact.distanceTo(closest), 1e-2f)
        // Slightly before the TOI there is no contact.
        val before = start + displacement * (contact.toi - 1e-3f)
        assertFalse(CollisionMath.circleVsSegment(before, needleRadius, displacement, sa, sb, shieldRadius, Contact()))
    }

    @Test
    fun discreteSubstepping_neverTunnels_atMaxNeedleSpeed() {
        // PHYSICS_SPEC §6.2: |V| * SUB_DT = 3600 / 480 = 7.5 wu < 2 * (7 + 6) = 26 wu.
        val subDt = 1f / 480f
        val maxSpeed = 3600f
        val sa = Vec2(400f, 500f)
        val sb = Vec2(412f, 500f) // shortest legal segment (MIN_SEGMENT_LENGTH = 12)
        val probe = Contact()
        for (angleStep in -6..6) {
            val angle = angleStep * (PI.toFloat() / 18f) // -60° .. +60° from straight down
            val dir = Vec2(sin(angle), cos(angle))
            val velocity = dir * maxSpeed
            for (k in 0..12) {
                val crossing = Vec2(400f + k, 500f)
                var position = crossing - dir * 150f
                var detected = false
                var steps = 0
                while (steps < 200 && !detected) {
                    position += velocity * subDt
                    detected = CollisionMath.circleVsSegment(position, needleRadius, velocity, sa, sb, shieldRadius, probe)
                    steps++
                }
                assertTrue("tunneled at angleStep=$angleStep k=$k", detected)
            }
        }
    }

    // =============================================================================================
    // Zero-allocation contract (.cursorrules §3.3)
    // =============================================================================================

    @Test
    fun zeroAllocation_allocationProbe_detectsBoxing() {
        if (!AllocationProbe.isAvailable) return
        val sink = arrayOfNulls<Any>(1024)
        AllocationProbe.warmUp()
        val before = AllocationProbe.allocatedBytes()
        for (i in 0 until 20_000) {
            sink[i and 1023] = boxVec(Vec2(i.toFloat(), 1f)) // Vec2 as Any -> boxed
        }
        val delta = AllocationProbe.allocatedBytes() - before
        assertTrue("probe failed to observe boxing (delta=$delta)", delta >= 20_000L * 16L)
    }

    @Test
    fun zeroAllocation_hotPathMath_allocatesNothing() {
        if (!AllocationProbe.isAvailable) return
        val probeContact = Contact()
        runHotLoop(probeContact, 5_000) // warm-up: class init, interpreter profiling
        AllocationProbe.warmUp()
        val before = AllocationProbe.allocatedBytes()
        val sink = runHotLoop(probeContact, ITERATIONS)
        val delta = AllocationProbe.allocatedBytes() - before
        assertTrue(sink.isFinite())
        // Any per-iteration allocation would cost >= 16 B * 200k = 3.2 MB. The tolerance only
        // absorbs the probe's own reflective call overhead.
        assertTrue("hot path allocated $delta bytes over $ITERATIONS iterations", delta < PROBE_TOLERANCE_BYTES)
    }

    /** Exercises every CollisionMath branch used by PhysicsSystem; returns a sink to defeat DCE. */
    private fun runHotLoop(c: Contact, iterations: Int): Float {
        var sink = 0f
        val sa = Vec2(400f, 500f)
        val sb = Vec2(600f, 500f)
        for (i in 0 until iterations) {
            val f = (i and 1023).toFloat()
            val center = Vec2(380f + f * 0.25f, 480f + (i and 15).toFloat())
            val velocity = Vec2(f - 512f, 400f + f)
            val swipe = CollisionMath.effectiveSwipeVelocity(Vec2(0f, -600f), (i and 255) * 0.001f, PARRY_WINDOW, 1f)
            if (CollisionMath.circleVsSegment(center, drifterRadius, velocity, sa, sb, shieldRadius, c)) {
                val p = CollisionMath.separate(center, c)
                val v = CollisionMath.resolveVelocity(velocity, swipe, c.normal, eDrifter)
                sink += p.y + v.y + CollisionMath.normalImpulse(1f, velocity, swipe, c.normal, eDrifter)
                if (CollisionMath.isParry(swipe, c.normal, PARRY_MIN_NORMAL_SPEED)) sink += 1f
            }
            val start = Vec2(350f + f * 0.3f, 380f)
            if (CollisionMath.sweptCircleVsSegment(start, Vec2(0f, 240f), needleRadius, sa, sb, shieldRadius, c)) {
                sink += c.toi + c.normal.x
            }
            sink += CollisionMath.closestPointOnSegment(center, sa, sb).x * 1e-6f
            sink += (center - sa).normalized().reflect(Vec2.UP).rotated(0.1f).clampLength(0.5f).length()
        }
        return sink
    }

    /** Deliberately boxes a Vec2 (non-inline generic upcast) for the positive-control test. */
    private fun boxVec(v: Vec2): Any = v

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private fun assertVec(expected: Vec2, actual: Vec2, epsilon: Float = EPS) {
        assertTrue("expected $expected but was $actual", expected.approxEquals(actual, epsilon))
    }

    /** Unsigned angle (rad) between vector [v] and unit normal [n]. */
    private fun angleFromNormal(v: Vec2, n: Vec2): Float {
        val cosA = (v dot n) / v.length()
        val sinA = abs(v cross n) / v.length()
        return atan2(sinA, cosA)
    }

    /**
     * Reads the per-thread allocated-bytes counter of HotSpot-family JVMs reflectively, because
     * `java.lang.management` is absent from `android.jar` at compile time (.cursorrules §6).
     */
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
        fun allocatedBytes(): Long = counter?.invoke(bean, Thread.currentThread().id) as Long

        /** Primes the reflective call path so its one-time setup cost is not measured. */
        fun warmUp() {
            for (i in 0 until 64) allocatedBytes()
        }
    }

    private companion object {
        const val EPS = 1e-4f
        const val PARRY_WINDOW = 0.150f
        const val PARRY_MIN_NORMAL_SPEED = 250f
        const val ITERATIONS = 200_000
        const val PROBE_TOLERANCE_BYTES = 64L * 1024L
        val FALLING = Vec2(0f, 400f)
        val RISING = Vec2(0f, -400f)
    }
}
