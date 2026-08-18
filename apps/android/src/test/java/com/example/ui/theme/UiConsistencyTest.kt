package com.example.ui.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consistency that a person can see, checked the only way it stays true: by reading the source.
 *
 * The UI had drifted to fourteen corner radii and three sizes for the same inline icon. Nobody
 * decided that; it accumulated, one reasonable-looking literal at a time, and the result reads as
 * carelessness long before anyone can name the cause. Style review cannot catch this reliably —
 * every individual diff looks fine — so the rule is a test.
 *
 * This is the same instrument as `ContrastTest`: a claim about the whole palette, enforced over the
 * whole tree rather than trusted per commit.
 */
class UiConsistencyTest {
  @Test
  fun `every corner radius comes from the Radius scale`() {
    val offenders =
      uiSources()
        .filterNot { it.path.endsWith("theme/Theme.kt") }
        .flatMap { file ->
          file.readLines().withIndex().mapNotNull { (index, line) ->
            val raw = RAW_RADIUS.find(line) ?: return@mapNotNull null
            "${file.name}:${index + 1} uses ${raw.value} — say what it is: " +
              "Radius.mark, Radius.control, Radius.block or Radius.container"
          }
        }
    assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
  }

  @Test
  fun `an icon beside a label uses one size`() {
    val offenders =
      uiSources().flatMap { file ->
        val text = file.readText()
        // Read the whole Icon(...) call rather than one line of it: the size usually sits on a
        // different line from the name, which is exactly how three sizes crept in unnoticed.
        iconCalls(text).mapNotNull { call ->
          val size = SIZE_LITERAL.find(call) ?: return@mapNotNull null
          if (call.contains("rotate(")) return@mapNotNull null
          "${file.name} sizes an icon at ${size.groupValues[1]} dp — use InlineIconSize"
        }
      }
    assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
  }

  /** The source of each `Icon(` call, balanced by parentheses. */
  private fun iconCalls(text: String): List<String> =
    Regex("""\bIcon\(""").findAll(text).mapNotNull { match ->
      var depth = 0
      var index = match.range.last
      while (index < text.length) {
        when (text[index]) {
          '(' -> depth++
          ')' -> {
            depth--
            if (depth == 0) return@mapNotNull text.substring(match.range.first, index + 1)
          }
        }
        index++
      }
      null
    }.toList()

  @Test
  fun `a directional icon mirrors for right-to-left readers`() {
    val offenders =
      uiSources().flatMap { file ->
        file.readLines().withIndex().mapNotNull { (index, line) ->
          val match = DIRECTIONAL.find(line) ?: return@mapNotNull null
          if (line.contains("AutoMirrored")) return@mapNotNull null
          "${file.name}:${index + 1} uses ${match.value} — use the AutoMirrored variant so it " +
            "points the right way in an RTL layout"
        }
      }
    assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
  }

  @Test
  fun `the scale itself stays small enough to be a scale`() {
    val theme = File(UI_ROOT, "theme/Theme.kt").readText()
    val radii = RAW_RADIUS.findAll(theme).map { it.value }.toSet()
    // Four values, one per kind of thing. A fifth means the vocabulary is slipping again.
    assertTrue("the Radius scale grew to $radii", radii.size <= 4)
    listOf("mark", "control", "block", "container").forEach { name ->
      assertTrue("Radius.$name is missing", theme.contains("val $name ="))
    }
  }

  private fun uiSources(): List<File> =
    UI_ROOT.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

  private companion object {
    /** Gradle runs unit tests with the module directory as the working directory. */
    val UI_ROOT = File("src/main/java/com/example/ui")

    val RAW_RADIUS = Regex("""RoundedCornerShape\(\s*(?:top|bottom)?\w*\s*=?\s*\d+(?:\.\d+)?\.dp""")
    val SIZE_LITERAL = Regex("""Modifier\.size\((\d+(?:\.\d+)?)\.dp\)""")
    val DIRECTIONAL = Regex("""Icons\.(?:Default|Filled)\.KeyboardArrow(?:Left|Right)""")
  }
}
