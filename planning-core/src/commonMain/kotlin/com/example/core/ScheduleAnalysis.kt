package com.example.core

import com.example.data.model.BriefingEvent
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Pure schedule maths, shared by the UI, the AI prompt builder and the
 * notification receiver. Previously each of those carried its own copy of the
 * overlap loop; they are all this one now.
 *
 * Ported off `java.util.Calendar`/`TimeZone` with the engine's DST policy named in
 * [AmbiguousLocalTime]: a repeated wall time is the later, standard-time occurrence,
 * and a skipped wall time normalises forward to the next valid minute.
 */
@OptIn(ExperimentalTime::class)
object ScheduleAnalysis {

  data class Conflict(val first: BriefingEvent, val second: BriefingEvent) {
    /** Milliseconds the two events actually share. */
    val overlapMs: Long
      get() = minOf(first.endTime, second.endTime) - maxOf(first.startTime, second.startTime)
  }

  data class DayStats(val total: Int, val conflicts: Int, val urgent: Int, val bookedMinutes: Int)

  fun isAllDay(event: BriefingEvent): Boolean = event.isAllDay

  /**
   * The hard, capacity-eating stretches a list of events represents — the shape every planning
   * consumer feeds to the engine as fixed commitments. All-day entries are markers, not
   * commitments: they claim no capacity, so a planner is free to propose work across the whole
   * day they label.
   */
  fun fixedCommitments(events: List<BriefingEvent>): List<WorkingInterval> =
    events
      .filter { it.endTime > it.startTime && !it.isAllDay }
      .map { WorkingInterval(it.startTime, it.endTime) }

  /**
   * Pairs of events whose times genuinely overlap, ordered by start time.
   *
   * All-day entries are skipped: they overlap everything by definition and
   * reporting them as clashes buries the real ones. Zero-length markers are
   * skipped for the same reason — they cannot occupy a slot.
   */
  fun findConflicts(events: List<BriefingEvent>): List<Conflict> {
    val candidates =
      events
        .filter { it.endTime > it.startTime && !isAllDay(it) }
        .sortedWith(compareBy({ it.startTime }, { it.endTime }, { it.id }))
    if (candidates.size < 2) return emptyList()

    val conflicts = mutableListOf<Conflict>()
    // Sweep line: only events still running when the next one starts can clash.
    val active = mutableListOf<BriefingEvent>()
    for (event in candidates) {
      active.removeAll { it.endTime <= event.startTime }
      for (open in active) {
        conflicts.add(Conflict(open, event))
      }
      active.add(event)
    }
    return conflicts
  }

  fun statsFor(
    events: List<BriefingEvent>,
    startInclusive: Long? = null,
    endExclusive: Long? = null,
  ): DayStats {
    val conflicts = findConflicts(events)
    val urgent = events.count { it.isUrgent || it.isDeadline }
    val booked =
      events.filterNot { isAllDay(it) }.sumOf { event ->
        val start = startInclusive?.let { maxOf(event.startTime, it) } ?: event.startTime
        val end = endExclusive?.let { minOf(event.endTime, it) } ?: event.endTime
        maxOf(0L, end - start)
      } /
        (60L * 1000L)
    return DayStats(
      total = events.size,
      conflicts = conflicts.size,
      urgent = urgent,
      bookedMinutes = booked.toInt(),
    )
  }

  /**
   * Fingerprint of everything a brief depends on. Two schedules with the same
   * signature produce the same brief, so a cached one can be reused.
   *
   * SHA-256 over length-prefixed fields, byte-identical to the `java.security.MessageDigest`
   * output it replaced — [Sha256] is tested against NIST vectors and this exact byte layout is
   * pinned by `SignatureFixtureTest`. The digest is the daily-brief cache key, so it must never
   * change without deliberately accepting one cache miss per user.
   */
  fun signature(events: List<BriefingEvent>): String {
    val digest = SignatureDigest()
    digest.putInt(events.size)
    events.sortedBy { it.id }.forEach { event ->
      digest.putString(event.id)
      digest.putLong(event.startTime)
      digest.putLong(event.endTime)
      digest.putString(event.title)
      digest.putBoolean(event.isUrgent)
      digest.putBoolean(event.isDeadline)
      digest.putBoolean(event.isAllDay)
      digest.putString(event.source)
      // The Gemini prompt omits blank locations, so canonicalize them alike.
      digest.putString(event.location?.takeIf { it.isNotBlank() }.orEmpty())
    }
    return digest.hex()
  }

  /** Inclusive millisecond bounds of the local calendar day containing [timeMs]. */
  fun dayBounds(
    timeMs: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): LongRange = startOfDay(timeMs, timeZone)..(startOfDayOffset(timeMs, 1, timeZone) - 1)

  fun startOfDay(timeMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDate(timeMs, timeZone).atStartOfDayIn(timeZone).toEpochMilliseconds()

  /** Start of the day [days] later, hopping via local dates so DST cannot skip a day. */
  fun startOfDayOffset(
    timeMs: Long,
    days: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Long = localDate(timeMs, timeZone).plus(days, DateTimeUnit.DAY).atStartOfDayIn(timeZone).toEpochMilliseconds()

  /**
   * Material's date picker reports a UTC midnight. Reading the calendar fields
   * back out in UTC and rebuilding them locally keeps the user on the date they
   * actually tapped, whatever their offset.
   */
  fun localDayFromUtcMillis(utcMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDate(utcMs, TimeZone.UTC).atStartOfDayIn(timeZone).toEpochMilliseconds()

  /**
   * Calendar providers store all-day boundaries as UTC midnights representing
   * calendar dates, not instants that should be shifted into the device zone.
   * Rebuild those dates at local midnight and keep the end exclusive.
   */
  fun normalizeAllDayUtcRange(
    startUtcMs: Long,
    endUtcExclusiveMs: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Pair<Long, Long> {
    val localStart = localDayFromUtcMillis(startUtcMs, timeZone)
    val candidateEnd =
      if (endUtcExclusiveMs > startUtcMs) localDayFromUtcMillis(endUtcExclusiveMs, timeZone)
      else localStart
    val localEnd =
      if (candidateEnd > localStart) candidateEnd
      else startOfDayOffset(localStart, 1, timeZone)
    return localStart to localEnd
  }

  /**
   * Inverse of [localDayFromUtcMillis]: the UTC midnight the date picker needs
   * in order to pre-select the local date the user is actually on.
   */
  fun utcMillisFromLocalDay(localMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDate(localMs, timeZone).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

  /** Replaces the clock time on [dayMs] while keeping its date. */
  fun withTimeOfDay(
    dayMs: Long,
    hour: Int,
    minute: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Long {
    val date = localDate(dayMs, timeZone)
    return AmbiguousLocalTime.resolve(LocalDateTime(date.year, date.month, date.day, hour, minute), timeZone)
      .toEpochMilliseconds()
  }

  /** Suggests a start on the day being viewed without rounding into another day. */
  fun suggestedEventStart(
    dayMs: Long,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Long {
    if (!isSameDay(dayMs, nowMs, timeZone)) return withTimeOfDay(dayMs, 9, 0, timeZone)
    val quarter = 15 * 60 * 1000L
    val rounded = ((nowMs + quarter - 1) / quarter) * quarter
    return rounded.takeIf { isSameDay(it, dayMs, timeZone) } ?: nowMs
  }

  /**
   * Moves an event by local calendar days instead of fixed 24-hour periods.
   *
   * Timed events keep both endpoint wall-clock times. All-day events keep local-midnight
   * boundaries and their calendar-day span, including across 23- and 25-hour days.
   */
  fun moveEventByDays(
    event: BriefingEvent,
    days: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Pair<Long, Long> {
    if (days == 0) return event.startTime to event.endTime
    if (event.isAllDay) {
      val start = startOfDayOffset(event.startTime, days, timeZone)
      val end = startOfDayOffset(event.endTime, days, timeZone)
      return start to if (end > start) end else startOfDayOffset(start, 1, timeZone)
    }

    val start = calendarTimeOffset(event.startTime, days, timeZone)
    val end = calendarTimeOffset(event.endTime, days, timeZone)
    return start to if (end > start) end else start + 60_000L
  }

  /** Moves an event onto an exact local calendar date while preserving its wall-clock span. */
  fun moveEventToDay(
    event: BriefingEvent,
    targetDayMs: Long,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): Pair<Long, Long> {
    val delta =
      localDateOrdinal(targetDayMs, timeZone) - localDateOrdinal(event.startTime, timeZone)
    require(delta in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
      "The target date is outside the supported calendar range"
    }
    return moveEventByDays(event, delta.toInt(), timeZone)
  }

  fun hourOf(timeMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Int =
    localDateTime(timeMs, timeZone).hour

  fun minuteOf(timeMs: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Int =
    localDateTime(timeMs, timeZone).minute

  /**
   * Moves an instant's wall clock onto the local date [days] later. The date itself is found
   * by local-date arithmetic, so offset changes cannot move the calendar date; the wall clock
   * is then rebuilt on it, with a spring-gap time normalising forward (via
   * [AmbiguousLocalTime]) exactly as the old lenient `java.util.Calendar` did.
   */
  private fun calendarTimeOffset(timeMs: Long, days: Int, timeZone: TimeZone): Long {
    val local = localDateTime(timeMs, timeZone)
    val target = local.date.plus(days, DateTimeUnit.DAY)
    return AmbiguousLocalTime
      .resolve(
        LocalDateTime(
          target.year,
          target.month,
          target.day,
          local.hour,
          local.minute,
          local.second,
          local.nanosecond,
        ),
        timeZone,
      )
      .toEpochMilliseconds()
  }

  /** A zone-independent ordinal for the local date fields of one instant. */
  private fun localDateOrdinal(timeMs: Long, timeZone: TimeZone): Long = localDate(timeMs, timeZone).toEpochDays()

  fun isSameDay(a: Long, b: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Boolean =
    startOfDay(a, timeZone) == startOfDay(b, timeZone)

  /** Events bucketed by local day, days ordered ascending, events within a day by start. */
  fun groupByDay(
    events: List<BriefingEvent>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
  ): List<Pair<Long, List<BriefingEvent>>> =
    events
      .groupBy { startOfDay(it.startTime, timeZone) }
      .toList()
      .sortedBy { it.first }
      .map { (day, items) -> day to items.sortedWith(compareBy({ it.startTime }, { it.title })) }

  const val DAY_MS: Long = 24L * 60 * 60 * 1000

  private fun localDateTime(timeMs: Long, timeZone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(timeMs).toLocalDateTime(timeZone)

  private fun localDate(timeMs: Long, timeZone: TimeZone): LocalDate =
    localDateTime(timeMs, timeZone).date

  /** SHA-256 with the exact byte layout the old `java.security.MessageDigest` stream produced. */
  private class SignatureDigest {
    private val bytes = ArrayList<Byte>(512)

    fun putBoolean(value: Boolean) {
      bytes += if (value) 1.toByte() else 0.toByte()
    }

    fun putInt(value: Int) {
      bytes += (value ushr 24).toByte()
      bytes += (value ushr 16).toByte()
      bytes += (value ushr 8).toByte()
      bytes += value.toByte()
    }

    fun putLong(value: Long) {
      for (shift in 56 downTo 0 step 8) bytes += (value ushr shift).toByte()
    }

    fun putString(value: String) {
      val encoded = value.encodeToByteArray()
      putInt(encoded.size)
      encoded.forEach { bytes += it }
    }

    fun hex(): String = Sha256.hash(bytes.toByteArray()).toHex()
  }

  private fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
      for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(alphabet[value ushr 4])
        append(alphabet[value and 0x0f])
      }
    }
  }
}

/**
 * FIPS 180-4 SHA-256, tested against NIST vectors in `Sha256Test`. Exists because there is no
 * hash in the Kotlin common stdlib and `ScheduleAnalysis.signature` is the daily-brief cache
 * key: it must produce identical bytes on Android, the JVM server and iOS, so it is implemented
 * here rather than delegated to a platform provider.
 */
internal object Sha256 {

  private val K =
    intArrayOf(
      0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
      0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(), 0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
      0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(), 0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
      0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
      0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(), 0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
      0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
      0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(), 0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
      0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

  fun hash(input: ByteArray): ByteArray {
    val bitLength = input.size.toLong() * 8L
    val paddedSize = ((input.size + 8) / 64 + 1) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (i in 0 until 8) {
      padded[paddedSize - 1 - i] = (bitLength ushr (8 * i)).toByte()
    }

    var h0: Int = 0x6a09e667.toInt()
    var h1: Int = 0xbb67ae85.toInt()
    var h2: Int = 0x3c6ef372.toInt()
    var h3: Int = 0xa54ff53a.toInt()
    var h4: Int = 0x510e527f.toInt()
    var h5: Int = 0x9b05688c.toInt()
    var h6: Int = 0x1f83d9ab.toInt()
    var h7: Int = 0x5be0cd19.toInt()
    val w = IntArray(64)

    var offset = 0
    while (offset < paddedSize) {
      for (i in 0 until 16) {
        val at = offset + i * 4
        w[i] =
          (padded[at].toInt() and 0xff) shl 24 or
            ((padded[at + 1].toInt() and 0xff) shl 16) or
            ((padded[at + 2].toInt() and 0xff) shl 8) or
            (padded[at + 3].toInt() and 0xff)
      }
      for (i in 16 until 64) {
        val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
        val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
        w[i] = w[i - 16] + s0 + w[i - 7] + s1
      }

      var a = h0
      var b = h1
      var c = h2
      var d = h3
      var e = h4
      var f = h5
      var g = h6
      var h = h7
      for (i in 0 until 64) {
        val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
        val ch = (e and f) xor (e.inv() and g)
        val temp1 = h + s1 + ch + K[i] + w[i]
        val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
        val maj = (a and b) xor (a and c) xor (b and c)
        val temp2 = s0 + maj
        h = g
        g = f
        f = e
        e = d + temp1
        d = c
        c = b
        b = a
        a = temp1 + temp2
      }
      h0 += a
      h1 += b
      h2 += c
      h3 += d
      h4 += e
      h5 += f
      h6 += g
      h7 += h
      offset += 64
    }

    val out = ByteArray(32)
    val state = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
    var i = 0
    for (value in state) {
      out[i] = (value ushr 24).toByte()
      out[i + 1] = (value ushr 16).toByte()
      out[i + 2] = (value ushr 8).toByte()
      out[i + 3] = value.toByte()
      i += 4
    }
    return out
  }

  /** Rotate right by [bits]; the shift count is masked so `rotateRight(0)` is the identity. */
  private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits and 31))
}
