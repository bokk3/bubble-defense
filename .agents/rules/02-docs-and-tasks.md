---
trigger: always_on
---

# Docs-as-Memory Architecture & Master Tasklist Discipline

The `/docs` directory is the single persistent source of truth and living memory state machine for Bubble Defense.

## 1. Master Tasklist Discipline (`docs/STATE_AND_TASKS.md`)
- Single source of truth for planning, sprint tracking, and known edge cases.
- **Protocol:** Every agent turn MUST read `docs/STATE_AND_TASKS.md` first.
- Mark active tasks as in-progress (`[/]`) before coding.
- Mark completed tasks as verified (`[x]`) upon test verification.
- Always record newly discovered edge cases and maintain the dated agent handoff log.
- Never write feature code without reading and updating the architectural specs in `/docs` first.

## 2. Semantic Versioning (SemVer 2.0.0)
- Strictly follow `MAJOR.MINOR.PATCH`:
  - `v0.1.0`: Phase 1 — Zero-Alloc Math Core & Documentation Specs.
  - `v0.2.0`: Phase 2 — Memory Pools, Virtual Viewport & Fixed Timestep World.
  - `v0.2.1`: Arch Patch — Modular Rules & Canonical Skills Refactoring.
  - `v0.3.0`: Phase 3 — Tactile Shield Input, Physics Solver & Procedural Renderer.
  - `v0.4.0`: Phase 4 — Wave Director, Juice & Flow Economy.
  - `v1.0.0`: Phase 5 — Production Release Candidate & 120Hz SysTrace Profiling.
- Document all version releases in the Change Log of `docs/STATE_AND_TASKS.md`.

## 3. Conventional Commits Protocol
- Commit and turn messages MUST follow:
  `<type>(<scope>): [<PERSONA>] <description> (vX.Y.Z)`
- Allowed types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `chore`.
- Personas: `[ARCH-AGENT]`, `[PHYS-AGENT]`, `[JUICE-AGENT]`, `[ECON-AGENT]`.
- All public code symbols must include rigorous KDocs specifying units (`wu`, `wu/s`, `rad`), coordinate system (+Y down), and zero-allocation guarantees.
