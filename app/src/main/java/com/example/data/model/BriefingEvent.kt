package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Sources an event can come from. Stored as plain strings for schema stability. */
object EventSource {
  const val MANUAL = "Manual"
  const val NOTION = "Notion"
  const val GOOGLE = "Google Calendar"
  const val SAMSUNG = "Samsung Calendar"
  const val DEVICE = "Device Calendar"
  const val SAMPLE = "Sample"

  /** Everything that a sync replaces wholesale; [MANUAL] and [SAMPLE] are user-owned. */
  val SYNCED = listOf(NOTION, GOOGLE, SAMSUNG, DEVICE)
}

@Entity(tableName = "briefing_events")
data class BriefingEvent(
  @PrimaryKey val id: String,
  val title: String,
  val startTime: Long,
  val endTime: Long,
  val source: String,
  val description: String?,
  val isDeadline: Boolean,
  val isUrgent: Boolean,
  val location: String? = null,
  val kanbanStatus: String = "To Do",
  val kanbanBoard: String = "Default",
  /**
   * Set once the user edits an event by hand. A re-sync then refreshes only the
   * times and location from the source and leaves their wording and flags alone,
   * instead of silently reverting the edit.
   */
  val userEdited: Boolean = false,
)

@Entity(tableName = "daily_briefings")
data class DailyBriefing(
  @PrimaryKey val dateString: String,
  val briefText: String,
  val createdAt: Long = System.currentTimeMillis(),
  /** Fingerprint of the events the text was generated from; drives cache reuse. */
  val signature: String = "",
)

@Entity(tableName = "system_settings")
data class SystemSetting(@PrimaryKey val key: String, val value: String)
