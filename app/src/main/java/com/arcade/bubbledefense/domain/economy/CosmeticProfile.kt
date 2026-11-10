package com.arcade.bubbledefense.domain.economy

/**
 * Procedural cosmetic skin profile for player shields.
 *
 * Implements the zero-asset cosmetic architecture specified in `arcade-economy-architect/SKILL.md`.
 * All visual variations are parameter presets rendered natively on Canvas with 0 asset overhead.
 */
data class ShieldSkin(
    val id: String,
    val name: String,
    val primaryColorArgb: Int,
    val glowColorArgb: Int,
    val sparkColorArgb: Int,
    val costPrisms: Int
) {
    companion object {
        val CYBER_CYAN = ShieldSkin(
            id = "shield_cyber_cyan",
            name = "Cyber Cyan",
            primaryColorArgb = 0xFF00F0FF.toInt(),
            glowColorArgb = 0x3300F0FF,
            sparkColorArgb = 0xFF00F0FF.toInt(),
            costPrisms = 0
        )

        val PLASMA_MAGENTA = ShieldSkin(
            id = "shield_plasma_magenta",
            name = "Plasma Magenta",
            primaryColorArgb = 0xFFFF0055.toInt(),
            glowColorArgb = 0x33FF0055,
            sparkColorArgb = 0xFFFF0055.toInt(),
            costPrisms = 150
        )

        val SOLAR_GOLD = ShieldSkin(
            id = "shield_solar_gold",
            name = "Solar Gold",
            primaryColorArgb = 0xFFFFB800.toInt(),
            glowColorArgb = 0x33FFB800,
            sparkColorArgb = 0xFFFFB800.toInt(),
            costPrisms = 300
        )

        val VOID_GLITCH = ShieldSkin(
            id = "shield_void_glitch",
            name = "Void Glitch",
            primaryColorArgb = 0xFF9D00FF.toInt(),
            glowColorArgb = 0x339D00FF,
            sparkColorArgb = 0xFF00FF99.toInt(),
            costPrisms = 600
        )

        val ALL_SHIELDS = listOf(CYBER_CYAN, PLASMA_MAGENTA, SOLAR_GOLD, VOID_GLITCH)

        fun findById(id: String): ShieldSkin =
            ALL_SHIELDS.firstOrNull { it.id == id } ?: CYBER_CYAN
    }
}

/**
 * Procedural cosmetic skin profile for the defense Bubble.
 */
data class BubbleSkin(
    val id: String,
    val name: String,
    val membraneColorArgb: Int,
    val auraColorArgb: Int,
    val costPrisms: Int
) {
    companion object {
        val CLASSIC_NEON = BubbleSkin(
            id = "bubble_classic_neon",
            name = "Classic Neon",
            membraneColorArgb = 0xFF00F0FF.toInt(),
            auraColorArgb = 0x2200F0FF,
            costPrisms = 0
        )

        val PULSING_SYNTH = BubbleSkin(
            id = "bubble_pulsing_synth",
            name = "Pulsing Synth",
            membraneColorArgb = 0xFFFF0055.toInt(),
            auraColorArgb = 0x22FF0055,
            costPrisms = 200
        )

        val QUANTUM_TESSERACT = BubbleSkin(
            id = "bubble_quantum_tesseract",
            name = "Quantum Tesseract",
            membraneColorArgb = 0xFF9D00FF.toInt(),
            auraColorArgb = 0x229D00FF,
            costPrisms = 500
        )

        val ALL_BUBBLES = listOf(CLASSIC_NEON, PULSING_SYNTH, QUANTUM_TESSERACT)

        fun findById(id: String): BubbleSkin =
            ALL_BUBBLES.firstOrNull { it.id == id } ?: CLASSIC_NEON
    }
}
