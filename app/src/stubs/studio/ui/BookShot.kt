package io.github.prithirajn.readync.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import io.github.prithirajn.readync.R
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * THE STAND-IN for the Book photo studio, which isn't part of the public code: the studio's
 * settings and scenes (so the rest of the app builds the same), with a plain card where the
 * official app draws the photo. The official Readync APK, from github.com/PrithirajN/readync,
 * has the real studio.
 */
object BookShot {

    enum class Format(val label: String, val w: Int, val h: Int) { STORY("Story", 1080, 1920), POST("Post", 1080, 1350), SQUARE("Square", 1080, 1080) }
    enum class Book(val label: String) { OPEN("Open book"), PAGE("Single page"), CARD("Index card"), DATAPAD("Datapad") }
    enum class Side(val label: String) { LEFT("Left page"), RIGHT("Right page") }
    enum class Light(val label: String) {
        NONE("Open sky"), SUNBEAM("Sunbeam"), BLINDS("Blinds"), WINDOW("Window"), LEAVES("Leaves"), LAMP("Lamp"),
        CANDLE("Candle"), NEON("Neon signs"), MOON("Moonlight"), HOLO("Hologram"),
    }
    /** The light's colour: its own (sun, lamp, candle…), or a tint of your choosing. */
    enum class Tint(val label: String, val color: Int?) {
        AUTO("Natural", null), AMBER("Amber", 0xFFFFB45A.toInt()), ICE("Ice", 0xFFA8D8FF.toInt()), EMERALD("Emerald", 0xFF7DFFB0.toInt()),
        VIOLET("Violet", 0xFFC9A0FF.toInt()), CRIMSON("Crimson", 0xFFFF6A5A.toInt()), CYAN("Cyan", 0xFF5CF2FF.toInt()),
    }
    /** What's in the air, caught by the light. */
    enum class Air(val label: String) { NONE("Still"), DUST("Dust"), EMBERS("Embers"), MAGIC("Sparkles"), SNOW("Snow"), RAIN("Rain on glass"), STARS("Starfield") }
    enum class Angle(val label: String, val tilt: Float) { OVERHEAD("From above", 3f), SLIGHT("Leaning in", 20f), LOW("Low and close", 38f) }
    enum class Paper(val label: String, val color: Int) {
        WHITE("White", 0xFFF6F3EA.toInt()), CREAM("Cream", 0xFFF2E8D3.toInt()), IVORY("Ivory", 0xFFEEE4CB.toInt()),
        AGED("Aged", 0xFFE4D2AC.toInt()), PULP("Pulp", 0xFFE8DFCB.toInt()), RECYCLED("Recycled", 0xFFDEDACF.toInt()),
        PARCHMENT("Parchment", 0xFFE0C893.toInt()), DARK("Black page", 0xFF16171B.toInt()),
    }
    enum class Ink(val label: String, val color: Int) { BLACK("Black", 0xFF1C1915.toInt()), SEPIA("Sepia", 0xFF3A2A1D.toInt()), NAVY("Navy", 0xFF1D2638.toInt()), BLOOD("Blood red", 0xFF5E1010.toInt()), SILVER("Silver", 0xFFD9DEE6.toInt()), GOLD("Gold", 0xFFE3B65A.toInt()) }
    enum class Mark(val label: String) { HIGHLIGHTER("Highlighter"), PEN("Pen"), PENCIL("Pencil"), NONE("No mark") }
    enum class MarkColor(val label: String, val color: Int) {
        YELLOW("Yellow", 0xFFFFE15A.toInt()), PINK("Pink", 0xFFFF8EB6.toInt()), GREEN("Green", 0xFFA6E86E.toInt()),
        ORANGE("Orange", 0xFFFFB357.toInt()), BLUE("Blue", 0xFF8DCBFF.toInt()), PURPLE("Purple", 0xFFC7A4FF.toInt()),
        RED("Crimson", 0xFFFF5A5A.toInt()), CYAN("Neon cyan", 0xFF5CF2FF.toInt()), GOLD("Gold", 0xFFFFC94A.toInt()),
    }
    enum class Desk(val label: String) {
        OAK("Oak"), HONEY("Honey"), WALNUT("Walnut"), DARK("Dark wood"), STONE("Stone"), LEATHER("Leather"), METAL("Brushed steel"),
        MAP("Old map"), SAND("Desert sand"), SPACE("Deep space"), LINEN("Linen"), BEDSHEET("Bedsheet"), PLAIN("Plain"), PHOTO("Your photo"),
    }
    enum class Mode(val label: String, val hint: String) {
        CONTEXT("In context", "Your passage where it sits on the page, among the words around it."),
        APART("Set apart", "Your passage on its own, with the page's text above and below it."),
        QUOTE("Quote only", "Just the passage, alone on a quiet page."),
    }
    enum class Face(val label: String, val res: Int, val italicRes: Int?, val scale: Float) {
        CRIMSON("Crimson", R.font.book_crimson, R.font.book_crimson_italic, 1.08f),
        BASKERVILLE("Baskerville", R.font.book_baskerville, null, 0.92f),
        LORA("Lora", R.font.lora, R.font.book_lora_italic, 0.95f),
        PLEX("Plex Serif", R.font.book_plex, R.font.book_plex_italic, 0.9f),
        CALADEA("Caladea", R.font.book_caladea, R.font.book_caladea_italic, 1.0f),
        INSTRUMENT("Instrument", R.font.book_instrument, R.font.book_instrument_italic, 1.12f),
        SAGA("Saga", R.font.book_youngserif, null, 0.88f),
        FUTURE("Future", R.font.book_jura, null, 0.98f),
        STARSHIP("Starship", R.font.book_tektur, null, 0.9f),
        TERMINAL("Terminal", R.font.book_plexmono, null, 0.82f),
        SYSTEM("System", R.font.book_pixelify, null, 0.95f),
    }

    data class Options(
        val format: Format = Format.POST,
        val book: Book = Book.OPEN,
        val side: Side = Side.RIGHT,
        val light: Light = Light.BLINDS,
        val strength: Float = 0.7f,
        val softness: Float = 0.5f,
        val warmth: Float = 0.7f,
        /** Which way the light falls, in degrees. */
        val lightAngle: Float = -30f,
        /** Where the light lands, -1 (one side) to 1 (the other). */
        val lightPos: Float = 0.1f,
        /** The time of day the light is set to (0 dawn, 0.5 noon, 1 night), when chosen that way. */
        val dayTime: Float = 0.3f,
        val angle: Angle = Angle.SLIGHT,
        val zoom: Float = 1f,
        val rotate: Float = 1.5f,
        val focus: Float = 0.5f,
        val paper: Paper = Paper.CREAM,
        val grain: Float = 0.5f,
        val show: Float = 0.5f,
        val mode: Mode = Mode.CONTEXT,
        val face: Face = Face.CRIMSON,
        val size: Float = 1f,
        val spacing: Float = 1.45f,
        val ink: Ink = Ink.BLACK,
        val italic: Boolean = false,
        val mark: Mark = Mark.HIGHLIGHTER,
        val markColor: MarkColor = MarkColor.YELLOW,
        val desk: Desk = Desk.OAK,
        val photoBlur: Float = 0.3f,
        /** The photo's own grain, as a camera's sensor or film gives it. */
        val filmGrain: Float = 0.5f,
        val tint: Tint = Tint.AUTO,
        val air: Air = Air.NONE,
        /** Moving the camera over the page, as a fraction of the book (not kept between shares). */
        val panX: Float = 0f,
        val panY: Float = 0f,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("format", format.name).put("book", book.name).put("side", side.name).put("light", light.name)
            .put("strength", strength.toDouble()).put("softness", softness.toDouble()).put("warmth", warmth.toDouble())
            .put("lightAngle", lightAngle.toDouble()).put("lightPos", lightPos.toDouble()).put("dayTime", dayTime.toDouble())
            .put("angle", angle.name).put("zoom", zoom.toDouble()).put("rotate", rotate.toDouble()).put("focus", focus.toDouble())
            .put("paper", paper.name).put("grain", grain.toDouble()).put("show", show.toDouble()).put("mode", mode.name)
            .put("face", face.name).put("size", size.toDouble()).put("spacing", spacing.toDouble()).put("ink", ink.name)
            .put("italic", italic).put("mark", mark.name).put("markColor", markColor.name).put("desk", desk.name)
            .put("photoBlur", photoBlur.toDouble()).put("filmGrain", filmGrain.toDouble()).put("tint", tint.name).put("air", air.name)

        companion object {
            private inline fun <reified E : Enum<E>> JSONObject.e(k: String, d: E): E =
                optString(k).takeIf { it.isNotEmpty() }?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: d
            private fun JSONObject.f(k: String, d: Float): Float = if (has(k)) optDouble(k, d.toDouble()).toFloat() else d

            fun fromJson(o: JSONObject?): Options {
                val d = Options()
                if (o == null) return d
                return Options(
                    format = o.e("format", d.format), book = o.e("book", d.book), side = o.e("side", d.side), light = o.e("light", d.light),
                    strength = o.f("strength", d.strength), softness = o.f("softness", d.softness), warmth = o.f("warmth", d.warmth),
                    lightAngle = o.f("lightAngle", d.lightAngle), lightPos = o.f("lightPos", d.lightPos), dayTime = o.f("dayTime", d.dayTime),
                    angle = o.e("angle", d.angle), zoom = o.f("zoom", d.zoom), rotate = o.f("rotate", d.rotate), focus = o.f("focus", d.focus),
                    paper = o.e("paper", d.paper), grain = o.f("grain", d.grain), show = o.f("show", d.show), mode = o.e("mode", d.mode),
                    face = o.e("face", d.face), size = o.f("size", d.size), spacing = o.f("spacing", d.spacing), ink = o.e("ink", d.ink),
                    italic = o.optBoolean("italic", d.italic), mark = o.e("mark", d.mark), markColor = o.e("markColor", d.markColor),
                    desk = o.e("desk", d.desk), photoBlur = o.f("photoBlur", d.photoBlur),
                    filmGrain = o.f("filmGrain", d.filmGrain), tint = o.e("tint", d.tint), air = o.e("air", d.air),
                )
            }
        }
    }

    data class Content(
        val text: String,
        val author: String?,
        val title: String,
        /** For the running head on the passage's page. */
        val chapter: String?,
        /** The passage's page number, when known. */
        val page: Int?,
        /** Your other highlights from the same book, in book order: the page's text when its own isn't known. */
        val others: List<String> = emptyList(),
        /** Your note, pencilled in the margin when given. */
        val note: String? = null,
        /** The book's cover colour, for the boards around an open book. */
        val coverColor: Int? = null,
        /** The real text of the page before and after the passage, read off a photo of it. */
        val before: String? = null,
        val after: String? = null,
        /** The book's corners of speculative fiction (ORBIT, CITADEL, SPIRE, VOID, CHRONOS), for suggested moods. */
        val sectors: Set<String> = emptySet(),
        /** A note on its own, with no passage: [text] is the note. */
        val standalone: Boolean = false,
    ) {
        val hasPage: Boolean get() = !before.isNullOrBlank() || !after.isNullOrBlank()
    }

    /** A stroke drawn by hand on the page, in the page picture's own pixels (so it bends with the page). */
    data class Stroke(val tool: Mark, val color: Int, val points: List<Float>)

    // ---- moods: a whole scene in one tap ----

    /** A whole scene in one tap; [sectors] are the kinds of book it suits; [everyday] ones aren't genre scenes. */
    data class Mood(val name: String, val from: Int, val to: Int, val sectors: Set<String> = emptySet(), val everyday: Boolean = false, val apply: (Options) -> Options)

    private fun Options.scene(light: Light, desk: Desk, paper: Paper, air: Air = Air.NONE, tint: Tint = Tint.AUTO, strength: Float = 0.75f, softness: Float = 0.55f, warmth: Float = 0.7f, angle: Float = lightAngle, pos: Float = lightPos) =
        copy(light = light, desk = desk, paper = paper, air = air, tint = tint, strength = strength, softness = softness, warmth = warmth, lightAngle = angle, lightPos = pos,
            book = if (book == Book.DATAPAD) Book.OPEN else book, ink = if (ink == Ink.SILVER) Ink.BLACK else ink)

    val MOODS = listOf(
        // Fantasy
        Mood("Wizard's study", 0xFFFFB347.toInt(), 0xFF3B2416.toInt(), setOf("CITADEL", "SPIRE")) { it.scene(Light.CANDLE, Desk.LEATHER, Paper.PARCHMENT, Air.MAGIC, strength = 0.85f, warmth = 0.9f, pos = -0.3f).copy(markColor = MarkColor.GOLD) },
        Mood("Dragon's hoard", 0xFFFFC94A.toInt(), 0xFF5A1A0A.toInt(), setOf("CITADEL")) { it.scene(Light.CANDLE, Desk.STONE, Paper.AGED, Air.EMBERS, Tint.AMBER, strength = 0.9f, warmth = 1f, pos = 0.4f).copy(markColor = MarkColor.GOLD) },
        Mood("Elven glade", 0xFFC8F0B4.toInt(), 0xFF2E4A2A.toInt(), setOf("CITADEL")) { it.scene(Light.LEAVES, Desk.OAK, Paper.IVORY, Air.DUST, Tint.EMERALD, strength = 0.7f, softness = 0.6f, warmth = 0.4f, angle = -20f).copy(markColor = MarkColor.GREEN) },
        Mood("Grimdark keep", 0xFF8A9099.toInt(), 0xFF1A1A1E.toInt(), setOf("CITADEL", "VOID")) { it.scene(Light.MOON, Desk.STONE, Paper.AGED, Air.EMBERS, strength = 0.9f, softness = 0.7f, warmth = 0.1f, angle = 12f).copy(markColor = MarkColor.RED, focus = 0.6f) },
        Mood("Frozen north", 0xFFE8F4FF.toInt(), 0xFF5A7088.toInt(), setOf("CITADEL", "VOID")) { it.scene(Light.WINDOW, Desk.STONE, Paper.WHITE, Air.SNOW, Tint.ICE, strength = 0.65f, softness = 0.7f, warmth = 0.1f, angle = 6f).copy(markColor = MarkColor.BLUE) },
        // Progression and LitRPG
        Mood("Dungeon crawl", 0xFFFF8A3C.toInt(), 0xFF141414.toInt(), setOf("SPIRE")) { it.scene(Light.CANDLE, Desk.STONE, Paper.CREAM, Air.EMBERS, strength = 0.95f, softness = 0.5f, warmth = 0.95f, pos = 0.6f).copy(markColor = MarkColor.ORANGE) },
        Mood("System window", 0xFF5CF2FF.toInt(), 0xFF0A1830.toInt(), setOf("SPIRE")) { it.scene(Light.HOLO, Desk.METAL, Paper.DARK, Air.MAGIC, Tint.CYAN, strength = 0.8f).copy(book = Book.DATAPAD, face = Face.SYSTEM, ink = Ink.SILVER, markColor = MarkColor.CYAN, mark = Mark.HIGHLIGHTER) },
        // Science fiction
        Mood("Starship bridge", 0xFF7FD4FF.toInt(), 0xFF101A28.toInt(), setOf("ORBIT", "CHRONOS")) { it.scene(Light.HOLO, Desk.METAL, Paper.DARK, Air.STARS, Tint.ICE, strength = 0.75f).copy(book = Book.DATAPAD, face = Face.FUTURE, ink = Ink.SILVER, markColor = MarkColor.CYAN) },
        Mood("Neon city", 0xFFFF4FD8.toInt(), 0xFF2A0A40.toInt(), setOf("ORBIT", "CHRONOS")) { it.scene(Light.NEON, Desk.METAL, Paper.WHITE, Air.RAIN, strength = 0.85f, softness = 0.6f).copy(markColor = MarkColor.PINK, focus = 0.6f) },
        Mood("Deep space", 0xFF3A2A6E.toInt(), 0xFF05060C.toInt(), setOf("ORBIT")) { it.scene(Light.MOON, Desk.SPACE, Paper.WHITE, Air.STARS, Tint.VIOLET, strength = 0.6f, softness = 0.8f, warmth = 0.2f).copy(markColor = MarkColor.PURPLE) },
        Mood("Desert planet", 0xFFFFB066.toInt(), 0xFF8A4A1E.toInt(), setOf("ORBIT", "CHRONOS")) { it.scene(Light.SUNBEAM, Desk.SAND, Paper.AGED, Air.DUST, strength = 0.95f, softness = 0.35f, warmth = 1f, angle = -55f).copy(markColor = MarkColor.ORANGE) },
        // Horror and the weird
        Mood("Eldritch", 0xFF7DFFB0.toInt(), 0xFF0C1A12.toInt(), setOf("VOID")) { it.scene(Light.WINDOW, Desk.DARK, Paper.AGED, Air.DUST, Tint.EMERALD, strength = 0.85f, softness = 0.8f, warmth = 0.2f, angle = 20f).copy(markColor = MarkColor.GREEN, focus = 0.7f) },
        Mood("Wasteland", 0xFFD9A45A.toInt(), 0xFF3A2E22.toInt(), setOf("CHRONOS")) { it.scene(Light.SUNBEAM, Desk.SAND, Paper.RECYCLED, Air.DUST, Tint.AMBER, strength = 0.8f, softness = 0.6f, warmth = 0.8f, angle = 35f).copy(markColor = MarkColor.RED) },
        // Everyday
        Mood("Morning blinds", 0xFFF6E7C1.toInt(), 0xFFB88D60.toInt(), everyday = true) { it.scene(Light.BLINDS, Desk.OAK, Paper.CREAM, strength = 0.7f, softness = 0.45f, warmth = 0.45f, angle = -30f, pos = 0.1f).copy(dayTime = 0.25f) },
        Mood("Golden hour", 0xFFFFC46B.toInt(), 0xFF94612D.toInt(), everyday = true) { it.scene(Light.SUNBEAM, Desk.HONEY, Paper.IVORY, strength = 0.85f, softness = 0.5f, warmth = 0.95f, angle = -45f, pos = 0f).copy(dayTime = 0.82f) },
        Mood("Reading lamp", 0xFFFFB347.toInt(), 0xFF1C140F.toInt(), everyday = true) { it.scene(Light.LAMP, Desk.DARK, Paper.CREAM, strength = 0.85f, softness = 0.6f, warmth = 0.9f, pos = -0.4f).copy(dayTime = 0.97f) },
        Mood("Rainy window", 0xFFBFCAD3.toInt(), 0xFF3D2618.toInt(), everyday = true) { it.scene(Light.WINDOW, Desk.WALNUT, Paper.IVORY, Air.RAIN, strength = 0.55f, softness = 0.85f, warmth = 0.15f, angle = 8f) },
        Mood("Lazy Sunday", 0xFFF4F1EA.toInt(), 0xFFCFC8BC.toInt(), everyday = true) { it.scene(Light.WINDOW, Desk.BEDSHEET, Paper.WHITE, strength = 0.5f, softness = 0.7f, warmth = 0.5f, angle = -15f).copy(angle = Angle.OVERHEAD) },
        Mood("Clean", 0xFFF6F3EA.toInt(), 0xFF2B2622.toInt(), everyday = true) { it.scene(Light.NONE, Desk.PLAIN, Paper.WHITE).copy(focus = 0.15f, grain = 0.3f) },
    )

    /** The book's kinds of speculative fiction (by its genres, tags and description), for suggesting moods. */
    fun sectorsFor(s: io.github.prithirajn.readync.data.Snapshot, book: io.github.prithirajn.readync.data.Book?): Set<String> =
        book?.let { b -> runCatching { io.github.prithirajn.readync.sync.SffChart.sectorsOfBook(b, s.facts[b.document], s.webNovels.containsKey(b.document)).map { it.id }.toSet() }.getOrNull() } ?: emptySet()

    /** Moods for this book first (by its kind of speculative fiction), then the other genre scenes, then everyday ones. */
    fun moodsFor(sectors: Set<String>): List<Mood> {
        val suited = MOODS.filter { m -> m.sectors.any { it in sectors } }
        return suited + MOODS.filter { it !in suited && !it.everyday } + MOODS.filter { it.everyday }
    }

    /** The light at a time of day: low and warm at dawn and dusk, high and neutral at noon, a lamp at night. */
    fun atTime(o: Options, t: Float): Options {
        val tt = t.coerceIn(0f, 1f)
        if (tt > 0.9f) return o.copy(dayTime = tt, light = Light.LAMP, warmth = 0.9f, strength = 0.8f)
        val sunUp = sin(PI.toFloat() * (tt / 0.9f))
        return o.copy(
            dayTime = tt,
            light = if (o.light == Light.LAMP) Light.BLINDS else o.light,
            lightAngle = -70f + 140f * (tt / 0.9f),
            warmth = (1f - 0.65f * sunUp).coerceIn(0f, 1f),
            strength = (0.5f + 0.4f * sunUp).coerceIn(0f, 1f),
            softness = (0.75f - 0.35f * sunUp).coerceIn(0f, 1f),
        )
    }

    /** A different scene each time: light, surface, paper, angle and mark. */
    fun surprise(o: Options, rnd: java.util.Random = java.util.Random()): Options {
        fun <T> pick(list: List<T>) = list[rnd.nextInt(list.size)]
        return o.copy(
            light = pick(Light.entries.filter { it != Light.NONE }),
            strength = 0.5f + rnd.nextFloat() * 0.4f, softness = 0.3f + rnd.nextFloat() * 0.6f, warmth = rnd.nextFloat(),
            lightAngle = -70f + rnd.nextFloat() * 140f, lightPos = rnd.nextFloat() * 1.2f - 0.6f,
            desk = pick(Desk.entries.filter { it != Desk.PHOTO }), paper = pick(Paper.entries.filter { it != Paper.DARK }),
            air = pick(Air.entries), tint = if (rnd.nextInt(3) == 0) pick(Tint.entries) else Tint.AUTO,
            angle = pick(Angle.entries), rotate = rnd.nextFloat() * 10f - 5f, focus = 0.2f + rnd.nextFloat() * 0.6f,
            markColor = pick(MarkColor.entries), panX = 0f, panY = 0f,
        )
    }

    /**
     * The page's other text when its own isn't known: your other highlights from the same book (as
     * edited), the nearest to this one first, then put back in book order.
     */
    fun othersFor(s: io.github.prithirajn.readync.data.Snapshot, clip: io.github.prithirajn.readync.source.Clipping): List<String> {
        val p = clip.percent
        return io.github.prithirajn.readync.sync.Highlights.forBook(s, clip.document)
            .map { it.clip }
            .filter { it.id != clip.id && it.text.trim().length > 20 }
            .sortedBy { o -> if (p != null && o.percent != null) abs(o.percent - p) else 2.0 }
            .take(12)
            .sortedBy { it.percent ?: 2.0 }
            .map { it.text.trim() }
    }

    // ---- remembering your choices ----

    private fun prefs(context: Context) = context.getSharedPreferences("bookshot", Context.MODE_PRIVATE)

    fun saved(context: Context): Options = runCatching {
        Options.fromJson(prefs(context).getString("options", null)?.let { JSONObject(it) })
    }.getOrDefault(Options())

    fun save(context: Context, o: Options) {
        runCatching { prefs(context).edit().putString("options", o.toJson().toString()).apply() }
    }

    /** Whether shared pictures carry the small "Tracker Sync" mark (off unless you turn it on). */
    fun markOn(context: Context): Boolean = runCatching { prefs(context).getBoolean("mark", false) }.getOrDefault(false)

    fun setMark(context: Context, on: Boolean) {
        runCatching { prefs(context).edit().putBoolean("mark", on).apply() }
    }

    // ---- the stand-in's picture ----

    /** Not in this build: the stand-in can't put touches on a page. */
    fun pageAt(@Suppress("UNUSED_PARAMETER") x: Float, @Suppress("UNUSED_PARAMETER") y: Float): FloatArray? = null

    /** A plain card with the passage, saying the Book photo studio isn't in this build. */
    @Suppress("UNUSED_PARAMETER")
    fun render(context: Context?, c: Content, o: Options, photo: android.graphics.Bitmap? = null, strokes: List<Stroke> = emptyList(), mark: Boolean = false): android.graphics.Bitmap {
        val w = o.format.w; val h = o.format.h
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(0xFFF2E8D3.toInt())
        val text = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 46f; color = 0xFF1C1915.toInt(); typeface = android.graphics.Typeface.SERIF }
        val quote = "\u201C" + c.text.trim() + "\u201D\n\n\u2014 " + c.title
        val layout = android.text.StaticLayout.Builder.obtain(quote, 0, quote.length, text, w - 200).setLineSpacing(0f, 1.3f).build()
        cv.save(); cv.translate(100f, ((h - layout.height) / 2f).coerceAtLeast(100f)); layout.draw(cv); cv.restore()
        val note = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 26f; color = 0x991C1915.toInt(); textAlign = android.graphics.Paint.Align.CENTER }
        cv.drawText("The Book photo studio is in the official Readync app", w / 2f, h - 70f, note)
        return bmp
    }

    fun png(context: Context?, c: Content, o: Options, mark: Boolean = false): ByteArray {
        val bmp = render(context, c, o, mark = mark)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
