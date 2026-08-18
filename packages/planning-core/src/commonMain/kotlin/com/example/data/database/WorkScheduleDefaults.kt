package com.example.data.database

/**
 * The seeded working-schedule identity, shared by the 6→7 migration and the mapper that builds
 * the schedule. Living in `planning-core` keeps the mapper (and the journal codecs that validate
 * through it) portable; the migration file aliases these so its SQL keeps reading the same names.
 */
object WorkScheduleDefaults {
  const val ID = "default-work-schedule"
  const val NAME = "Default working week"
}