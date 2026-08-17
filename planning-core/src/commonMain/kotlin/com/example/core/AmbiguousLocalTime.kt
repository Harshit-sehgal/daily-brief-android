package com.example.core

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * What a wall-clock time means on the two days a year when it means two things, or none.
 *
 * This used to be inherited rather than chosen. The engine ran on `java.util.Calendar`, whose
 * policy is to resolve a repeated wall time — the hour that happens twice when clocks go back —
 * to the **later, standard-time** occurrence, and to push a wall time that does not exist —
 * the hour skipped when clocks go forward — to the next valid minute. `WorkingCalendarTest`
 * pins both to exact instants.
 *
 * `kotlinx-datetime` and `java.time` resolve a repeated wall time to the **earlier** occurrence
 * instead. Porting the engine without noticing would have moved every affected working window by
 * an hour, silently, on a server resolving many zones at once rather than one phone in one zone.
 *
 * So the policy is written down here instead of being whatever the date library happens to do.
 * If it is ever changed, it should be changed here, deliberately, and the pinned tests should
 * fail loudly when it is.
 */
internal object AmbiguousLocalTime {

  /**
   * The instant this wall-clock time refers to, resolving a repeated time to the later occurrence.
   *
   * A local time is a real representation of an instant only when it maps back to itself, so the
   * candidates are tested by round trip:
   *
   * - **Ordinary day** — one candidate round-trips; that is the answer.
   * - **Clocks go back** — two candidates round-trip. The later instant is the standard-time
   *   occurrence, which is the `Calendar` behaviour being preserved.
   * - **Clocks go forward** — no candidate round-trips, because the wall time never happens.
   *   `toInstant` normalises forward to the next valid minute, matching `Calendar`.
   */
  fun resolve(local: LocalDateTime, zone: TimeZone): Instant {
    // A transition near this local time is within a day either side, whatever the offset is.
    val probe = local.toInstant(UtcOffset.ZERO)
    val offsets =
      listOf(zone.offsetAt(probe - 1.days), zone.offsetAt(probe + 1.days)).distinct()

    val valid = offsets.map(local::toInstant).filter { it.toLocalDateTime(zone) == local }
    // maxOrNull is the later occurrence; the fallback covers the skipped-hour case.
    return valid.maxOrNull() ?: local.toInstant(zone)
  }
}
