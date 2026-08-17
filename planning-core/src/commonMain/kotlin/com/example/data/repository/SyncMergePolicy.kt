package com.example.data.repository

import com.example.data.model.BriefingEvent

/**
 * What a re-sync is allowed to overwrite.
 *
 * This is the one place where source data and hand edits meet, and getting it
 * wrong silently destroys the user's work — so it is pure, and tested directly
 * rather than only through a database. It moved out of the app (WP-13) so the
 * server's reconciliation worker runs the same policy code: the platform only
 * differs in how a stored row's provider id is recovered ([providerIdOf]).
 *
 * This is the only place that decides what a re-sync may overwrite. Change it
 * with tests.
 */
object SyncMergePolicy {

  /**
   * Rebases [incoming] source rows onto what is already stored.
   *
   * Times and location always track the source of truth. Wording, priority flags
   * and board placement belong to whoever last touched them by hand.
   *
   * [providerIdOf] recovers the provider row id from a stored id — on the device that is
   * `DeviceCalendarSync.providerEventId` (the `device_<id>_<begin>` convention), on the
   * server it is the Google calendar's own row id. The policy itself is platform-neutral.
   */
  fun merge(
    incoming: List<BriefingEvent>,
    existingEvents: List<BriefingEvent>,
    providerIdOf: (BriefingEvent) -> Long?,
  ): List<BriefingEvent> {
    val existingById = existingEvents.associateBy { it.id }
    val existingByProviderId =
      existingEvents
        .mapNotNull { event ->
          providerIdOf(event)?.let { providerId -> providerId to event }
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    return incoming.map { fresh ->
      val prior = priorFor(fresh, existingById, existingByProviderId, providerIdOf) ?: return@map fresh
      if (prior.userEdited) {
        fresh.copy(
          title = prior.title,
          description = prior.description,
          isUrgent = prior.isUrgent,
          isDeadline = prior.isDeadline,
          kanbanStatus = prior.kanbanStatus,
          kanbanBoard = prior.kanbanBoard,
          userEdited = true,
        )
      } else {
        fresh.copy(kanbanStatus = prior.kanbanStatus, kanbanBoard = prior.kanbanBoard)
      }
    }
  }

  /**
   * The stored row this incoming one continues.
   *
   * Normally that is the same id. Device rows also match on the provider row id,
   * so an event whose local id changed — a DTSTART edit, or the upgrade that gave
   * non-repeating events a stable id — keeps its edits instead of arriving as a
   * stranger. An ambiguous provider id (several stored instances of one repeating
   * series) is deliberately left unmatched: guessing which instance to inherit
   * from would corrupt the others.
   */
  private fun priorFor(
    fresh: BriefingEvent,
    existingById: Map<String, BriefingEvent>,
    existingByProviderId: Map<Long, List<BriefingEvent>>,
    providerIdOf: (BriefingEvent) -> Long?,
  ): BriefingEvent? =
    existingById[fresh.id]
      ?: providerIdOf(fresh)?.let { providerId -> existingByProviderId[providerId]?.singleOrNull() }

  /** Drops rows from other sources and collapses duplicate ids from one fetch. */
  fun scopedToSources(
    incoming: List<BriefingEvent>,
    sources: List<String>,
  ): List<BriefingEvent> =
    incoming.filter { it.source in sources }.associateBy { it.id }.values.toList()
}
