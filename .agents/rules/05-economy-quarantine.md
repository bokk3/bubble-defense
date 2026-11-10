---
trigger: glob
globs: "**/domain/economy/**, **/billing/**"
---

# Flow-State Monetization & SDK Quarantine Specification

Game economy and monetization must never interrupt player flow or compromise the sub-300ms instant retry loop.

## 1. Zero Native SDK Contamination
- Engine core, physics, and gameplay systems MUST NEVER import Google Play Billing, AppLovin, AdMob, or any external advertising/telemetry SDKs directly.
- All monetization interactions pass through a pure Kotlin abstraction interface: `EconomyGateway`.
- Core domain models operate with mockable, deterministically testable gateways.

## 2. Monetization Rules
- **ZERO Banner Ads:** Banners are completely forbidden. They reduce playable viewport and cause accidental touches during frantic swiping.
- **Cooldown-Gated Interstitials Only:** Never show interstitials on short runs (`runDuration < 45s`) or in the first 3 runs of a session. Minimum `180s` cumulative gameplay cooldown.
- **Clutch Revive / EMP Surge:**
  - Offered strictly once per run during peak emotional valley (death freeze).
  - 4.0s radial countdown fuse.
  - Clears all active obstacles with procedural shockwave, refills 100% ink, grants 2.5s invulnerability.
- **No Pay-To-Win:** Only monetize procedural visual cosmetics (`ShieldSkin`, `BubbleSkin`), progression pacing, and soft-currency Prisms.
