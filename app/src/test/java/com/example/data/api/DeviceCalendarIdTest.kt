package com.example.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The local id is the only link between a stored event and the calendar row it
 * came from. If these two stop agreeing, edits stop writing back and every sync
 * inserts duplicates.
 */
class DeviceCalendarIdTest {

  @Test
  fun `a non-repeating event keeps one id whatever its start time`() {
    val morning = DeviceCalendarSync.localId(42L, rawBegin = 1_700_000_000_000L, recurring = false)
    val moved = DeviceCalendarSync.localId(42L, rawBegin = 1_800_000_000_000L, recurring = false)

    assertEquals(morning, moved)
    assertEquals(42L, DeviceCalendarSync.providerEventId(morning))
  }

  @Test
  fun `each occurrence of a repeating event gets its own id`() {
    val monday = DeviceCalendarSync.localId(42L, rawBegin = 1_700_000_000_000L, recurring = true)
    val tuesday = DeviceCalendarSync.localId(42L, rawBegin = 1_700_086_400_000L, recurring = true)

    assertEquals(false, monday == tuesday)
    assertEquals(42L, DeviceCalendarSync.providerEventId(monday))
    assertEquals(42L, DeviceCalendarSync.providerEventId(tuesday))
  }

  @Test
  fun `ids from other sources resolve to no calendar row`() {
    assertNull(DeviceCalendarSync.providerEventId("manual_9f0c-4a"))
    assertNull(DeviceCalendarSync.providerEventId("notion_1234"))
    assertNull(DeviceCalendarSync.providerEventId("sample_standup"))
    // Well-formed prefix, unusable body.
    assertNull(DeviceCalendarSync.providerEventId("device_"))
    assertNull(DeviceCalendarSync.providerEventId("device_not-a-number_1700000000000"))
  }
}
