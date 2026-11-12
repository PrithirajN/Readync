package io.github.prithirajn.trackersync.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Picks a book cover's accent colour and turns it into the app's accent set, without a library.
 * Colours are ARGB ints; hue in degrees, saturation and lightness 0–1.
 */
object Palette {

    data class Hsl(val h: Float, val s: Float, val l: Float)

    /** The accent family for one theme: fill (bars, buttons), text on the page, tint, ring. */
    data class Accent(val accent: Int, val text: Int, val tint: Int, val ring: Int)

    fun toHsl(argb: Int): Hsl {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        if (mx == mn) return Hsl(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Hsl(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * ll - 1f)) * ss
        val hp = ((h % 360f + 360f) % 360f) / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0f)
            hp < 2 -> Triple(x, c, 0f)
            hp < 3 -> Triple(0f, c, x)
            hp < 4 -> Triple(0f, x, c)
            hp < 5 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = ll - c / 2f
        fun ch(v: Float) = Math.round((v + m) * 255f).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    /**
     * The cover's most vivid colour family: pixels are grouped by hue, weighted toward
     * saturated mid-tones, and the heaviest group's average wins. Null for a grey, black or
     * white cover, which keeps the app's own green.
     */
    fun vibrant(pixels: IntArray): Int? {
        val buckets = 24
        val weight = FloatArray(buckets)
        val sumH = FloatArray(buckets) // circular mean via sin/cos would be exact; within a 15° bucket a plain mean is fine
        val sumS = FloatArray(buckets)
        val sumL = FloatArray(buckets)
        var total = 0f
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            val hsl = toHsl(p)
            if (hsl.s < 0.22f || hsl.l < 0.12f || hsl.l > 0.9f) continue
            val w = hsl.s * hsl.s * (1f - abs(hsl.l - 0.5f) * 1.4f).coerceAtLeast(0.05f)
            val bIdx = ((hsl.h / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[bIdx] += w
            sumH[bIdx] += hsl.h * w
            sumS[bIdx] += hsl.s * w
            sumL[bIdx] += hsl.l * w
            total += w
        }
        // Too little colour on the cover to call it anything.
        if (total < pixels.size * 0.004f) return null
        var best = 0
        for (i in 1 until buckets) if (weight[i] > weight[best]) best = i
        val w = weight[best]
        if (w <= 0f) return null
        return fromHsl(sumH[best] / w, sumS[best] / w, sumL[best] / w)
    }

    /**
     * The app's accent set from a cover colour, at the same lightness the default green uses
     * in each theme, so text on it stays readable whatever the cover.
     */
    /**
     * A bright signal colour from a cover, for keys, lights and lit segments: the cover's hue made
     * vivid (as the device's orange is), dark enough for white writing on it. Null for a cover with
     * hardly any colour, which keeps the orange.
     */
    fun signalFrom(seed: Int, dark: Boolean): Accent? {
        val (h, s0, _) = toHsl(seed)
        if (s0 < 0.14f) return null
        val s = (s0 * 1.25f).coerceIn(0.62f, 0.92f)
        // Yellows and greens read light, so they sit a little darker to keep white writing clear.
        val l = when (h) {
            in 40f..175f -> if (dark) 0.5f else 0.44f
            else -> if (dark) 0.6f else 0.55f
        }
        return if (dark) Accent(
            accent = fromHsl(h, s, l),
            text = fromHsl(h, s * 0.9f, 0.72f),
            tint = fromHsl(h, s * 0.5f, 0.13f),
            ring = fromHsl(h, s * 0.6f, 0.3f),
        ) else Accent(
            accent = fromHsl(h, s, l),
            text = fromHsl(h, s * 0.9f, 0.36f),
            tint = fromHsl(h, s * 0.6f, 0.94f),
            ring = fromHsl(h, s * 0.6f, 0.78f),
        )
    }

    /** The colour a little deeper, for the ledge under a signal key. */
    fun deeper(argb: Int): Int { val (h, s, l) = toHsl(argb); return fromHsl(h, s, (l - 0.17f).coerceAtLeast(0.08f)) }

    fun accentFrom(seed: Int, dark: Boolean): Accent {
        val (h, s0, _) = toHsl(seed)
        val s = s0.coerceIn(0.32f, 0.72f)
        return if (dark) {
            Accent(
                accent = fromHsl(h, s * 0.8f, 0.46f),
                text = fromHsl(h, s * 0.65f, 0.74f),
                tint = fromHsl(h, s * 0.45f, 0.11f),
                ring = fromHsl(h, s * 0.5f, 0.25f),
            )
        } else {
            Accent(
                accent = fromHsl(h, s * 0.8f, 0.38f),
                text = fromHsl(h, s * 0.8f, 0.31f),
                tint = fromHsl(h, s * 0.5f, 0.955f),
                ring = fromHsl(h, s * 0.55f, 0.76f),
            )
        }
    }
}
