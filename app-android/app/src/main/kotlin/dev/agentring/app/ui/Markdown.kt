package dev.agentring.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp

/**
 * The Markdown agents write, as styled text: **bold**, *italic* / _italic_, ~~strike~~, `code`,
 * ``` blocks ```, # headings, - and 1. lists, > quotes and [links](url) / bare URLs.
 * Small on purpose (no dependency); anything it doesn't know stays as written.
 */
object Markdown {
    private val URL = Regex("""https?://[^\s<>()]+[^\s<>().,;:!?"'\]]""")

    fun render(text: String, linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0
        var first = true
        fun newline() {
            if (!first) append('\n')
            first = false
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.trimStart().startsWith("```")) {
                // Code block up to the closing fence (or the end).
                val body = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) body += lines[i++]
                i++
                newline()
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, background = codeBackground)) {
                    append(body.joinToString("\n"))
                }
                continue
            }
            newline()
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$").find(line)
            val numbered = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$").find(line)
            val quote = Regex("^>\\s?(.*)$").find(line)
            when {
                heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = if (heading.groupValues[1].length <= 2) 18.sp else 16.sp)) {
                    inline(heading.groupValues[2], linkColor, codeBackground)
                }
                bullet != null -> {
                    append("  ".repeat(bullet.groupValues[1].length / 2) + "•  ")
                    inline(bullet.groupValues[2], linkColor, codeBackground)
                }
                numbered != null -> {
                    append("  ".repeat(numbered.groupValues[1].length / 2) + numbered.groupValues[2] + ".  ")
                    inline(numbered.groupValues[3], linkColor, codeBackground)
                }
                quote != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.Unspecified)) {
                    append("▍ ")
                    inline(quote.groupValues[1], linkColor, codeBackground)
                }
                else -> inline(line, linkColor, codeBackground)
            }
            i++
        }
    }

    private data class Token(val start: Int, val end: Int, val kind: Char, val inner: String, val url: String? = null)

    /** Inline spans. Earliest match wins; nesting inside bold/italic is rendered recursively. */
    private fun AnnotatedString.Builder.inline(s: String, linkColor: Color, codeBg: Color) {
        val patterns = listOf(
            'c' to Regex("`([^`]+)`"),
            'l' to Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"),
            'b' to Regex("\\*\\*(.+?)\\*\\*|__(.+?)__"),
            's' to Regex("~~(.+?)~~"),
            'i' to Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])"),
            'u' to URL,
        )
        var pos = 0
        while (pos < s.length) {
            var best: Token? = null
            for ((k, re) in patterns) {
                val m = re.find(s, pos) ?: continue
                if (best != null && m.range.first >= best.start) continue
                best = when (k) {
                    'l' -> Token(m.range.first, m.range.last + 1, k, m.groupValues[1], m.groupValues[2])
                    'u' -> Token(m.range.first, m.range.last + 1, k, m.value, m.value)
                    else -> Token(m.range.first, m.range.last + 1, k, m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "")
                }
            }
            val t = best ?: break
            append(s.substring(pos, t.start))
            val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            when (t.kind) {
                'c' -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(t.inner) }
                'b' -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(t.inner, linkColor, codeBg) }
                'i' -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(t.inner, linkColor, codeBg) }
                's' -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inline(t.inner, linkColor, codeBg) }
                'l', 'u' -> withLink(LinkAnnotation.Url(t.url!!, linkStyle)) { append(t.inner) }
            }
            pos = t.end
        }
        if (pos < s.length) append(s.substring(pos))
    }
}
