# PHYSICS SPEC — Bubble Defense

Owner: **[PHYS-AGENT]** · Implemented in: `engine/math/Vec2.kt`, `engine/math/CollisionMath.kt`
(Phase 1), `domain/systems/PhysicsSystem.kt` (Phase 3).

All quantities in world units (wu), seconds, radians. **+Y is down** (screen convention).
"Up" (away from the bubble, toward the spawn line) is `(0, -1)`.

---

## 1. Vec2 — Packed Value Type

```
packed: Long = (x.toRawBits() << 32) | (y.toRawBits() & 0xFFFF_FFFF)
x = Float.fromBits((packed ushr 32).toInt())
y = Float.fromBits(packed.toInt())
```

| Operation | Definition |
|---|---|
| `a + b`, `a - b` | component-wise |
| `a * s`, `s * a`, `a / s` | scalar scale |
| `a dot b` | $a_x b_x + a_y b_y$ |
| `a cross b` | $a_x b_y - a_y b_x$ (scalar z of the 3D cross product) |
| `length()` | $\sqrt{a \cdot a}$ |
| `normalized()` | $a / \Vert a \Vert$, or `ZERO` if $\Vert a \Vert^2 \le 10^{-12}$ |
| `perpendicular()` | $(-a_y, a_x)$ — +90° in math (y-up) convention = **clockwise on screen** (y-down) |
| `reflect(n)` | $a - 2(a \cdot \hat n)\hat n$, requires unit $\hat n$ |
| `rotated(θ)` | $(a_x\cos θ - a_y \sin θ,\; a_x \sin θ + a_y \cos θ)$ — positive θ is clockwise on screen |
| `clampLength(m)` | $a \cdot \min(1, m/\Vert a\Vert)$ |

**Equality caveat:** `==` compares raw bits. `Vec2(0f, 0f) != Vec2(-0f, 0f)` and identical NaN
payloads compare equal. Use `approxEquals(other, eps)` for numeric comparisons.

**Cross-product sign convention:** `UNIT_X cross UNIT_Y = +1`. On screen (y-down) a positive cross
means `b` is **clockwise** from `a`.

---

## 2. Closest Point on Segment

For point $\vec C$ and segment $\vec A \to \vec B$:

$$\vec{AB} = \vec B - \vec A, \qquad
t = \operatorname{clamp}\left(\frac{(\vec C - \vec A)\cdot\vec{AB}}{\Vert\vec{AB}\Vert^2},\, 0,\, 1\right), \qquad
\vec P_{closest} = \vec A + t\,\vec{AB}$$

**Degenerate segment:** if $\Vert\vec{AB}\Vert^2 \le 10^{-8}$ wu², the segment is a point:
$t = 0$, $\vec P_{closest} = \vec A$. (Input decimation guarantees `‖AB‖ ≥ 12 wu` for live segments,
but recoil/numerical paths must still never divide by zero.)

`t = 0` ⇒ contact on cap A, `t = 1` ⇒ cap B, `0 < t < 1` ⇒ contact on the segment body.

---

## 3. Circle vs Segment (Discrete Narrow Phase)

Obstacle: center $\vec C$, radius $r$, velocity $\vec V$. Shield: segment $\vec A\vec B$ with
half-thickness $r_s$ = `SHIELD_RADIUS` (the shield is a **capsule**).

$$\vec d = \vec C - \vec P_{closest}, \qquad \text{dist} = \Vert \vec d \Vert, \qquad R = r + r_s$$

- **Hit** iff $\text{dist}^2 \le R^2$ (inclusive ⇒ exact tangency counts, penetration 0).
- **Normal** (from shield toward obstacle): $\hat n = \vec d / \text{dist}$.
- **Penetration:** $p = R - \text{dist} \ge 0$.

Shields are **two-sided**: an obstacle hitting from below gets a downward normal.

### 3.1 Fallback normal (center exactly on the segment, dist ≤ 1e-6)

Direction is undefined, so it is chosen deterministically:

1. Non-degenerate segment: $\hat n_g = \operatorname{perp}(\vec{AB}) / \Vert\vec{AB}\Vert$.
   Flip so the obstacle is pushed back to the side it came from:
   if $\hat n_g \cdot \vec V > 0$ ⇒ $\hat n = -\hat n_g$;
   if $\hat n_g \cdot \vec V = 0$ ⇒ choose the variant with $n_y \le 0$ (screen-up, away from the bubble).
2. Degenerate segment, $\Vert \vec V\Vert > 0$: $\hat n = -\hat V$.
3. Degenerate segment, $\vec V = 0$: $\hat n = (0, -1)$ (UP).

---

## 4. Positional Correction (Anti-Sinking)

Applied immediately on contact, before the velocity response:

$$\vec C_{new} = \vec C + \hat n\,(R - \text{dist}) = \vec C + \hat n\,p$$

After correction the obstacle sits exactly at distance $R$ (tested to 1e-3 wu).
No slop/bias is used: because the impulse is only applied to **approaching** contacts
(§5), a resting/re-detected contact never double-reflects.

Per-substep push-out is clamped in `PhysicsSystem` to `MAX_PUSHOUT = 24 wu` to avoid
teleporting an obstacle when a brand-new segment is drawn straight through a large Crusher
(remaining penetration resolves over subsequent sub-steps).

---

## 5. Impulse Response with Active Swipe Velocity (Parry)

Each segment stores the filtered pointer velocity $\vec V_{swipe}$ at creation and its `age`.

### 5.1 Effective surface velocity (decay over the parry window)

$$w(\text{age}) = \operatorname{clamp}\left(1 - \frac{\text{age}}{T_{parry}},\, 0,\, 1\right), \qquad T_{parry} = 0.150\text{ s}$$

$$\vec V_{surf} = \vec V_{swipe}\cdot w(\text{age}) \cdot G_{parry}, \qquad G_{parry} = 1.0$$

Linear decay: full transfer at creation, 50% at 75 ms, zero at ≥ 150 ms.
$\Vert\vec V_{swipe}\Vert$ is clamped to `MAX_SWIPE_SPEED = 3000 wu/s` at creation.

### 5.2 Velocity reflection

$$\vec V_{rel} = \vec V - \vec V_{surf}, \qquad v_n = \vec V_{rel}\cdot\hat n$$

$$\text{if } v_n < 0:\quad \vec V_{new} = \vec V - (1 + e)\,v_n\,\hat n \qquad\text{else}\quad \vec V_{new} = \vec V$$

Properties (all unit-tested):
- Post-contact relative normal velocity is $-e\,v_n$ (Newton's law of restitution against a
  kinematic, infinite-mass shield).
- Tangential velocity is **preserved** (frictionless): only the $\hat n$ component changes.
- With $\vec V_{surf} = 0$ and $e = 1$ the result equals `V.reflect(n)` (mirror reflection;
  angle of incidence = angle of reflection).
- A shield retreating faster than the obstacle ($v_n \ge 0$) produces **no** impulse.
- A shield slamming a resting obstacle ($\vec V = 0$) launches it at $(1+e)(\vec V_{surf}\cdot\hat n)\hat n$.
- Only the **normal** component of the swipe matters. A swipe along the segment's own
  direction (the usual drawing motion) adds nothing on the segment body; parries arise
  mainly from **cap contacts at the leading stroke tip** (cap normal is radial, i.e. aligned
  with the swipe direction) and from curved/arcing strokes whose segments sweep sideways.

### 5.3 Normal impulse magnitude (damage, recoil, juice)

$$J = -(1 + e)\, m\, v_n \quad (v_n < 0), \qquad J = 0 \text{ otherwise} \qquad [\text{mass}\cdot\text{wu/s}]$$

### 5.4 Parry classification

A contact is a **PARRY** iff $\vec V_{surf}\cdot\hat n \ge$ `PARRY_MIN_NORMAL_SPEED = 250 wu/s`
(evaluated with the decayed surface velocity, therefore implicitly only within 150 ms).

Worked example (unit-tested): Drifter $\vec V = (0, 400)$, $\hat n = (0,-1)$, $e = 0.75$.

| Case | $\vec V_{surf}$ | $v_n$ | $\vec V_{new}$ |
|---|---|---|---|
| Passive shield | `(0, 0)` | −400 | `(0, −300)` |
| Parry, fresh | `(0, −600)` | −1000 | `(0, −1350)` (4.5× exit speed) |
| Shield retreating | `(0, +500)` | +100 | `(0, 400)` unchanged |
| Tangential swipe | `(800, 0)` | −400 | `(0, −300)` (no boost) |

---

## 6. Sub-Stepping & Continuous Collision Detection

### 6.1 Integration (semi-implicit Euler, per sub-step)

$$\vec V \mathrel{+}= \vec g\,\Delta t_{sub}, \qquad \vec C \mathrel{+}= \vec V\,\Delta t_{sub}, \qquad
\Delta t_{sub} = \frac{1/120}{4} = \frac{1}{480}\text{ s}, \qquad \vec g = (0, 520)\text{ wu/s}^2$$

Then $\Vert\vec V\Vert$ is clamped to `MAX_OBSTACLE_SPEED = 3600 wu/s`.

### 6.2 Tunneling safety inequality (discrete test)

A discrete capsule test cannot be skipped as long as the per-sub-step displacement is smaller
than the capsule's full thickness:

$$\Vert\vec V\Vert\,\Delta t_{sub} < 2(r + r_s)$$

Worst case: Needle $r = 7$, $r_s = 6$ ⇒ limit $26$ wu; at `MAX_OBSTACLE_SPEED` the displacement
is $3600/480 = 7.5$ wu ⇒ **3.47× safety margin**. Verified by
`discreteSubstepping_neverTunnels_atMaxNeedleSpeed`.

### 6.3 Swept circle vs segment (CCD) — belt and braces

Used for every obstacle with flag `CCD` (all Needles and Shards) and for any obstacle with
$\Vert\vec V\Vert\Delta t_{sub} > r + r_s$. Motion $\vec C(s) = \vec C_0 + s\,\vec D$, $s \in [0,1]$,
against the capsule (segment inflated by $R$):

1. **Initial overlap:** if the discrete test hits at $s=0$ ⇒ TOI $= 0$, contact from §3.
2. **Side faces** (non-degenerate segment). $\hat u = \vec{AB}/L$, $\hat m = \operatorname{perp}(\hat u)$,
   $h_0 = (\vec C_0 - \vec A)\cdot\hat m$, $h_d = \vec D\cdot\hat m$.
   If $|h_0| > R$ and $h_0 h_d < 0$:
   $s = (\operatorname{sign}(h_0)R - h_0)/h_d$; valid if $s\in[0,1]$ and
   $(\vec C_0 + s\vec D - \vec A)\cdot\hat u \in [0, L]$.
3. **End caps** (only if no side hit): ray vs circle of radius $R$ at $\vec A$ and $\vec B$:
   $\vec m = \vec C_0 - \vec P$, $a = \vec D\cdot\vec D$, $b = \vec m\cdot\vec D$, $c = \vec m\cdot\vec m - R^2$;
   hit iff $b < 0$ and $b^2 - ac \ge 0$; $s = (-b - \sqrt{b^2 - ac})/a$, valid if $s \le 1$.
   TOI $=$ earliest valid cap hit.
4. **Contact at impact:** $\vec C_{hit} = \vec C_0 + s\vec D$; normal $= (\vec C_{hit} - \vec P_{closest})/R$
   (fallback §3.1 using $\vec D$ as velocity); penetration $= \max(0, R - \text{dist}) \approx 0$.

Correctness argument: the capsule is convex, so a ray starting outside enters exactly once.
If the near side-face crossing projects inside $[0, L]$ it is the entry point; otherwise the
entry is on a cap, and the earliest cap-circle entry is the capsule entry (a cap-circle point
inside the rectangle would already be interior).

`PhysicsSystem` usage: on a swept hit, move to $\vec C_0 + \max(0, s - 10^{-4})\vec D$, apply §5,
then spend the remaining $(1 - s)$ fraction of the sub-step with the new velocity (at most
2 TOI iterations per sub-step; any remainder is dropped deterministically).

---

## 7. Restitution & Mass Table

| Archetype | Collision radius (wu) | Mass | $e$ | CCD | Shield damage on hit |
|---|---|---|---|---|---|
| Drifter (circle) | 28 | 1.0 | **0.75** | no | $J \cdot 0.05$ |
| Needle (spike) | 7 | 0.35 | 0.60 | **yes** | $J \cdot 0.05$ |
| Crusher (hexagon) | 46 | 4.0 | **0.30** | no | **60** flat (60% of max integrity) + recoil |
| Splitter (diamond) | 32 | 1.2 | 0.70 | no | $J \cdot 0.05$, then splits |
| Shard (splitter child) | 14 | 0.4 | 0.80 | **yes** | $J \cdot 0.05$ |

Typical damage: Drifter at 350 wu/s ⇒ $J = 1.75 \cdot 1.0 \cdot 350 = 612.5$ ⇒ 30.6 integrity.
Needle at 1100 wu/s ⇒ $J = 1.6 \cdot 0.35 \cdot 1100 = 616$ ⇒ 30.8 integrity.

`SHIELD_DAMAGE_COOLDOWN = 0.10 s`: the same obstacle cannot damage the same segment more than
once per cooldown (prevents resting contacts from draining integrity every sub-step).

### 7.1 Crusher special rules
- Integrity drain: `CRUSHER_INTEGRITY_DRAIN = 0.60 × SEGMENT_INTEGRITY = 60` (replaces $J$-damage).
- Recoil: both endpoints translate by `CRUSHER_RECOIL_DISTANCE = 14 wu` along $-\hat n$
  (downward for a Crusher arriving from above); the broadphase is marked dirty.

### 7.2 Splitter rules
On its **first** shield contact (flag `HAS_SPLIT` not set): resolve §4–§5 normally, then
replace it with 2 Shards at the contact center:

$$\vec V_{shard,\pm} = \operatorname{rotated}(\vec V_{new}, \pm 25°)\cdot 1.4$$

Each shard is offset by $\pm 0.5\,r_{shard}$ along $\operatorname{perp}(\hat V_{new})$ to avoid
shard-shard overlap. Shards inherit `PARRIED` if the split contact was a parry.

---

## 8. Bubble Collision

Circle–circle: obstacle hits bubble iff $\Vert\vec C - \vec C_{bubble}\Vert \le r + R_{bubble}$,
$R_{bubble} = 64$ wu. Effect: obstacle destroyed (shatter particles), bubble loses 1 HP unless
invulnerable (`BUBBLE_INVULN_TIME = 1.0 s`). The bubble is not a physics body (no impulse).

---

## 9. Canonical Physics Constants (→ `GameConfig.kt`)

| Name | Value | Unit |
|---|---|---|
| `WORLD_WIDTH` / `WORLD_HEIGHT` | 1000 / 1800 | wu |
| `FIXED_DT` | 1/120 | s |
| `SUBSTEPS` | 4 | — |
| `SUB_DT` | 1/480 | s |
| `MAX_FRAME_TIME` | 0.05 | s |
| `MAX_STEPS_PER_FRAME` | 6 | — |
| `GRAVITY_Y` | 520 | wu/s² |
| `MAX_OBSTACLE_SPEED` | 3600 | wu/s |
| `SHIELD_RADIUS` | 6 | wu (half thickness) |
| `MAX_PUSHOUT` | 24 | wu per sub-step |
| `PARRY_WINDOW` | 0.150 | s |
| `PARRY_GAIN` | 1.0 | — |
| `PARRY_MIN_NORMAL_SPEED` | 250 | wu/s |
| `MAX_SWIPE_SPEED` | 3000 | wu/s |
| `SWIPE_VELOCITY_TAU` | 0.020 | s (EMA time constant) |
| `IMPACT_DAMAGE_PER_IMPULSE` | 0.05 | integrity per mass·wu/s |
| `SHIELD_DAMAGE_COOLDOWN` | 0.10 | s |
| `CRUSHER_INTEGRITY_DRAIN` | 0.60 | fraction of max integrity |
| `CRUSHER_RECOIL_DISTANCE` | 14 | wu |
| `SPLIT_SPREAD_ANGLE` | 25° (0.436332 rad) | rad |
| `SPLIT_SPEED_MULTIPLIER` | 1.4 | — |
| `BUBBLE_RADIUS` | 64 | wu |
| `CCD_TOI_BACKOFF` | 1e-4 | fraction of sub-step |
| `MAX_TOI_ITERATIONS` | 2 | per sub-step |
| `COLLISION_EPSILON` | 1e-6 | wu |
| `DEGENERATE_SEGMENT_LENGTH_SQ` | 1e-8 | wu² |

---

## 10. 2.5D Perspective Projection Duality

The 480Hz narrowphase simulation remains entirely in 2D Virtual World Space ($1000 \times 1800\text{ wu}$). The 2.5D third-person view is a bijective projective mapping executed purely in the render and gesture input systems:

1. **Longitudinal Axis ($+Y$):** Represents distance down the lane toward the player/camera ($Y = 1850\text{ wu}$).
2. **Deflection / Parry ($-\Delta V_y$):** Slashing a shield transfers upward swipe velocity $-V_y$, which in perspective accelerates the obstacle away into the distance towards the horizon.
3. **Continuous Collision Detection (CCD):** Remains independent of perspective distortion. High-speed Needles moving at $3600\text{ wu/s}$ are verified through sub-stepping ($7.5\text{ wu/sub-step} \ll 26\text{ wu}$ collision capsule thickness) and exact TOI swept calculation.
4. **Touch Gesture Raycasting:** Screen coordinates $(sx, sy)$ unproject onto the ground plane $(X, Y)$ via closed-form algebraic inverse:
   $$Z = \frac{K}{\max(sy, V_y + 8) - V_y}, \quad Y = 1850 - Z, \quad X = 500 + \frac{(sx - V_x) \cdot Z}{D \cdot \text{laneScaleX}}$$

