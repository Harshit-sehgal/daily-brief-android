package com.example.core

/**
 * Ranking for the quick switcher.
 *
 * Deliberately simple and predictable rather than clever: people type the start of
 * a word, so a prefix beats a word-start, which beats a substring, which beats a
 * scattered subsequence. Ties break toward shorter targets, so "Week" outranks
 * "Weekly planning review" for the query "wee".
 */
object Fuzzy {

  /** Higher is better. `null` means no match at all. */
  fun score(query: String, target: String): Int? {
    val q = query.trim().lowercase()
    val t = target.lowercase()
    if (q.isEmpty()) return 0
    if (q.length > t.length) return null

    if (t.startsWith(q)) return 4000 - t.length
    wordStartIndex(t, q)?.let { return 3000 - it - t.length / 4 }
    val contains = t.indexOf(q)
    if (contains >= 0) return 2000 - contains - t.length / 4
    return subsequenceScore(q, t)
  }

  /** Index of a word boundary where [q] begins, if any. */
  private fun wordStartIndex(t: String, q: String): Int? {
    var from = 0
    while (true) {
      val at = t.indexOf(q, from)
      if (at <= 0) return if (at == 0) 0 else null
      val prev = t[at - 1]
      if (!prev.isLetterOrDigit()) return at
      from = at + 1
    }
  }

  /** All query characters present in order, penalised by how spread out they are. */
  private fun subsequenceScore(q: String, t: String): Int? {
    var ti = 0
    var gaps = 0
    var last = -1
    for (c in q) {
      val at = t.indexOf(c, ti)
      if (at < 0) return null
      if (last >= 0) gaps += at - last - 1
      last = at
      ti = at + 1
    }
    return 1000 - gaps - t.length / 4
  }

  /** Filters and orders [items] by how well [query] matches the text [selector] returns. */
  fun <T> rank(query: String, items: List<T>, selector: (T) -> String): List<T> =
    items
      .mapNotNull { item -> score(query, selector(item))?.let { item to it } }
      .sortedByDescending { it.second }
      .map { it.first }
}
