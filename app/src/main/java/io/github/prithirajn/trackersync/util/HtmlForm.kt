package io.github.prithirajn.trackersync.util

import io.github.prithirajn.trackersync.source.BookDetails

/**
 * A site's own HTML form, read from its page and filled in by field name, so a book can be
 * added the way you would by hand without knowing every field in advance. Hidden fields (the
 * CSRF token and the like) and defaults are kept; fields that match nothing are left as they are.
 */
class HtmlForm private constructor(
    val action: String?,
    val method: String,
    /** In page order: name -> value. Repeated names (checkbox groups) keep their first value. */
    val fields: LinkedHashMap<String, String>,
    /** Select fields: name -> (value, label) options. */
    val options: Map<String, List<Pair<String, String>>>,
    val checkboxes: Set<String>,
    /** File inputs (a cover upload), by name. */
    val files: List<String> = emptyList(),
    /** Hidden fields behind a rich-text box (Trix): they hold HTML, not plain text. */
    val richText: Set<String> = emptySet(),
) {
    companion object {
        private val formRe = Regex("<form\\b([^>]*)>(.*?)</form>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private fun attr(tag: String, name: String): String? =
            Regex("(?<![\\w-])$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)?.let { m ->
                Html.decode(m.groupValues[2].ifEmpty { m.groupValues[3] })
            }

        /** The first form on the page with a field whose name matches [marker] (e.g. "title"). */
        fun find(html: String, marker: Regex): HtmlForm? {
            for (m in formRe.findAll(html)) {
                val f = parse(m.groupValues[1], m.groupValues[2])
                if (f.fields.keys.any { marker.containsMatchIn(it) }) return f
            }
            return null
        }

        private fun parse(formTag: String, body: String): HtmlForm {
            val fields = LinkedHashMap<String, String>()
            val options = HashMap<String, List<Pair<String, String>>>()
            val boxes = HashSet<String>()
            val files = ArrayList<String>()
            for (m in Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body)) {
                val tag = m.value
                val name = attr(tag, "name") ?: continue
                val type = attr(tag, "type")?.lowercase() ?: "text"
                if (type == "file") { files += name; continue }
                if (type in setOf("submit", "button", "image", "reset")) continue
                if (type == "checkbox" || type == "radio") {
                    boxes += name
                    val checked = Regex("\\bchecked\\b", RegexOption.IGNORE_CASE).containsMatchIn(tag)
                    if (checked) fields[name] = attr(tag, "value") ?: "on"
                    else if (type == "checkbox" && name !in fields) Unit
                    continue
                }
                if (name !in fields) fields[name] = attr(tag, "value") ?: ""
            }
            for (m in Regex("<textarea\\b([^>]*)>(.*?)</textarea>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(body)) {
                val name = attr(m.groupValues[1], "name") ?: continue
                fields[name] = Html.decode(m.groupValues[2])
            }
            for (m in Regex("<select\\b([^>]*)>(.*?)</select>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(body)) {
                val name = attr(m.groupValues[1], "name") ?: continue
                val opts = Regex("<option\\b([^>]*)>(.*?)</option>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).findAll(m.groupValues[2]).map { o ->
                    val label = Html.decode(o.groupValues[2].replace(Regex("<[^>]+>"), "")).trim()
                    (attr(o.groupValues[1], "value") ?: label) to label
                }.toList()
                options[name] = opts
                val selected = Regex("<option\\b([^>]*\\bselected\\b[^>]*)>", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.let { attr(it.groupValues[1], "value") }
                fields[name] = selected ?: opts.firstOrNull()?.first ?: ""
            }
            // A rich-text box (<trix-editor input="x">) keeps its text, as HTML, in the hidden field with id x.
            val idToName = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).mapNotNull { m ->
                val id = attr(m.value, "id") ?: return@mapNotNull null
                val name = attr(m.value, "name") ?: return@mapNotNull null
                id to name
            }.toMap()
            val rich = Regex("<trix-editor\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(body).mapNotNull { m -> attr(m.value, "input")?.let { idToName[it] } }.toSet()
            val act = attr(formTag, "action")?.trim()?.takeIf { it.startsWith("/") || it.startsWith("http://") || it.startsWith("https://") }
            return HtmlForm(act, (attr(formTag, "method") ?: "post").uppercase(), fields, options, boxes, files, rich)
        }
    }

    /** Sets the first field whose name matches [name] (and not [not]) to [value]; a select takes the option matching it. */
    fun set(name: Regex, value: String?, not: Regex? = null): Boolean {
        if (value.isNullOrBlank()) return false
        val key = fields.keys.firstOrNull { name.containsMatchIn(it) && (not == null || !not.containsMatchIn(it)) } ?: return false
        val opts = options[key]
        if (opts != null) {
            val v = value.lowercase()
            val pick = opts.firstOrNull { it.second.lowercase() == v || it.first.lowercase() == v }
                ?: opts.firstOrNull { it.second.isNotBlank() && (it.second.lowercase().startsWith(v) || v.startsWith(it.second.lowercase())) }
                ?: return false
            fields[key] = pick.first
        } else {
            fields[key] = value
        }
        return true
    }

    /**
     * The book's details into whatever fields the form has for them: title, subtitle, author,
     * ISBNs, pages, publisher, publication date (one field or year/month/day), language,
     * format, description, cover address and series.
     */
    fun fillBook(d: BookDetails) {
        if (fillBookRules(d).isEmpty()) return
        set(Regex("(?i)(^|\\[)title\\]?$"), d.title)
        set(Regex("(?i)sort_by_title|sort_title"), sortTitle(d.title))
        set(Regex("(?i)subtitle"), d.subtitle)
        set(Regex("(?i)author.*name|author\\]?$|authors"), d.authors.firstOrNull(), not = Regex("(?i)role|id\\]?$"))
        set(Regex("(?i)isbn_?13"), d.isbn13)
        set(Regex("(?i)isbn(?!_?13)"), d.isbn10 ?: d.isbn13, not = Regex("(?i)13"))
        set(Regex("(?i)num_pages|page_count|pages|number_of_pages"), d.pages?.toString() ?: "1")
        set(Regex("(?i)publisher"), d.publisher)
        val parts = d.published?.split("-")
        if (!set(Regex("(?i)publication_date|published_on|pub_date|release_date|publish_date"), d.published)) {
            set(Regex("(?i)(publication|publish|pub)_?year"), parts?.getOrNull(0))
            set(Regex("(?i)(publication|publish|pub)_?month"), parts?.getOrNull(1)?.toIntOrNull()?.toString())
            set(Regex("(?i)(publication|publish|pub)_?day"), parts?.getOrNull(2)?.toIntOrNull()?.toString())
        }
        set(Regex("(?i)language"), d.language ?: "English")
        set(Regex("(?i)format|binding|edition_type"), d.format ?: "Digital")
        setDescription(d.description)
        set(Regex("(?i)image_url|cover_url|cover_image_url|remote_image|image_remote"), d.coverUrl)
        set(Regex("(?i)series(_name|\\]\\[name|\\]?$)"), d.series, not = Regex("(?i)position|number|index"))
        set(Regex("(?i)series.*(position|number|index)"), d.seriesIndex)
    }

    /**
     * The description, into the form's description field: as HTML paragraphs when that field
     * is behind a rich-text box (StoryGraph's), else as plain text. True when there was a field.
     */
    fun setDescription(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val key = fields.keys.firstOrNull { Regex("(?i)description|summary|blurb|synopsis").containsMatchIn(it) } ?: return false
        fields[key] = if (key in richText) Markdown.toTrixHtml(text) else text
        return true
    }

    /** "The Name of the Wind" -> "Name of the Wind, The", as library catalogues sort. */
    fun sortTitle(t: String): String {
        val m = Regex("^(The|A|An)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t) ?: return t
        return "${m.groupValues[2]}, ${m.groupValues[1]}"
    }

    /** The file input for a cover image, if the form has one. */
    val coverField: String? get() = files.firstOrNull { Regex("(?i)cover|image|photo|picture").containsMatchIn(it) } ?: files.firstOrNull()

    /**
     * The form as multipart/form-data with [image] in [field]: (body, content type). For forms
     * whose cover is an upload rather than an address.
     */
    fun multipart(field: String, image: ByteArray, filename: String = "cover.jpg"): Pair<ByteArray, String> {
        val boundary = "----TrackerSync" + java.util.UUID.randomUUID().toString().replace("-", "")
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        for ((k, v) in fields) {
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        val mime = when {
            image.size > 3 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte() -> "image/png"
            image.size > 12 && String(image, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> "image/jpeg"
        }
        val name = filename.substringBeforeLast('.') + "." + mime.substringAfter('/')
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n")
        out.write(image)
        w("\r\n--$boundary--\r\n")
        return out.toByteArray() to "multipart/form-data; boundary=$boundary"
    }

    fun encoded(): String = fields.entries.joinToString("&") { (k, v) -> java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8") }

}

/** One field of a book form: which field (by name, id, placeholder or label), what goes in it, and what not to touch. */
data class FieldRule(val pattern: String, val value: String, val not: String? = null)

/** A [FieldRule] value meaning "tick this box". */
const val TICK = "__tick__"

/** The book's details as field rules, shared by the form filler here and the page filler in the app's browser. */
fun fillBookRules(d: BookDetails): List<FieldRule> {
    val parts = d.published?.split("-")
    return listOfNotNull(
        FieldRule("(^|\\[)title\\]?$|^book title$|^title\\b", d.title, "sort|sub"),
        d.subtitle?.let { FieldRule("subtitle", it) },
        FieldRule("sort_by_title|sort title", sortTitleOf(d.title)),
        d.author?.let { FieldRule("author.*name|author\\]?$|^authors?$|^author\\b", it, "role|id\\]?$") },
        d.isbn13?.let { FieldRule("isbn_?13|isbn-13", it) },
        (d.isbn10 ?: d.isbn13)?.let { FieldRule("isbn(?!_?-?13)", it, "13") },
        FieldRule("num_pages|page_count|pages|number of pages", d.pages?.toString() ?: "1"),
        d.publisher?.let { FieldRule("publisher", it) },
        d.published?.let { FieldRule("publication_date|published_on|pub_date|release_date|publish_date|publication date", it) },
        parts?.getOrNull(0)?.let { FieldRule("(publication|publish|pub)_?year", it) },
        parts?.getOrNull(1)?.toIntOrNull()?.let { FieldRule("(publication|publish|pub)_?month", it.toString()) },
        parts?.getOrNull(2)?.toIntOrNull()?.let { FieldRule("(publication|publish|pub)_?day", it.toString()) },
        FieldRule("language", d.language ?: "English"),
        FieldRule("format|binding|edition_type", d.format ?: "Digital"),
        d.description?.let { FieldRule("description|summary|blurb", it) },
        d.coverUrl?.let { FieldRule("image_url|cover_url|cover_image_url|remote_image|image_remote|cover image url", it) },
        d.series?.let { FieldRule("series(_name|\\]\\[name|\\]?$| name)", it, "position|number|index") },
        d.seriesIndex?.let { FieldRule("series.*(position|number|index)", it) },
        // Pages that import a book from elsewhere (Pagebound): its Goodreads address, its ISBN or ASIN.
        d.goodreadsUrl?.let { FieldRule("goodreads", it) },
        (d.isbn13 ?: d.isbn10 ?: d.asin)?.let { FieldRule("isbn.*asin|asin.*isbn|^asin$|identifier", it) },
        // Fan fiction: the site's own "fan fiction" / "not a book" box, ticked.
        d.web?.takeIf { it.fanfic }?.let { FieldRule("fan.?fic|not a (traditional )?book|non.?book", TICK) },
    )
}

/** "The Name of the Wind" -> "Name of the Wind, The". */
fun sortTitleOf(t: String): String {
    val m = Regex("^(The|A|An)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t) ?: return t
    return "${m.groupValues[2]}, ${m.groupValues[1]}"
}

/**
 * JavaScript that fills a site's add-a-book page with [rules] in the app's browser: each field
 * is found by its name, id, placeholder or label, set the way the site's own scripts notice, and
 * it keeps trying while the page builds its form. Nothing is submitted: you check it and save.
 */
fun fillPageScript(rules: List<FieldRule>, coverBase64: String? = null, coverMime: String = "image/jpeg"): String {
    val json = org.json.JSONArray(rules.map { r -> org.json.JSONObject().put("p", r.pattern).put("v", r.value).put("n", r.not ?: org.json.JSONObject.NULL) })
    return """
(function(){
  var rules = $json, done = {};
  function keys(el){
    var k = [el.name||'', el.id||'', el.getAttribute('placeholder')||'', el.getAttribute('aria-label')||''];
    if (el.id) { var l = document.querySelector('label[for="'+el.id+'"]'); if (l) k.push(l.textContent.trim()); }
    var p = el.closest('label'); if (p) k.push(p.textContent.trim());
    return k.filter(function(x){return x;});
  }
  function setVal(el, v){
    if (v === '$TICK') {
      if (el.type !== 'checkbox' && el.type !== 'radio') return false;
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'checked').set.call(el, true);
    } else if (el.tagName === 'SELECT') {
      var lv = v.toLowerCase(), opt = null;
      for (var i=0;i<el.options.length;i++){ var o=el.options[i]; var t=(o.text||'').trim().toLowerCase();
        if (t===lv || (o.value||'').toLowerCase()===lv || (t && (t.indexOf(lv)===0 || lv.indexOf(t)===0))) { opt=o; break; } }
      if (!opt) return false;
      el.value = opt.value;
    } else {
      var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    }
    el.dispatchEvent(new Event('input', {bubbles:true}));
    el.dispatchEvent(new Event('change', {bubbles:true}));
    return true;
  }
  function paragraphs(v){
    return v.split(/\n\s*\n/).map(function(p){
      return '<div>' + p.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\n/g,'<br>') + '</div>';
    }).join('');
  }
  // Rich-text boxes (Trix, as StoryGraph's description): their text goes in through the editor.
  function fillRich(){
    var eds = document.querySelectorAll('trix-editor');
    rules.forEach(function(r, i){
      if (done[i] || r.v === '$TICK') return;
      var re = new RegExp(r.p, 'i'), no = r.n ? new RegExp(r.n, 'i') : null;
      for (var j=0;j<eds.length;j++){
        var ed = eds[j];
        if (ed.dataset.trackerSync || !ed.editor) continue;
        var inp = ed.getAttribute('input') ? document.getElementById(ed.getAttribute('input')) : null;
        var ks = [ed.id||'', ed.getAttribute('placeholder')||'', ed.getAttribute('aria-label')||''];
        if (inp) { ks.push(inp.name||''); ks.push(inp.id||''); }
        var l = ed.id ? document.querySelector('label[for="'+ed.id+'"]') : null; if (l) ks.push(l.textContent.trim());
        ks = ks.filter(function(x){return x;});
        if (ks.some(function(k){return re.test(k);}) && !(no && ks.some(function(k){return no.test(k);}))) {
          ed.editor.loadHTML(paragraphs(r.v));
          if (inp) { inp.value = ed.innerHTML; inp.dispatchEvent(new Event('input', {bubbles:true})); }
          ed.dataset.trackerSync = '1'; done[i] = true;
          break;
        }
      }
    });
  }
  function fill(){
    fillRich();
    var els = document.querySelectorAll('input:not([type=hidden]):not([type=submit]):not([type=checkbox]):not([type=radio]):not([type=file]), textarea, select');
    var boxes = document.querySelectorAll('input[type=checkbox], input[type=radio]');
    rules.forEach(function(r, i){
      if (done[i]) return;
      var re = new RegExp(r.p, 'i'), no = r.n ? new RegExp(r.n, 'i') : null;
      var pool = r.v === '$TICK' ? boxes : els;
      for (var j=0;j<pool.length;j++){
        var el = pool[j];
        if (el.dataset.trackerSync) continue;
        var ks = keys(el);
        if (ks.some(function(k){return re.test(k);}) && !(no && ks.some(function(k){return no.test(k);}))) {
          if (setVal(el, r.v)) { el.dataset.trackerSync = '1'; done[i] = true; }
          break;
        }
      }
    });
  }
  var cover = ${if (coverBase64 == null) "null" else "'" + coverBase64 + "'"}, coverDone = false;
  // The cover as a file in the form's upload field, the way choosing one by hand does.
  function addCover(){
    if (!cover || coverDone) return;
    var files = Array.prototype.slice.call(document.querySelectorAll('input[type=file]'));
    if (!files.length) return;
    var el = files.filter(function(f){ return keys(f).some(function(k){ return /cover|image|photo|picture/i.test(k); }); })[0] || files[0];
    try {
      var bin = atob(cover), arr = new Uint8Array(bin.length);
      for (var i=0;i<bin.length;i++) arr[i] = bin.charCodeAt(i);
      var dt = new DataTransfer();
      dt.items.add(new File([arr], 'cover.' + '$coverMime'.split('/')[1], {type: '$coverMime'}));
      el.files = dt.files;
      el.dispatchEvent(new Event('change', {bubbles:true}));
      coverDone = true;
    } catch (e) {}
  }
  // Keeps filling for a couple of minutes: on a page that asks first how to add the book
  // (Pagebound), the fields only appear after you pick an option.
  fill(); addCover(); var n = 0; var t = setInterval(function(){ fill(); addCover(); if (++n > 170) clearInterval(t); }, 700);
})();
""".trimIndent()
}
