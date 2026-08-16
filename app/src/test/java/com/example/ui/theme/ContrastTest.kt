package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette claims WCAG AA for every foreground it draws. That claim is easy
 * to break with a single hex tweak and impossible to notice by eye, so it is
 * checked here rather than asserted in a comment.
 */
class ContrastTest {

  /** WCAG 2.1 relative luminance. */
  private fun luminance(color: Color): Double {
    fun channel(value: Float): Double {
      val c = value.toDouble()
      return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
  }

  private fun contrast(foreground: Color, background: Color): Double {
    val a = luminance(foreground)
    val b = luminance(background)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
  }

  private fun assertReadable(
    label: String,
    foreground: Color,
    background: Color,
    minimum: Double = AA_NORMAL_TEXT,
  ) {
    val ratio = contrast(foreground, background)
    assertTrue(
      "$label is %.2f:1, below the required %.1f:1".format(ratio, minimum),
      ratio >= minimum,
    )
  }

  /** Every pair of scheme roles the app actually draws text with. */
  private fun assertSchemeIsReadable(name: String, scheme: ColorScheme, status: StatusColors) {
    assertReadable("$name onSurface on surface", scheme.onSurface, scheme.surface)
    assertReadable(
      "$name onSurfaceVariant on surface",
      scheme.onSurfaceVariant,
      scheme.surface,
    )
    // Supporting text also sits on raised rows and sheets.
    assertReadable(
      "$name onSurfaceVariant on surfaceContainerHighest",
      scheme.onSurfaceVariant,
      scheme.surfaceContainerHighest,
    )
    assertReadable("$name onSurface on surfaceContainer", scheme.onSurface, scheme.surfaceContainer)
    // The accent is used as text — links, section actions, the empty-day nudge.
    assertReadable("$name primary on surface", scheme.primary, scheme.surface)
    assertReadable("$name onPrimary on primary", scheme.onPrimary, scheme.primary)
    assertReadable(
      "$name onPrimaryContainer on primaryContainer",
      scheme.onPrimaryContainer,
      scheme.primaryContainer,
    )
    assertReadable("$name error on surface", scheme.error, scheme.surface)
    assertReadable("$name onError on error", scheme.onError, scheme.error)
    // Status colours are drawn as text on the page and inside tinted chips.
    assertReadable("$name deadline on surface", status.deadline, scheme.surface)
    assertReadable("$name urgent on surface", status.urgent, scheme.surface)
    assertReadable("$name positive on surface", status.positive, scheme.surface)
  }

  @Test
  fun `every accent stays readable in both themes`() {
    Accents.all.forEach { accent ->
      assertSchemeIsReadable("${accent.label} light", lightSchemeFor(accent), statusColorsFor(false))
      assertSchemeIsReadable("${accent.label} dark", darkSchemeFor(accent), statusColorsFor(true))
    }
  }

  @Test
  fun `accent swatches carry a readable check mark`() {
    // The picker draws a tick straight onto the swatch.
    Accents.all.forEach { accent ->
      assertReadable(
        "${accent.label} light swatch tick",
        accent.onSwatch(dark = false),
        accent.swatch(dark = false),
      )
      assertReadable(
        "${accent.label} dark swatch tick",
        accent.onSwatch(dark = true),
        accent.swatch(dark = true),
      )
    }
  }

  /**
   * Hairlines are deliberately quiet — separating rows on the page ground is the
   * whole visual language, and a 3:1 rule through every row would undo it. They
   * still have to be visible at all, which is what this guards. Anything that
   * carries meaning rather than structure is a status colour, checked above.
   */
  @Test
  fun `hairlines stay visible without becoming rules`() {
    listOf(false to lightSchemeFor(Accents.Default), true to darkSchemeFor(Accents.Default))
      .forEach { (dark, scheme) ->
        val name = if (dark) "dark" else "light"
        listOf("outline" to scheme.outline, "outlineVariant" to scheme.outlineVariant).forEach {
          (role, color) ->
          val ratio = contrast(color, scheme.surface)
          assertTrue(
            "$name $role is invisible against the page at %.2f:1".format(ratio),
            ratio >= VISIBLE_HAIRLINE,
          )
        }
      }
  }

  private companion object {
    const val AA_NORMAL_TEXT = 4.5

    /**
     * Not a WCAG figure. The quietest hairline in the palette today is the light
     * row divider at 1.17:1 — deliberately faint, in the range Notion and
     * Obsidian use. This pins that floor so a later tweak cannot quietly fade
     * the page structure away altogether.
     */
    const val VISIBLE_HAIRLINE = 1.15
  }
}
