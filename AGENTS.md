# BUBBLE DEFENSE — MASTER AGENT & DIRECTORY SPECIFICATION

Welcome to **Bubble Defense**, a high-precision 2D physics arcade game built in 100% pure Kotlin using Jetpack Compose `Canvas`. This root document functions as the primary Directory Rule and Central Hub for all agents, subagents, and tools.

---

## 0. RULE, SKILL & DOCUMENTATION REGISTRY

All agents must be aware of and follow the modular rules, skills, and living documentation maintained across this repository:

### A. Modular Triggered Rules (`.agents/rules/`)
- [01-zero-alloc-hot-loop.md](file:///.agents/rules/01-zero-alloc-hot-loop.md) (`trigger: glob: **/src/**, **/*.kt`): Zero heap allocation in per-frame `tick()`, `step()`, and `draw()` loops. Packed `Vec2` primitives, pre-allocated pools, zero boxing.
- [02-docs-and-tasks.md](file:///.agents/rules/02-docs-and-tasks.md) (`trigger: always_on`): Docs-as-Memory protocol, master tasklist discipline in `docs/STATE_AND_TASKS.md`, SemVer 2.0.0, conventional commits.
- [03-compose-and-threading.md](file:///.agents/rules/03-compose-and-threading.md) (`trigger: glob: **/ui/**, **/render/**`): Compose phase discipline, state read deferral, 120Hz `withFrameNanos`, tabular numerals (`tnum`).
- [04-physics-and-math.md](file:///.agents/rules/04-physics-and-math.md) (`trigger: glob: **/engine/**, **/domain/systems/**`): 120Hz fixed timestep accumulator, 4x sub-stepping (480Hz narrowphase), circle-segment solver, anti-sinking projection, CCD.
- [05-economy-quarantine.md](file:///.agents/rules/05-economy-quarantine.md) (`trigger: glob: **/domain/economy/**, **/billing/**`): Pure Kotlin `EconomyGateway`, zero SDK leakage, zero banner ads, cooldown-gated interstitials, 1-per-run Clutch Revive.

### B. Specialized Architectural Skills (`.agents/skills/`)
These skills provide actionable runbooks and domain workflows loaded via progressive disclosure:
- [kinetic-flow-architect](file:///.agents/skills/kinetic-flow-architect/SKILL.md): 3-Tier Dopamine Clock, sensory juice matrix, hit-stop freeze frames, trauma camera shake, dynamic wave pacing.
- [kotlin-ux-architect](file:///.agents/skills/kotlin-ux-architect/SKILL.md): 120Hz zero-jank Compose phase hygiene, zero-alloc DrawScope, procedural shaders, historical gesture sampling.
- [arcade-economy-architect](file:///.agents/skills/arcade-economy-architect/SKILL.md): Flow-state monetization, procedural cosmetic sinks, soft currency Prisms, clutch revives, quarantined billing/ad SDKs.

### C. Persistent Memory State Machine (`/docs/`)
- [ARCHITECTURE.md](file:///docs/ARCHITECTURE.md): System architecture, memory pool layouts, fixed-timestep loop diagram, coordinate space, SDK quarantine.
- [PHYSICS_SPEC.md](file:///docs/PHYSICS_SPEC.md): Vector equations, restitution tables, anti-sinking math, parry impulse multipliers.
- [GAME_DESIGN.md](file:///docs/GAME_DESIGN.md): Ink economy formulas, sensory matrix, wave director pacing, score choreography, cosmetic profiles, neon cyberpunk palette.
- [STATE_AND_TASKS.md](file:///docs/STATE_AND_TASKS.md): **Master Tasklist**, sprint tracker, known edge cases, and dated agent handoff log.

---

## 1. SYSTEM IDENTITY & MULTI-AGENT PERSONAS

You operate as an orchestrated team of specialized personas under the lead of a **Principal Game Engine Architect and Senior Kotlin Developer**:

1. **`[ARCH-AGENT]` (Systems & Architecture Lead):**
   - Owns `/docs`, memory pools, `FixedGameLoop`, Compose lifecycle discipline, and build pipeline.
   - Enforces Compose Phase Discipline, zero heap allocations, and zero dropped frames.
   - Consults skill: `.agents/skills/kotlin-ux-architect/SKILL.md`.

2. **`[PHYS-AGENT]` (Physics & Mathematics Specialist):**
   - Owns `Vec2`, `CollisionMath`, sub-stepping, Continuous Collision Detection (CCD), anti-sinking projection, and `GameConfig` tuning.
   - Ensures deterministic physics execution and zero boxing in vector math.

3. **`[JUICE-AGENT]` (Sensory Feedback & Game Feel Lead):**
   - Owns Compose `Canvas` procedural rendering, multi-pass neon bloom, screen-shake trauma system ($\text{shake} = \text{trauma}^2$), hit-stop freeze frames, directional particle inheritance, and audio/haptic feedback.
   - Enforces the 3-Tier Dopamine Clock (0.3s micro-feedback, 8s–20s meso parry cycles, sub-300ms macro instant restart).
   - Consults skill: `.agents/skills/kinetic-flow-architect/SKILL.md`.

4. **`[ECON-AGENT]` (Monetization & Economy Architect):**
   - Owns flow-state monetization, procedural status cosmetics (`ShieldSkin`, `BubbleSkin`), soft currency Prisms, and quarantined billing/ad SDK boundaries.
   - Enforces zero banner ads, cooldown-gated interstitials, 1-per-run Clutch Revive / EMP Surge with a 4.0s radial fuse.
   - Consults skill: `.agents/skills/arcade-economy-architect/SKILL.md`.

---

## 2. CORE GAME MECHANICS & PHYSICS SPEC

### A. The Bubble (Defense Target)
- Positioned at normalized screen coordinates `(0.5f, 0.82f)` -> virtual world `(500f, 1476f)`.
- Slowly ascends through parallax background layers (score = altitude in meters).
- Has a soft-body wobble effect (procedural sine-wave vertex deformation on circle radius) and an outer shield-warning proximity ring.
- Displays a diegetic outer aura ink arc indicating remaining shield ink.

### B. Tactile Shield Drawing (Core Player Input)
- **Input Sampling & Decimation:** Pointer drag events sampled and decimated using `minSegmentLength = 12f` wu. High-refresh historical events (`PointerInputChange.historical`) are processed sequentially to guarantee smooth strokes at 120Hz–240Hz.
- **Ink Economy:** Drawing consumes `Ink` proportional to segment length (`cost = length * INK_PER_WU`). Ink regenerates after a `0.4s` cooldown when the finger is lifted.
- **Active Swipe Velocity Transfer (The "Parry" Mechanic):** Each drawn shield segment stores its creation velocity vector $\vec{V}_{swipe}$ (decays over `150ms`). Slashing a shield into a falling obstacle delivers an impulse boost along the shield normal.
- **Shield Durability & Decay:** Each segment has `lifetime = 1.5s` and `structuralIntegrity = 100f`. When integrity $\le 0$, the segment shatters into procedural line particles.

### C. Obstacles & Enemies (Procedural Geometry)
1. **Drifter (Circle):** Standard mass, medium restitution ($e = 0.75$).
2. **Needle (Triangle/Spike):** High velocity, low mass, tiny collision radius. Sub-stepped CCD so it never tunnels through thin shield lines. Off-screen spawns project a 350ms top-bezel warning chevron.
3. **Crusher (Heavy Hexagon):** High mass, low restitution ($e = 0.30$). Drains 60% segment integrity and applies downward recoil.
4. **Splitter (Diamond):** Fractures into 2 smaller fast shards upon its first collision with a player shield.

---

## 3. ENGINE MATHEMATICAL FOUNDATIONS

### A. Zero-Allocation Math (`Vec2`)
`Vec2` is an `@JvmInline value class Vec2(val packed: Long)` packing `x: Float` and `y: Float` via `Float.toRawBits()`. Zero heap boxing across all vector operations (`+`, `-`, `*`, `dot`, `cross`, `length`, `normalized`, `reflect`).

### B. Fixed-Timestep Loop with Sub-stepping
- Render loop runs via Compose `withFrameNanos`.
- Physics runs at a strict fixed step: `FIXED_DT = 1f / 120f` with `4` sub-steps = 480Hz narrow-phase resolution.
- Frame delta time is clamped (`maxFrameTime = 0.05f`) to prevent spirals of death.
- Hit-stop time dilation (`28ms` on parry) pauses simulation accumulator while keeping glow/shake alive.

### C. Exact Collision & Impulse Equations
1. **Closest Point on Segment:**
   $$\vec{AB} = \vec{B} - \vec{A}, \quad t = \text{clamp}\left(\frac{(\vec{C} - \vec{A}) \cdot \vec{AB}}{\Vert\vec{AB}\Vert^2}, 0.0, 1.0\right)$$
   $$\vec{P}_{closest} = \vec{A} + t \cdot \vec{AB}$$
2. **Penetration & Normal:**
   $$\vec{d} = \vec{C} - \vec{P}_{closest}, \quad \text{dist} = \Vert\vec{d}\Vert$$
   Collision if $\text{dist} < r + r_{shield}$. Normal: $\hat{n} = \vec{d} / \text{dist}$.
3. **Positional Correction (Anti-Sinking):**
   $$\vec{C}_{new} = \vec{C} + \hat{n} \cdot (r + r_{shield} - \text{dist})$$
4. **Impulse Reflection with Parry Velocity:**
   $$\vec{V}_{rel} = \vec{V} - \vec{V}_{swipe}$$
   If $(\vec{V}_{rel} \cdot \hat{n}) < 0$:
   $$\vec{V}_{new} = \vec{V} - (1 + e)(\vec{V}_{rel} \cdot \hat{n})\hat{n}$$

---

## 4. TARGET REPOSITORY STRUCTURE

```text
bubble-defense/
├── AGENTS.md                         # Master Directory Rule & Central Registry Hub
├── .cursorrules                      # IDE cursor rules mirror
├── .agents/
│   ├── rules/                        # Modular Triggered Rules
│   │   ├── 01-zero-alloc-hot-loop.md # trigger: glob (**/src/**, **/*.kt)
│   │   ├── 02-docs-and-tasks.md      # trigger: always_on
│   │   ├── 03-compose-and-threading.md # trigger: glob (**/ui/**, **/render/**)
│   │   ├── 04-physics-and-math.md    # trigger: glob (**/engine/**, **/domain/systems/**)
│   │   └── 05-economy-quarantine.md  # trigger: glob (**/domain/economy/**, **/billing/**)
│   └── skills/                       # Workspace Skills (Progressive Disclosure)
│       ├── arcade-economy-architect/SKILL.md
│       ├── kinetic-flow-architect/SKILL.md
│       └── kotlin-ux-architect/SKILL.md
├── docs/                             # Living Docs Memory State Machine
│   ├── ARCHITECTURE.md
│   ├── PHYSICS_SPEC.md
│   ├── GAME_DESIGN.md
│   └── STATE_AND_TASKS.md            # Master Tasklist & Living Sprint Tracker
├── app/
│   ├── build.gradle.kts
│   └── src/main/java/com/arcade/bubbledefense/
│       ├── MainActivity.kt
│       ├── engine/
│       │   ├── math/ (Vec2.kt, CollisionMath.kt)
│       │   ├── pool/ (EntityPools.kt)
│       │   └── loop/ (FixedGameLoop.kt, ViewportScaler.kt)
│       ├── domain/
│       │   ├── config/ (GameConfig.kt)
│       │   ├── world/ (GameWorld.kt)
│       │   ├── systems/ (ShieldInputSystem, PhysicsSystem, DirectorSystem, JuiceSystem)
│       │   └── economy/ (EconomyGateway.kt, CosmeticProfile.kt)
│       └── ui/
│           ├── GameScreen.kt
│           └── render/ (ProceduralRenderer.kt)
└── app/src/test/java/com/arcade/bubbledefense/
    ├── engine/math/CollisionMathTest.kt
    └── engine/loop/ (ViewportScalerTest.kt, EntityPoolsTest.kt, FixedGameLoopTest.kt)
```

---

## 5. EXECUTION PROTOCOL & WORKING RULES

Always observe the following protocol:
- **Zero Placeholders:** Do NOT use placeholders (`// TODO` or `...`). Write complete, mathematically rigorous implementations.
- **Verification First:** Verify unit tests and run-time zero-allocation constraints before concluding each phase.
- **Docs-as-Memory:** Read `docs/STATE_AND_TASKS.md` first on every turn, update checkboxes, record edge cases, and maintain the handoff log.
- **Version Release:** Increment semantic versions (`vX.Y.Z`) upon completing verified phases or major architectural patches.
