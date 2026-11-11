package com.remotegamestreamer.client.input

import android.graphics.Path

/** Small, bounded SVG subset for HUD icons. It accepts local path geometry only. */
internal object SvgIconParser {
    data class Icon(val paths: List<Path>, val minX: Float, val minY: Float, val width: Float, val height: Float)

    private val pathTag = Regex("<path\\b([^>]*)/?>", RegexOption.IGNORE_CASE)
    private val dataAttr = Regex("\\bd\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val viewBoxAttr = Regex("\\bviewBox\\s*=\\s*([\"'])(.*?)\\1", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tokens = Regex("[A-Za-z]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?")

    fun parse(svg: String): Icon? {
        if (svg.length !in 1..(16 * 1024)) return null
        val source = svg.trimStart().replaceFirst(Regex("^<\\?xml\\s+[^?]*\\?>\\s*", RegexOption.IGNORE_CASE), "")
        if (!source.trimStart().startsWith("<svg", true)) return null
        if (Regex("<(?!/?(?:svg|path)\\b)[^>]+>", RegexOption.IGNORE_CASE).containsMatchIn(source)) return null
        if (Regex("\\b(?:href|style|on[a-z]+)\\s*=|url\\s*\\(|<!", RegexOption.IGNORE_CASE).containsMatchIn(source)) return null
        val viewBox = viewBoxAttr.find(source)?.groupValues?.get(2)?.trim()?.split(Regex("[,\\s]+"))?.mapNotNull(String::toFloatOrNull)
        val bounds = if (viewBox?.size == 4 && viewBox.all(Float::isFinite) && viewBox[2] > 0f && viewBox[3] > 0f) viewBox
            else listOf(0f, 0f, 24f, 24f)
        val tags = pathTag.findAll(source).toList()
        val paths = tags.map { tag ->
            val d = dataAttr.find(tag.groupValues[1])?.groupValues?.get(2) ?: return null
            parsePath(d) ?: return null
        }
        if (paths.isEmpty() || pathTag.findAll(source).count() != paths.size) return null
        return Icon(paths, bounds[0], bounds[1], bounds[2], bounds[3])
    }

    private fun parsePath(data: String): Path? {
        if (data.length > 8192) return null
        val ts = tokens.findAll(data).map { it.value }.toList()
        if (ts.isEmpty()) return null
        var i = 0
        var command = ' '
        var x = 0f; var y = 0f; var startX = 0f; var startY = 0f
        val path = Path()
        fun number(): Float? = ts.getOrNull(i++)?.toFloatOrNull()?.takeIf(Float::isFinite)
        while (i < ts.size) {
            if (ts[i].length == 1 && ts[i][0].isLetter()) command = ts[i++][0]
            if (command == ' ') return null
            val relative = command.isLowerCase()
            when (command.uppercaseChar()) {
                'M', 'L' -> {
                    val nx = number() ?: return null; val ny = number() ?: return null
                    val tx = if (relative) x + nx else nx; val ty = if (relative) y + ny else ny
                    if (command.uppercaseChar() == 'M') { path.moveTo(tx, ty); startX = tx; startY = ty; command = if (relative) 'l' else 'L' }
                    else path.lineTo(tx, ty)
                    x = tx; y = ty
                }
                'H' -> { val v = number() ?: return null; x = if (relative) x + v else v; path.lineTo(x, y) }
                'V' -> { val v = number() ?: return null; y = if (relative) y + v else v; path.lineTo(x, y) }
                'C' -> {
                    val a = number() ?: return null; val b = number() ?: return null
                    val c = number() ?: return null; val d = number() ?: return null
                    val e = number() ?: return null; val f = number() ?: return null
                    val x1 = if (relative) x + a else a; val y1 = if (relative) y + b else b
                    val x2 = if (relative) x + c else c; val y2 = if (relative) y + d else d
                    val nx = if (relative) x + e else e; val ny = if (relative) y + f else f
                    path.cubicTo(x1, y1, x2, y2, nx, ny); x = nx; y = ny
                }
                'Q' -> {
                    val a = number() ?: return null; val b = number() ?: return null
                    val c = number() ?: return null; val d = number() ?: return null
                    val cx = if (relative) x + a else a; val cy = if (relative) y + b else b
                    val nx = if (relative) x + c else c; val ny = if (relative) y + d else d
                    path.quadTo(cx, cy, nx, ny); x = nx; y = ny
                }
                'Z' -> { path.close(); x = startX; y = startY; command = ' ' }
                else -> return null
            }
        }
        return path
    }
}
