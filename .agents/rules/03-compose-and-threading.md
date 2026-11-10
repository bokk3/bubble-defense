---
trigger: glob
globs: "**/ui/**, **/render/**"
---

# Jetpack Compose 120Hz Phase Discipline & Recomposition Hygiene

All UI layers and rendering components must enforce strict Compose Phase Discipline to maintain locked 120Hz rendering and 0 dropped frames.

## 1. Phase Discipline (Defer State Reads)
- Never read fast-changing game state (such as bubble altitude, ink level, combo counts, or trauma shake offsets) during the **Composition Phase**.
- Defer state reads to the **Layout Phase** (`Modifier.offset { ... }`) or **Draw Phase** (`Modifier.drawWithContent { ... }` or `Canvas`).
- State models passed to UI composables must be annotated with `@Immutable` or `@Stable`.
- Use primitive lambdas (e.g., `inkSupplier: () -> Float`) instead of direct primitive state values when passing dynamic metrics to composables.

## 2. Tabular Numerals & HUD Stability
- All numerical HUD displays (Score, Altitude, Speed, Combo multiplier) MUST enforce tabular numerals via `FontFeatureSettings("tnum")` to prevent horizontal layout jank and relayout recalculations on digit changes.

## 3. 120Hz Render Loop Discipline
- Drive frame updates using `withFrameNanos` decoupled from the fixed physics timestep accumulator.
- Clamp frame deltas (`maxFrameTime = 0.05f`) to prevent frame death spirals on OS backgrounding or stutters.
- Hit-stop time dilation pauses simulation accumulation while keeping shader bloom, floating text, and screen shake alive.
