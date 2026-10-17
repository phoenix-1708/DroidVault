package com.phoenix.droidvault.models

/**
 * Reading and filtering the diagnostics log, with no Composable involved.
 *
 * The Live log sheet was making two decisions in a `LazyColumn` that are both
 * decidable without one, and both were wrong:
 *
 *  - **It always scrolled to the newest line.** The log grows many times a
 *    second while a run is going, so scrolling up to read anything was
 *    impossible — the view snapped back to the tail on the next line. That is
 *    invariant 31's shape applied to a scroll position: a live view that moves
 *    when nobody asked it to is worse than a static one, because the reader has
 *    no way to stay where they were.
 *  - **It had no way to find anything.** 2000 lines in one undifferentiated
 *    grey, and the question a field tool exists to answer is "what went wrong".
 *
 * So the two decisions are here, pure and testable, and the sheet asks.
 */
object DiagLog {

    /**
     * The level character of a formatted line, or null.
     *
     * `formatLine` writes `"MM-dd HH:mm:ss.SSS L/Tag: message"`. The tempting
     * parse is "split on spaces, take token three", and it breaks the moment a
     * tag contains a space — the format does not strip them, only newlines. So
     * this anchors on the shape instead: a non-space timestamp, a space, then
     * the `L/Tag:` run. A message full of slashes cannot confuse it, because the
     * match is anchored at the start of the line.
     *
     * Null rather than a default, because the one thing a diagnostic view must
     * not do is invent a severity. A line this cannot read is a line written by
     * something else, and [filter] keeps those.
     */
    private val LINE = Regex("""^\S+\s+\S+\s+([VDIWE])/[^:]*:\s""")

    fun levelOf(line: String): Char? = LINE.find(line)?.groupValues?.get(1)?.firstOrNull()

    /** Levels in severity order, so a filter can be a floor rather than a set. */
    private const val ORDER = "VDIWE"

    private fun rank(level: Char?): Int = level?.let { ORDER.indexOf(it) } ?: -1

    /**
     * Narrow the log to a severity floor and a search term.
     *
     * [query] is matched case-insensitively against the WHOLE line rather than
     * the message, so a tag is findable as easily as a word — `Trash`, `Gate` and
     * `Transfer` are the tags the destructive paths record under, and those are
     * exactly what a report asks about. An empty query filters nothing.
     *
     * **A line with no recognisable level is kept at every setting.** Every line
     * this app writes has one, so an unparseable line came from somewhere else —
     * and a diagnostic view that silently drops lines it does not understand is
     * the failure mode this app keeps fixing in other places (invariant 16,
     * *absence is not failure*). Better a line of noise than a line missing.
     */
    fun filter(
        lines: List<String>,
        minLevel: Char = 'I',
        query: String = ""
    ): List<String> {
        val needle = query.trim()
        val floor = rank(minLevel)
        return lines.filter { line ->
            val r = rank(levelOf(line))
            (r < 0 || r >= floor) && (needle.isEmpty() || line.contains(needle, ignoreCase = true))
        }
    }

    /**
     * Whether the tail should follow new lines.
     *
     * True only when the reader is already at the end, or within [threshold]
     * lines of it. Scrolling up is a statement — "I am reading this" — and the
     * list honours it until the reader comes back down.
     *
     * False for an empty list, so opening the sheet does not scroll into
     * nothing, and true for a single visible item, so a short log still follows.
     */
    fun shouldStickToTail(
        lastVisibleIndex: Int,
        totalItems: Int,
        threshold: Int = 1
    ): Boolean {
        if (totalItems <= 0) return false
        return lastVisibleIndex >= totalItems - 1 - threshold
    }

    /** The severity chips, in order, with "All" as the unset option. */
    val LEVELS: List<Char> = ORDER.toList()
}
