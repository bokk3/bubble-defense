# MASTER TASKLIST & LIVING STATE — Bubble Defense

> **Single Source of Truth** for sprint planning, task tracking, numerical edge cases, and agent handoffs.  
> Every agent turn MUST read this file first and update checkboxes (`[ ]` &rarr; `[/]` &rarr; `[x]`), task notes, and handoff logs.

---

## 1. Release & Semantic Versioning Roadmap

We adhere to **Semantic Versioning 2.0.0 (`MAJOR.MINOR.PATCH`)**:
- **`MAJOR`**: Breaking architectural redesign or major engine overhaul.
- **`MINOR`**: Completion of an execution phase with new capabilities/systems.
- **`PATCH`**: Bug fixes, performance tuning, spec synchronizations, or test additions.

| Version | Phase | Target Scope | Status | Release Date |
|---|---|---|---|---|
| **`v0.1.0`** | **Phase 1** | Docs Architecture, Zero-Alloc Math Core (`Vec2`, `CollisionMath`), Math Tests | **RELEASED** | 2026-11-10 |
| **`v0.2.0`** | **Phase 2** | Pre-allocated SoA Pools, Virtual Viewport Scaler, Fixed Timestep Game Loop | **RELEASED** | 2026-11-10 |
| **`v0.2.1`** | **Arch Refactor** | Antigravity Modular Rules (`.agents/rules/`), Skills Migration (`.agents/skills/`), Master Registry Hub | **RELEASED** | 2026-11-10 |
| **`v0.3.0`** | **Phase 3** | Tactile Shield Input, Physics Sub-step System, Zero-Alloc Compose Canvas Renderer | **PLANNED** | Pending |
| **`v0.4.0`** | **Phase 4** | Wave Director, Juice System, Flow Economy, Game Over & Sub-300ms Instant Retry | **PLANNED** | Pending |
| **`v1.0.0`** | **Phase 5** | Production Release Candidate, 120Hz SysTrace Profiling, APK Assembly | **PLANNED** | Pending |

---

## 2. Comprehensive Master Tasklist

Legend: `[x]` Completed &bull; `[/]` In Progress &bull; `[ ]` Not Started

### Phase 1: Architecture, Docs & Zero-Alloc Math Core (`v0.1.0`) — [COMPLETED]
- [x] **T1.1 Core Specification & Skill Integration** `[ARCH-AGENT]`
  - [x] Create `.cursorrules` establishing zero-allocation hot paths, determinism, and Kotlin standards.
  - [x] Integrate 3 skills: `kinetic-flow-architect`, `kotlin-ux-architect`, `arcade-economy-architect`.
  - [x] Repair and validate Markdown/KaTeX formatting across `AGENTS.md`.
- [x] **T1.2 Complete Living Documentation Suite** `[ARCH-AGENT]`, `[PHYS-AGENT]`, `[JUICE-AGENT]`
  - [x] `docs/ARCHITECTURE.md`: Module hierarchy, coordinate spaces, fixed loop, SoA layout, broadphase.
  - [x] `docs/PHYSICS_SPEC.md`: Vector math, discrete/swept circle-segment intersection, parry formulas, restitution.
  - [x] `docs/GAME_DESIGN.md`: Bubble wobble harmonic equations, ink economy, director pacing, juice matrix, neon palette.
  - [x] `docs/STATE_AND_TASKS.md`: Initial sprint board and edge cases.
- [x] **T1.3 Zero-Allocation Packed Math (`Vec2`)** `[PHYS-AGENT]`
  - [x] Implement `@JvmInline value class Vec2(val packed: Long)` packing two 32-bit floats without heap boxing.
  - [x] Implement vector operators (`+`, `-`, `*`, `/`, `dot`, `cross`, `length`, `normalized`, `reflect`, `rotated`).
- [x] **T1.4 Stateless Collision & Impulse Solver (`CollisionMath`)** `[PHYS-AGENT]`
  - [x] Implement closest point on segment with projection parameter $t \in [0, 1]$.
  - [x] Implement discrete circle vs capsule intersection with caller-owned reusable `Contact` record.
  - [x] Implement deterministic fallback normal when obstacle center lands exactly on segment core.
  - [x] Implement anti-sinking positional projection (`separate` and `separateClamped`).
  - [x] Implement impulse reflection with active swipe velocity transfer and linear parry decay ($w(\text{age})$).
  - [x] Implement continuous collision detection (`sweptCircleVsSegment`) with exact TOI calculation.
- [x] **T1.5 Verification & Allocation Probe Suite** `[PHYS-AGENT]`
  - [x] Implement `CollisionMathTest.kt` covering grazing hits, end caps, oblique slopes, and parry angles.
  - [x] Implement swept CCD test verifying zero tunneling at maximum speed (`3600 wu/s`).
  - [x] Implement reflective `ThreadMXBean` memory allocation probe verifying 0 bytes allocated over 200,000 iterations.
  - [x] Verify green test execution: 61/61 tests passing.

---

### Phase 2: Memory Pools, Virtual Viewport & Fixed Timestep World (`v0.2.0`) — [COMPLETED]
- [x] **T2.1 Gradle & Project Infrastructure Setup** `[ARCH-AGENT]`
  - [x] Configure `settings.gradle.kts` and root `build.gradle.kts`.
  - [x] Configure `app/build.gradle.kts` with Android SDK 35, Jetpack Compose BOM, and Kotlin compiler options.
  - [x] Configure ProGuard / R8 preservation rules for packed `@JvmInline` value classes.
  - [x] Configure `app/src/main/AndroidManifest.xml` locked to portrait orientation.
- [x] **T2.2 Canonical Balance Constants (`GameConfig`)** `[PHYS-AGENT]`, `[JUICE-AGENT]`
  - [x] Implement `domain/config/GameConfig.kt` with all constants from `docs/PHYSICS_SPEC.md` and `docs/GAME_DESIGN.md`.
  - [x] Canonical balance constants for physics, ink, archetypes, wave director, juice, and economy.
- [x] **T2.3 Virtual Viewport & Coordinate Transformer (`ViewportScaler`)** `[ARCH-AGENT]`
  - [x] Implement `engine/loop/ViewportScaler.kt` mapping 1000×1800 Virtual World Space to physical screen pixels.
  - [x] Provide uniform letterboxing math: scale factor $s = \min(S_w/1000, S_h/1800)$, offsets $o_x, o_y$.
  - [x] Provide zero-allocation coordinate conversions: `worldToScreenX`, `worldToScreenY`, `screenToWorldX`, `screenToWorldY`, distance scaling.
  - [x] Implement `ViewportScalerTest.kt` covering identity, pillarbox, letterbox, real phone dimensions, and bounds checks.
- [x] **T2.4 Pre-allocated Structure-of-Arrays Pools (`EntityPools`)** `[ARCH-AGENT]`
  - [x] Implement `engine/pool/EntityPools.kt` with dense array storage and O(1) swap-remove.
  - [x] `ObstaclePool` (capacity 128, 18 primitive arrays for position, velocity, mass, archetype, flags).
  - [x] `ShieldSegmentPool` (capacity 256, 16 primitive arrays for endpoints, normal, swipe velocity, integrity).
  - [x] `ParticlePool` (capacity 512, rolling cursor ring buffer for line shards and sparks).
  - [x] `FloatingTextPool` (capacity 32, rolling cursor ring buffer for combat text popups).
  - [x] `InputRingBuffer` (capacity 256 samples, circular buffer for touch events).
  - [x] Implement `EntityPoolsTest.kt` verifying swap-remove dense compaction, overflow policies, and ring cursors.
- [x] **T2.5 World State Container (`GameWorld`)** `[ARCH-AGENT]`
  - [x] Implement `domain/world/GameWorld.kt` holding all pre-allocated pools, bubble state, score, and combo counters.
  - [x] Provide zero-alloc `reset()` method restoring initial conditions in `<0.1ms`.
  - [x] Implement Clutch Revive EMP surge activation and trauma/hit-stop trigger methods.
- [x] **T2.6 Fixed-Timestep Game Loop (`FixedGameLoop`)** `[ARCH-AGENT]`, `[PHYS-AGENT]`
  - [x] Implement `engine/loop/FixedGameLoop.kt` with accumulator at `FIXED_DT = 1/120s`.
  - [x] Implement spiral-of-death clamp (`MAX_FRAME_TIME = 0.05s`, `MAX_STEPS_PER_FRAME = 6`).
  - [x] Implement Hit-Stop time dilation timer (pauses simulation accumulator while preserving rendering and camera shake).
  - [x] Compute render interpolation factor `alpha = accumulator / FIXED_DT`.
- [x] **T2.7 Phase 2 Unit Tests & Verification** `[ARCH-AGENT]`
  - [x] Implement `ViewportScalerTest.kt` verifying aspect-ratio preservation and round-trip coordinate accuracy.
  - [x] Implement `EntityPoolsTest.kt` verifying swap-remove dense compaction, overflow policies, and ring cursors.
  - [x] Implement `FixedGameLoopTest.kt` verifying deterministic step accumulation and hit-stop pause mechanics.
  - [x] Verify zero heap allocations in `FixedGameLoop` hot path over 50,000 steps.
  - [x] Total test suite: 79/79 passing unit tests (0 failures, 0 skipped).
- [x] **T2.8 Antigravity Rules & Skills Refactoring (`v0.2.1`)** `[ARCH-AGENT]`
  - [x] Establish `.agents/rules/` with 5 modular YAML-triggered rule modules (`01-zero-alloc-hot-loop`, `02-docs-and-tasks`, `03-compose-and-threading`, `04-physics-and-math`, `05-economy-quarantine`).
  - [x] Migrate skills to canonical `.agents/skills/` (`arcade-economy-architect`, `kinetic-flow-architect`, `kotlin-ux-architect`) with progressive disclosure frontmatter.
  - [x] Refactor root `AGENTS.md` as Master Directory Rule (< 24 KB) with complete Rule & Skill Registry.
  - [x] Synchronize `.antigravity/skills/` and `.cursorrules` for dual discovery compatibility.

---

### Phase 3: Tactile Shield Input, Physics Solver & Procedural Renderer (`v0.3.0`)
- [ ] **T3.1 High-Refresh Gesture Input Pipeline (`ShieldInputSystem`)** `[JUICE-AGENT]`, `[kotlin-ux-architect]`
  - [ ] Implement `domain/systems/ShieldInputSystem.kt` consuming samples from `InputRingBuffer`.
  - [ ] Iterate `PointerInputChange.historical` sequentially before `position` to sample 120Hz–240Hz gestures.
  - [ ] Exponential moving average (EMA) swipe velocity filter ($\tau = 20\text{ms}$).
  - [ ] Minimum distance decimation (`MIN_SEGMENT_LENGTH = 12 wu`) and segment splitting (`MAX_SEGMENT_LENGTH = 48 wu`).
  - [ ] Ink drain and regeneration logic with 0.4s cooldown and truncation on empty tank.
- [ ] **T3.2 Physics Simulation & Sub-stepping Solver (`PhysicsSystem`)** `[PHYS-AGENT]`
  - [ ] Implement `domain/systems/PhysicsSystem.kt` stepping at `480Hz`.
  - [ ] Semi-implicit Euler integration with gravity ($g = 520\text{ wu/s}^2$) and terminal speed clamping.
  - [ ] Uniform grid broadphase (100 wu cells, 10×18 grid, counting-sort candidate query).
  - [ ] Narrowphase circle-capsule collision resolution using `CollisionMath`.
  - [ ] Crusher 60% integrity drain and downward segment recoil (`14 wu`).
  - [ ] Splitter shard fracturing on first shield contact (spawn 2 child shards rotated $\pm 25^\circ$).
  - [ ] Bubble circle-circle collision detection and invulnerability timer (`BUBBLE_INVULN_TIME = 1.0s`).
- [ ] **T3.3 Zero-Allocation Procedural Compose Canvas Renderer (`ProceduralRenderer`)** `[JUICE-AGENT]`, `[kotlin-ux-architect]`
  - [ ] Implement `ui/render/ProceduralRenderer.kt` using pure DrawScope calls.
  - [ ] Zero heap allocations in `DrawScope`: pre-allocate and reuse all `Path`, `Paint`, and `Stroke` instances.
  - [ ] Multi-pass procedural neon bloom: Outer Glow (wide, α 0.12) &rarr; Mid Core (accent, α 0.6) &rarr; Hot Core (thin white).
  - [ ] Soft-body Bubble wobble with 64-vertex procedural sine-wave harmonic deformation and proximity warning ring.
  - [ ] Parallax scrolling layers: Far starfield (0.15×), Mid neon horizon grid (0.40×), Near drifting wisps (0.80×).
  - [ ] Render shield segments with integrity-based chromatic transition (Cyan &rarr; Magenta &rarr; Orange).
  - [ ] Render obstacle geometry: Drifter circles, Needle spikes, Crusher hexagons, Splitter diamonds.
- [ ] **T3.4 Interactive Game Screen & Diegetic HUD (`GameScreen`)** `[JUICE-AGENT]`, `[kotlin-ux-architect]`
  - [ ] Implement `ui/GameScreen.kt` hosting the Compose `Canvas` and gesture detector.
  - [ ] Defer state reads to Draw phase using `frameTick` invalidation to eliminate Compose recomposition during gameplay.
  - [ ] Diegetic Ink Arc rendered around the Bubble's outer rim for peripheral resource tracking.
  - [ ] HUD overlay with `fontFeatureSettings = "tnum"` for altitude, score, combo multiplier, and ink meter.
- [ ] **T3.5 Phase 3 Interactive Verification** `[PHYS-AGENT]`, `[JUICE-AGENT]`
  - [ ] Verify drawing responsive glowing shields on screen with zero dropped frames.
  - [ ] Verify bouncing test obstacles and parry velocity reflections.

---

### Phase 4: Wave Director, Obstacle Archetypes, Juice & Flow Economy (`v0.4.0`)
- [ ] **T4.1 Sawtooth Wave Director (`DirectorSystem`)** `[JUICE-AGENT]`, `[PHYS-AGENT]`
  - [ ] Implement `domain/systems/DirectorSystem.kt` with seeded xorshift32 deterministic RNG.
  - [ ] Sawtooth wave rhythm: 14s High-Intensity Crunch vs 6s Breather / Harvest Valley.
  - [ ] Dynamic threat budget $\dot{B}(t) = \lambda(t) \cdot \phi(t)$ and archetype unlocking schedule.
  - [ ] High-speed Needle and Splitter top-bezel warning telegraph (350ms pulsing chevron).
  - [ ] Soft currency Prism spawning during harvest valleys.
- [ ] **T4.2 Sensory Amplification Matrix (`JuiceSystem`)** `[JUICE-AGENT]`, `[kinetic-flow-architect]`
  - [ ] Implement `domain/systems/JuiceSystem.kt`.
  - [ ] 28ms Hit-Stop freeze frame on Perfect Parry.
  - [ ] Quadratic camera trauma shake ($\text{shake} = \text{trauma}^2$) driven by 18Hz smooth noise.
  - [ ] Directional particle inheritance: spawn line fragments and sparks inheriting obstacle reflection velocity.
  - [ ] Shepard-tone combo audio modulation (`1.0 + combo * 0.05`).
  - [ ] Pooled floating combat text popups (`PARRY!`, `+50`, `CLOSE CALL!`) with scale-pop spring damping.
- [ ] **T4.3 Flow-State Monetization & Quarantined SDK (`EconomyGateway`)** `[ECON-AGENT]`, `[arcade-economy-architect]`
  - [ ] Define pure Kotlin `EconomyGateway` and `RewardPlacement` interfaces in `domain/economy/`.
  - [ ] Implement 1-per-run Clutch Revive / EMP Surge with 4.0s radial fuse on near-miss deaths.
  - [ ] Implement procedural cosmetic profiles (`ShieldSkin`, `BubbleSkin`) with zero asset overhead.
  - [ ] Implement `MockEconomyService` for offline instant testing.
  - [ ] Enforce zero banner ads and 180s minimum interstitial cooldown.
- [ ] **T4.4 Main Activity, Game Over Flow & Sub-300ms Instant Retry** `[ARCH-AGENT]`, `[kinetic-flow-architect]`
  - [ ] Implement `MainActivity.kt` with edge-to-edge window insets and immersive mode.
  - [ ] Game Over screen with near-miss milestone highlight and high score persistence (`SharedPreferences`).
  - [ ] Sub-300ms instant retry: `TAP ANYWHERE TO RETRY` active within 350ms of bubble pop with in-place pool reset.
- [ ] **T4.5 Phase 4 Full Loop Verification** `[ARCH-AGENT]`
  - [ ] Verify full gameplay loop: Start &rarr; Play &rarr; Parry &rarr; Game Over &rarr; Instant Retry.

---

### Phase 5: Release Candidate, Assetless Polish & Performance Profiling (`v1.0.0`)
- [ ] **T5.1 120Hz Zero-Jank Macrobenchmark & SysTrace Audit** `[ARCH-AGENT]`, `[kotlin-ux-architect]`
  - [ ] Verify 0 dropped frames at 120Hz and 0ms GC pauses during sustained gameplay.
- [ ] **T5.2 Release Build Assembly** `[ARCH-AGENT]`
  - [ ] Assemble signed release APK with ProGuard/R8 optimizations preserving packed value classes.
- [ ] **T5.3 Final Living Documentation Audit** `[ARCH-AGENT]`
  - [ ] Synchronize all `/docs` specs with final shipping constants.

---

## 3. Known Edge Cases & Handling

1. **Degenerate Segments (`lenSq <= 1e-8 wu²`):**
   - Handled in `CollisionMath`: parameter defaults to `t = 0`, normal defaults to `UP` (or `-velocity`), avoiding division by zero.
2. **Exact Center on Line Segment (`dist <= 1e-6 wu`):**
   - Deterministic fallback normal opposing obstacle velocity is selected to push the entity back to the side it arrived from; tie-break defaults to `UP`.
3. **Tunneling on High-Velocity Entities (Needle / Shard):**
   - Sub-stepping at 480Hz guarantees displacement per step (`<= 7.5 wu`) remains well below capsule thickness (`26 wu`).
   - Swept CCD (`sweptCircleVsSegment`) provides secondary continuous entry calculation with exact TOI calculation.
4. **Crusher Recoil & Deep Penetration:**
   - Pushout per sub-step is clamped to `MAX_PUSHOUT = 24 wu` to prevent teleportation if drawn directly across an obstacle.
5. **JVM Value Class Boxing:**
   - `Vec2` is an `@JvmInline value class` over a packed `Long`. Prohibited from nullable types, generics (`List<Vec2>`), or `Any` on hot paths.
   - Tested and verified via zero-allocation test harness.
6. **Lag-Spike Spiral of Death:**
   - Clamped in `FixedGameLoop` via `MAX_FRAME_TIME = 0.05s` and `MAX_STEPS_PER_FRAME = 6`.

---

## 4. Change Log & Semantic Version History

### `v0.2.1` (2026-11-10) — Antigravity Rules & Skills Architecture Refactor
- **chore(arch):** Establish `.agents/rules/` containing 5 modular triggered rule files (`01-zero-alloc-hot-loop.md`, `02-docs-and-tasks.md`, `03-compose-and-threading.md`, `04-physics-and-math.md`, `05-economy-quarantine.md`).
- **chore(skills):** Migrate active architectural skills to canonical `.agents/skills/` (`arcade-economy-architect`, `kinetic-flow-architect`, `kotlin-ux-architect`) with progressive disclosure YAML frontmatter.
- **docs(hub):** Refactor root `AGENTS.md` as Master Directory Rule (< 24 KB) with top-level Rule & Skill Registry, multi-agent personas, and clickable links.
- **chore(compat):** Synchronize `.cursorrules` and maintain `.antigravity/skills/` for dual discovery compatibility.

### `v0.2.0` (2026-11-10) — Phase 2: Memory Pools, Virtual Viewport & Fixed Timestep World
- **feat(infra):** Set up `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `AndroidManifest.xml`, and ProGuard rules.
- **feat(config):** Implement canonical tuning constants and balance knobs in `domain/config/GameConfig.kt`.
- **feat(loop):** Implement `engine/loop/ViewportScaler.kt` with uniform letterboxing, distance scaling, and zero-allocation coordinate mappings.
- **feat(pool):** Implement Structure-of-Arrays pools in `engine/pool/EntityPools.kt` (`ObstaclePool`, `ShieldSegmentPool`, `ParticlePool`, `FloatingTextPool`, `InputRingBuffer`) with O(1) swap-remove and oldest-segment eviction.
- **feat(world):** Implement `domain/world/GameWorld.kt` with sub-0.1ms zero-allocation `reset()` and Clutch Revive EMP surge mechanics.
- **feat(loop):** Implement `engine/loop/FixedGameLoop.kt` @ 120Hz physics (`FIXED_DT = 1/120s`, `SUBSTEPS = 4`), lag clamp, hit-stop pause, and render interpolation factor `alpha`.
- **test(engine):** Add unit tests `ViewportScalerTest.kt`, `EntityPoolsTest.kt`, and `FixedGameLoopTest.kt`. Verify 79/79 passing tests including a 50,000-step zero-allocation loop probe.

### `v0.1.0` (2026-11-10) — Phase 1: Architecture, Docs & Zero-Alloc Math Core
- **feat(math):** Implement packed `@JvmInline value class Vec2(val packed: Long)` with zero boxing.
- **feat(collision):** Implement `CollisionMath` circle vs segment solver, fallback normal, anti-sinking projection, parry velocity transfer, and swept CCD.
- **docs(architecture):** Establish `docs/ARCHITECTURE.md`, `docs/PHYSICS_SPEC.md`, `docs/GAME_DESIGN.md`.
- **docs(agents):** Format `AGENTS.md` and integrate 3 skills: `kinetic-flow-architect`, `kotlin-ux-architect`, `arcade-economy-architect`.
- **test(math):** Implement `CollisionMathTest.kt` with 61/61 passing unit tests and a 200,000-iteration zero-allocation probe.

---

## 5. Agent Handoff Notes

- **Date:** 2026-11-10
- **Current Version:** `v0.2.1`
- **Active Persona:** [ARCH-AGENT] & [PHYS-AGENT]
- **Status:** Architectural refactor complete. Rules modularized into `.agents/rules/`, skills organized in `.agents/skills/`, and root `AGENTS.md` operating as master directory hub under 24 KB. 79/79 passing unit tests (0 allocations, 0ms GC overhead).
- **Next Task:** Phase 3 — Task T3.1 (`ShieldInputSystem.kt`), Task T3.2 (`PhysicsSystem.kt`), Task T3.3 (`ProceduralRenderer.kt`), Task T3.4 (`GameScreen.kt`).
