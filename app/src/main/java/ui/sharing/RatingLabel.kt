package io.github.prithirajn.readync.ui

/** A rating as written beside its stars on pictures: "4", "4.5", "3.75". */
fun ratingLabel(r: Double): String =
    if (r % 1.0 == 0.0) r.toInt().toString() else String.format(java.util.Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')

/** Stars with a half where there is one, then the exact number: "★★★★½ 4.5". */
fun starsWithNumber(r: Double): String {
    val halves = Math.round(r * 2).toInt().coerceIn(0, 10)
    return "★".repeat(halves / 2) + (if (halves % 2 == 1) "½" else "") + "  " + ratingLabel(r)
}
