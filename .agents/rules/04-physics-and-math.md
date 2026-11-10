---
trigger: glob
globs: "**/engine/**, **/domain/systems/**, **/math/**"
---

# Deterministic Physics & Collision Solver Specification

Bubble Defense physics operates in a virtual world coordinate space of `1000f × 1800f` (+Y down, bubble resting at `(500f, 1476f)`).

## 1. Fixed-Timestep Accumulator & Sub-stepping
- Strict fixed timestep: `FIXED_DT = 1f / 120f` (8.333ms).
- Narrowphase collision sub-stepping: `SUBSTEPS = 4` (effective 480Hz narrowphase resolution).
- Accumulator pattern: clamp render frame delta to `maxFrameTime = 0.05f`. Consume `dt = FIXED_DT / SUBSTEPS` per sub-step.

## 2. Exact Collision & Anti-Sinking Projection
For circle obstacle (center $\vec{C}$, radius $r$) vs. capsule/segment line ($\vec{A}$ to $\vec{B}$, normal $\hat{n}$, radius $r_{shield}$):
1. **Closest Point on Segment:**
   $$t = \text{clamp}\left(\frac{(\vec{C} - \vec{A}) \cdot (\vec{B} - \vec{A})}{\Vert\vec{B} - \vec{A}\Vert^2}, 0.0, 1.0\right)$$
   $$\vec{P}_{closest} = \vec{A} + t \cdot (\vec{B} - \vec{A})$$
2. **Penetration Depth:**
   $$\vec{d} = \vec{C} - \vec{P}_{closest}, \quad \text{dist} = \Vert\vec{d}\Vert$$
   Collision occurs if $\text{dist} < r + r_{shield}$. Normal: $\hat{n} = \vec{d} / \text{dist}$.
3. **Positional Correction (Anti-Sinking):**
   Immediately push obstacle out of the shield line:
   $$\vec{C}_{new} = \vec{C} + \hat{n} \cdot (r + r_{shield} - \text{dist})$$
4. **Impulse Reflection with Parry Velocity:**
   $$\vec{V}_{rel} = \vec{V} - \vec{V}_{swipe}$$
   If $(\vec{V}_{rel} \cdot \hat{n}) < 0$:
   $$\vec{V}_{new} = \vec{V} - (1 + e)(\vec{V}_{rel} \cdot \hat{n})\hat{n}$$

## 3. Continuous Collision Detection (CCD)
- Fast projectiles (e.g. `Needle`) with velocity $\Vert\vec{V}\Vert \cdot dt > r$ must use raycast/swept-circle continuous collision checks against shield segments to prevent tunneling through thin barriers.
