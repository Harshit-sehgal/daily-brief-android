package com.example.server.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Where the notebook's readable copy is kept: one text file, rewritten in place. */
interface DriveStore {
  /** Writes [content] to [fileId], or to a new file when there is none. Returns the file's id. */
  fun put(fileId: String?, name: String, content: String): String
}

internal class DriveFailure(val statusCode: Int, message: String) : IllegalStateException(message)

/**
 * Google Drive under the `drive.file` scope: the notebook can see only the file it made.
 * A file the reader deleted answers 404 on update, and is simply made again.
 */
class GoogleDriveStore(
  private val accessToken: String,
  private val baseUrl: String = "https://www.googleapis.com",
) : DriveStore {
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
  private val json = Json { ignoreUnknownKeys = true }

  @Serializable
  private data class DriveFile(val id: String)

  override fun put(fileId: String?, name: String, content: String): String {
    if (fileId != null) {
      val updated = send("PATCH", "$baseUrl/upload/drive/v3/files/$fileId?uploadType=multipart&fields=id", name, content)
      if (updated.first in 200..299) return json.decodeFromString<DriveFile>(updated.second).id
      if (updated.first != 404) throw DriveFailure(updated.first, "Drive update failed: ${updated.first}")
    }
    val created = send("POST", "$baseUrl/upload/drive/v3/files?uploadType=multipart&fields=id", name, content)
    if (created.first !in 200..299) throw DriveFailure(created.first, "Drive upload failed: ${created.first}")
    return json.decodeFromString<DriveFile>(created.second).id
  }

  private fun send(method: String, url: String, name: String, content: String): Pair<Int, String> {
    val boundary = "dailybrief-${System.nanoTime()}"
    val metadata = buildJsonObject {
      put("name", name)
      put("mimeType", "text/markdown")
    }
    val body =
      "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
        "--$boundary\r\nContent-Type: text/markdown; charset=UTF-8\r\n\r\n$content\r\n--$boundary--"
    val request =
      Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")
        .method(method, body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
        .build()
    client.newCall(request).execute().use { response -> return response.code to response.body?.string().orEmpty() }
  }
}

/** Fixture mode's Drive: remembers the last copy so the journey can read it back. */
object FixtureDrive {
  data class Copy(val content: String, val at: Long)

  val copies = ConcurrentHashMap<String, Copy>()

  fun store(workspaceId: String): DriveStore =
    object : DriveStore {
      override fun put(fileId: String?, name: String, content: String): String {
        copies[workspaceId] = Copy(content, System.currentTimeMillis())
        return fileId ?: "fixture-file"
      }
    }
}

/**
 * The notebook as a page a person can read without the app: this week's plan by day,
 * then every project's open and finished tasks. Pure, so the wording is pinned by test.
 */
object NotebookExport {
  const val FILE_NAME = "Daily Brief.md"

  data class TaskLine(val title: String, val effortMinutes: Int?, val dueAt: Long?, val done: Boolean, val highlighted: Boolean)

  data class ProjectPage(val name: String, val tasks: List<TaskLine>)

  data class PlannedBlock(val title: String, val projectName: String, val startAt: Long, val endAt: Long)

  /** A day's typed notes. Sketches stay in the app: a text file cannot hold a drawing. */
  data class NotePage(val day: String, val text: String)

  fun markdown(
    nowMs: Long,
    zoneId: String,
    projects: List<ProjectPage>,
    blocks: List<PlannedBlock>,
    notes: List<NotePage> = emptyList(),
  ): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of("UTC"))
    val day = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).withZone(zone)
    val short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(zone)
    val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(zone)
    val stamp = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.ENGLISH).withZone(zone)
    val out = StringBuilder()
    out.append("# Daily Brief\n\n")
    out.append("_Updated ${stamp.format(Instant.ofEpochMilli(nowMs))} ($zone). Daily Brief rewrites this file; changes made here are not read back._\n")

    val upcoming = blocks.filter { it.endAt > nowMs - 24 * 60 * 60 * 1000L }.sortedBy { it.startAt }
    if (upcoming.isNotEmpty()) {
      out.append("\n## The plan\n")
      upcoming.groupBy { day.format(Instant.ofEpochMilli(it.startAt)) }.forEach { (heading, dayBlocks) ->
        out.append("\n### $heading\n\n")
        dayBlocks.forEach { b ->
          out.append("- ${clock.format(Instant.ofEpochMilli(b.startAt))}–${clock.format(Instant.ofEpochMilli(b.endAt))} ${b.title} (${b.projectName})\n")
        }
      }
    }

    projects.filter { it.tasks.isNotEmpty() }.forEach { project ->
      out.append("\n## ${project.name}\n\n")
      project.tasks.sortedBy { it.done }.forEach { t ->
        val facts = listOfNotNull(t.effortMinutes?.let(::effort), t.dueAt?.let { "due ${short.format(Instant.ofEpochMilli(it))}" })
        val title = if (t.highlighted && !t.done) "**${t.title}**" else t.title
        out.append("- [${if (t.done) "x" else " "}] $title${if (facts.isEmpty()) "" else " — ${facts.joinToString(", ")}"}\n")
      }
    }
    val written = notes.filter { it.text.isNotBlank() }.sortedByDescending { it.day }
    if (written.isNotEmpty()) {
      out.append("\n## Notes\n")
      written.forEach { note -> out.append("\n### ${note.day}\n\n${note.text.trim()}\n") }
    }
    return out.toString()
  }

  private fun effort(minutes: Int): String =
    when {
      minutes < 60 -> "${minutes}m"
      minutes % 60 == 0 -> "${minutes / 60}h"
      else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
