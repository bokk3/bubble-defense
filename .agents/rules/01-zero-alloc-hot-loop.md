---
trigger: glob
globs: "**/src/**, **/*.kt"
---

# Zero-Allocation Hot Loop Discipline

All code executing in the simulation loop (`FixedGameLoop.tick()`, `PhysicsSystem.step()`, `update()`) and the render loop (`ProceduralRenderer.draw()`, `DrawScope`) must strictly adhere to a **0-heap-allocation** policy to guarantee a locked 120Hz frame rate with 0ms GC pause stutter.

## 1. Bit-Packed Math (`Vec2`)
- `Vec2` is an `@JvmInline value class Vec2(val packed: Long)`.
- Never unbox into heap objects.
- All vector math (`+`, `-`, `*`, `dot`, `cross`, `length`, `normalized`, `reflect`) must remain `inline` and return packed primitives.
- Avoid passing `Vec2?` nullable types in hot paths, as nullable value classes cause heap boxing in Kotlin/JVM. Use sentinel values (e.g., `Vec2.ZERO` or specialized boolean flags) instead of null.

## 2. Pre-Allocated Pools & Arrays
- Never call constructors (`new`, `Vec2(...)` object boxing, `ArrayList(...)`, or lambda closures) inside hot paths.
- Entities are allocated upfront in pre-allocated Structure-of-Arrays or fixed-size flat object pools:
  - `ObstaclePool` (max 128 active)
  - `ShieldSegmentPool` (max 256 active)
  - `ParticlePool` (max 512 active)
  - `FloatingTextPool` (max 32 active)
- Iteration over pools must use indexed `while` or `for (i in 0 until activeCount)` loops using primitive integers. Avoid iterator allocations (`forEach`, iterator instances).

## 3. DrawScope Zero-Allocation Rule
- Pre-allocate all `Path`, `Paint`, `Matrix`, `ColorFilter`, and `Brush` instances in reusable state containers.
- Reset and reconfigure pre-allocated paths (`path.rewind()`, `path.moveTo()`, `path.lineTo()`) rather than instantiating new paths per frame.
- Do not construct dynamic strings or string concatenations inside `draw()` without pre-allocated buffers.
