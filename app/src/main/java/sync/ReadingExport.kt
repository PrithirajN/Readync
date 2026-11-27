package io.github.prithirajn.readync.sync

import io.github.prithirajn.readync.data.Snapshot
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Your whole library as a reading log, one book a row: title, author, status, dates, rating (and
 * your rubric scores), review, pages, series, genres, moods, pace, ISBN, where it came from and how
 * many highlights. As a CSV (any spreadsheet opens it) or an Excel workbook (.xlsx).
 */
object ReadingExport {

    fun rows(s: Snapshot): List<List<String>> {
        val rubric = s.rubric
        val head = listOf(
            "Title", "Author", "Status", "Started", "Finished", "Rating (of 5)", "Review", "Pages", "Series", "Series #",
            "Genres", "Moods", "Pace", "ISBN", "Source", "Highlights",
        ) + rubric + (if (rubric.isNotEmpty()) listOf("Rubric average (of 10)") else emptyList())
        val clips = Highlights.all(s).groupingBy { it.clip.document }.eachCount()
        fun date(t: Long?) = t?.takeIf { it > 0 }?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() } ?: ""
        val body = s.books.values.sortedWith(compareByDescending<io.github.prithirajn.readync.data.Book> { it.finishedAt ?: it.startedAt ?: it.timestamp }).map { b ->
            val f = s.facts[b.document]
            val scores = s.rubricScores[b.document].orEmpty()
            val rubricCols = rubric.map { c -> scores[c]?.toString() ?: "" }
            val avg = rubric.mapNotNull { scores[it] }.takeIf { it.isNotEmpty() }?.average()
            listOf(
                b.displayTitle, b.author.orEmpty(), b.status?.label.orEmpty(), date(b.startedAt), date(b.finishedAt),
                b.rating?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
                b.review.orEmpty(), b.pageCount?.toString().orEmpty(), f?.series.orEmpty(), f?.seriesNumber.orEmpty(),
                f?.genres.orEmpty().joinToString(", "), f?.moods.orEmpty().joinToString(", "), f?.pace.orEmpty(), b.isbn.orEmpty(),
                b.origin ?: "CrossPoint", (clips[b.document] ?: 0).toString(),
            ) + rubricCols + (if (rubric.isNotEmpty()) listOf(avg?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "") else emptyList())
        }
        return listOf(head) + body
    }

    fun csv(s: Snapshot): ByteArray {
        fun cell(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        // A byte-order mark so Excel reads the accents and the ₹ right.
        return ("﻿" + rows(s).joinToString("\r\n") { r -> r.joinToString(",") { cell(it) } }).toByteArray(Charsets.UTF_8)
    }

    /** A minimal Excel workbook: one sheet, a bold header row frozen at the top, numbers as numbers. */
    fun xlsx(s: Snapshot): ByteArray {
        val rows = rows(s)
        fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
        fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, ('A' + r)); n = (n - 1) / 26 }; return sb.toString() }
        val numeric = setOf("Rating (of 5)", "Pages", "Highlights", "Rubric average (of 10)") + s.rubric
        val head = rows.first()
        val sheet = StringBuilder()
        sheet.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>""")
        rows.forEachIndexed { r, row ->
            sheet.append("<row r=\"${r + 1}\">")
            row.forEachIndexed { c, v ->
                val ref = col(c) + (r + 1)
                val style = if (r == 0) " s=\"1\"" else ""
                val num = r > 0 && head.getOrNull(c) in numeric && v.toDoubleOrNull() != null
                if (num) sheet.append("<c r=\"$ref\"$style><v>$v</v></c>")
                else if (v.isNotEmpty()) sheet.append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
            }
            sheet.append("</row>")
        }
        sheet.append("</sheetData></worksheet>")
        val files = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Reading log" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""",
            "xl/styles.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to sheet.toString(),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return out.toByteArray()
    }
}
