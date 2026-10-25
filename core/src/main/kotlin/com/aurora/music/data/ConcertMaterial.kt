package com.aurora.music.data

// random-incidence absorption at 125, 250, 500, 1k, 2k, 4k and 8k hz (textbook values, 8k extrapolated)
enum class ConcertMaterial(vararg alpha: Float) {
    CONCRETE(0.01f, 0.01f, 0.015f, 0.02f, 0.02f, 0.02f, 0.03f),
    BRICK(0.03f, 0.03f, 0.03f, 0.04f, 0.05f, 0.07f, 0.08f),
    PLASTER(0.01f, 0.02f, 0.02f, 0.03f, 0.04f, 0.05f, 0.05f),
    STONE(0.02f, 0.02f, 0.03f, 0.04f, 0.05f, 0.05f, 0.06f),
    GLASS(0.35f, 0.25f, 0.18f, 0.12f, 0.07f, 0.04f, 0.03f),
    WOOD_FLOOR(0.15f, 0.11f, 0.10f, 0.07f, 0.06f, 0.07f, 0.07f),
    // panelling and drapes, what most music halls hang on their walls
    PANELLED(0.20f, 0.30f, 0.35f, 0.40f, 0.40f, 0.38f, 0.35f),
    // bass traps, rockwool and velour, a properly treated club
    TREATED(0.15f, 0.40f, 0.60f, 0.75f, 0.75f, 0.70f, 0.65f),
    METAL_ROOF(0.15f, 0.10f, 0.07f, 0.05f, 0.05f, 0.05f, 0.05f),
    // perforated deck over insulation, the usual arena roof
    ARENA_ROOF(0.30f, 0.55f, 0.75f, 0.80f, 0.75f, 0.70f, 0.65f),
    MEMBRANE(0.10f, 0.12f, 0.15f, 0.18f, 0.20f, 0.20f, 0.20f),
    PLASTIC_SEATS(0.07f, 0.10f, 0.12f, 0.14f, 0.16f, 0.18f, 0.18f),
    UPHOLSTERED_SEATS(0.44f, 0.60f, 0.77f, 0.89f, 0.82f, 0.70f, 0.65f),
    PEWS(0.10f, 0.12f, 0.12f, 0.12f, 0.12f, 0.12f, 0.12f),
    STONE_SEATS(0.02f, 0.03f, 0.03f, 0.04f, 0.05f, 0.05f, 0.06f),
    GRASS(0.11f, 0.26f, 0.60f, 0.69f, 0.92f, 0.99f, 0.99f),
    AUDIENCE(0.45f, 0.65f, 0.85f, 0.95f, 0.95f, 0.90f, 0.85f),
    OPEN(1f, 1f, 1f, 1f, 1f, 1f, 1f);

    val alpha: FloatArray = alpha

    companion object {
        val BANDS_HZ = doubleArrayOf(125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
    }
}
