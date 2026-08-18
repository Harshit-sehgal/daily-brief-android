package com.example.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** The version and tolerance rules that make the contract evolvable without breaking. */
class ApiVersionTest {
  @Test
  fun `a newer version is refused before any field is interpreted`() {
    val e = assertThrows(ApiVersionNotSupported::class.java) {
      PlannerApi.checkVersion(2)
    }
    assertEquals(1, e.supported)
    assertEquals(2, e.received)
  }

  @Test
  fun `a version-2 request fails to decode`() {
    val text = read("golden/request-v1.json").replace("\"v\": 1", "\"v\": 2")
    assertThrows(ApiVersionNotSupported::class.java) {
      PlannerApi.json.decodeFromString<PlanningRequest>(text)
    }
  }

  @Test
  fun `a version-2 result fails to decode`() {
    val text = read("golden/result-v1.json").replace("\"v\": 1", "\"v\": 2")
    assertThrows(ApiVersionNotSupported::class.java) {
      PlannerApi.json.decodeFromString<PlanningResult>(text)
    }
  }

  @Test
  fun `unknown fields from a newer minor are ignored`() {
    val text = read("golden/request-v1.json").replaceFirst("}", ",\"futureField\":{\"a\":1}}")
    val parsed = PlannerApi.json.decodeFromString<PlanningRequest>(text)
    assertNotNull(parsed)
    assertEquals(PlannerApi.VERSION, parsed.v)
  }

  @Test
  fun `unknown enum spellings arrive as data, not as decode errors`() {
    val proposal =
      PlannerApi.json.decodeFromString<PlanProposalWire>(
        """{"itemId":"t","startAt":0,"endAt":60000,"reason":"x"}""",
      )
    assertEquals("t", proposal.itemId)
    val request = PlannerApi.json.decodeFromString<PlanningRequest>(requestWith("deadlinePolicy", "MAYBE"))
    assertEquals("MAYBE", request.deadlinePolicy)
  }

  @Test
  fun `a request with no version field decodes as version 1`() {
    val stripped =
      Json { ignoreUnknownKeys = true }
        .decodeFromString<JsonObject>(read("golden/request-v1.json"))
        .let { obj -> JsonObject(obj.filterKeys { it != "v" }) }
        .toString()
    val parsed = PlannerApi.json.decodeFromString<PlanningRequest>(stripped)
    assertEquals(1, parsed.v)
  }

  private fun requestWith(key: String, value: String): String {
    val root = Json { ignoreUnknownKeys = true }.decodeFromString<JsonObject>(read("golden/request-v1.json"))
    val withField = buildJsonObject {
      root.forEach { (k, v) -> put(k, v) }
      put(key, JsonPrimitive(value))
    }
    return withField.toString()
  }

  private fun read(path: String): String =
    requireNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }.readText()
}