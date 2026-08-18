package com.example.ui.viewmodel

import com.example.data.prefs.SettingKeys

/** Pure normalization rules for user-arranged workspace preferences. */
internal object WorkspacePreferencePolicy {
  /**
   * Navigation was consolidated from four roots to Home / Calendar / Plan.
   * Keep stored launch choices from older installs useful instead of silently
   * falling back to Home after the upgrade.
   */
  fun homeDestination(raw: String?): String =
    when (raw) {
      "Calendar", "Today", "Week" -> "Calendar"
      "Plan", "Board" -> "Plan"
      else -> "Home"
    }

  fun calendarView(raw: String?, legacyHomeDestination: String? = null): String =
    when (raw) {
      "Timeline", "Week" -> raw
      null -> if (legacyHomeDestination == "Week") "Week" else "Agenda"
      else -> "Agenda"
    }

  fun planView(raw: String?): String =
    when (raw) {
      "Board", "Gantt" -> raw
      else -> "Outline"
    }

  fun homeCards(raw: String?): List<HomeCard> =
    SettingKeys.decodeList(raw)?.mapNotNull(HomeCard::byKey)?.distinct()?.ifEmpty { null }
      ?: HomeCard.Defaults

  fun setHomeCardVisible(raw: String?, card: HomeCard, visible: Boolean): List<HomeCard> {
    val current = homeCards(raw)
    if (!visible && current.size == 1 && current.single() == card) return current
    return if (visible) (current + card).distinct() else current.filterNot { it == card }
  }

  fun moveHomeCard(raw: String?, card: HomeCard, delta: Int): List<HomeCard> =
    move(homeCards(raw), card, delta)

  fun todaySections(raw: String?): List<TodaySection> =
    SettingKeys.decodeList(raw)?.mapNotNull(TodaySection::byKey)?.distinct()?.ifEmpty { null }
      ?: TodaySection.Defaults

  fun setTodaySectionVisible(
    raw: String?,
    section: TodaySection,
    visible: Boolean,
  ): List<TodaySection> {
    val current = todaySections(raw)
    val next =
      if (visible) (current + section).distinct() else current.filterNot { it == section }
    return next.ifEmpty { listOf(TodaySection.Agenda) }
  }

  fun moveTodaySection(raw: String?, section: TodaySection, delta: Int): List<TodaySection> =
    move(todaySections(raw), section, delta)

  /** Only Today section keys are durable; old Week epoch keys are discarded. */
  fun collapsedTodaySections(raw: String?): Set<String> =
    SettingKeys.decodeList(raw).orEmpty().filter { TodaySection.byKey(it) != null }.toSet()

  fun toggleTodaySection(raw: String?, section: TodaySection): Set<String> =
    collapsedTodaySections(raw).toMutableSet().apply {
      if (!add(section.key)) remove(section.key)
    }

  fun revealTodaySection(raw: String?, section: TodaySection): List<TodaySection> =
    (todaySections(raw) + section).distinct()

  private fun <T> move(items: List<T>, item: T, delta: Int): List<T> {
    val mutable = items.toMutableList()
    val from = mutable.indexOf(item)
    val to = from + delta
    if (from < 0 || to !in mutable.indices) return items
    mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
  }
}
