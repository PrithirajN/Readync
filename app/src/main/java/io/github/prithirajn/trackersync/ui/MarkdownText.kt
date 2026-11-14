package io.github.prithirajn.trackersync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.prithirajn.trackersync.ui.theme.Cp
import io.github.prithirajn.trackersync.ui.theme.CpShapes
import io.github.prithirajn.trackersync.ui.theme.CpType
import io.github.prithirajn.trackersync.util.Markdown

/** A review written in Markdown, shown as it will read. Spoilers show on a tinted background. */
@Composable
fun MarkdownText(md: String, modifier: Modifier = Modifier) {
    val c = Cp.colors
    Column(modifier) {
        Markdown.parse(md).forEachIndexed { i, b ->
            if (i > 0) VSpace(8)
            MdBlock(b, c.text, c.accent, c.divider)
        }
    }
}

@Composable
private fun MdBlock(b: Markdown.Block, text: Color, accent: Color, tint: Color) {
    when (b) {
        is Markdown.Para -> Text(annotated(b.inlines, accent, tint), style = CpType.body, color = text)
        is Markdown.Heading -> Text(annotated(b.inlines, accent, tint), style = CpType.bodyStrong, color = text)
        is Markdown.ListBlock -> Column {
            b.items.forEachIndexed { n, item ->
                Row {
                    Text(if (b.ordered) "${n + 1}." else "•", style = CpType.body, color = Cp.colors.quiet, modifier = Modifier.width(22.dp))
                    Text(annotated(item, accent, tint), style = CpType.body, color = text)
                }
            }
        }
        is Markdown.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(tint, CpShapes.pill))
            Column(Modifier.padding(start = 10.dp)) {
                b.blocks.forEach { MdBlock(it, Cp.colors.textMid, accent, tint) }
            }
        }
    }
}

private fun annotated(inl: List<Markdown.Inline>, accent: Color, tint: Color): AnnotatedString = buildAnnotatedString {
    fun add(xs: List<Markdown.Inline>) {
        for (x in xs) when (x) {
            is Markdown.Text -> append(x.text)
            is Markdown.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { add(x.children) }
            is Markdown.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { add(x.children) }
            is Markdown.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { add(x.children) }
            is Markdown.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = tint)) { append(x.text) }
            is Markdown.Link -> withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { add(x.children) }
            is Markdown.Spoiler -> withStyle(SpanStyle(background = tint)) { add(x.children) }
            Markdown.Break -> append("\n")
        }
    }
    add(inl)
}
