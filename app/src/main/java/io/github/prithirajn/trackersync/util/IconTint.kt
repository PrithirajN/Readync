package io.github.prithirajn.trackersync.util

/**
 * The colours the app's icon comes in. Android can't recolour an icon on the fly, so the app
 * ships one icon per colour and turns on the one closest to the book you're reading.
 */
object IconTint {

    /** Key, ARGB colour, and the hue it stands for (null for the grey one). */
    data class Tint(val key: String, val argb: Int, val hue: Float?)

    val all = listOf(
        Tint("green", 0xFF4A7A62.toInt(), 145f),
        Tint("teal", 0xFF2E7C7A.toInt(), 178f),
        Tint("blue", 0xFF2F5F9E.toInt(), 214f),
        Tint("indigo", 0xFF4B4F9C.toInt(), 238f),
        Tint("purple", 0xFF6D4A93.toInt(), 270f),
        Tint("rose", 0xFFA34A6B.toInt(), 338f),
        Tint("red", 0xFFA8433F.toInt(), 3f),
        Tint("orange", 0xFFB5652B.toInt(), 25f),
        Tint("amber", 0xFF9C7A1E.toInt(), 44f),
        Tint("slate", 0xFF4E5A63.toInt(), null),
    )

    const val DEFAULT = "green"

    /** The icon colour nearest [seed] (a cover's colour): by hue, or slate for greys. */
    fun nearest(seed: Int?): String {
        if (seed == null) return DEFAULT
        val r = (seed shr 16 and 0xFF) / 255f
        val g = (seed shr 8 and 0xFF) / 255f
        val b = (seed and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        val sat = if (max == 0f) 0f else delta / max
        if (sat < 0.18f || delta < 0.08f) return "slate"
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0) it + 360f else it }
        return all.filter { it.hue != null }.minBy { t ->
            val d = kotlin.math.abs(t.hue!! - hue)
            minOf(d, 360f - d)
        }.key
    }
}
