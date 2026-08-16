package com.example.ui.viewmodel

/** The field an editor is waiting on, so the message can be attached to that control. */
enum class EditorField {
  TITLE,
  DESTINATION,
  PARENT,
  EFFORT,
  MILESTONE,
}

/**
 * Why a draft cannot be saved yet.
 *
 * Two registers, because the two places a person looks are different: [message] sits under the
 * field and says how to fix it, [summary] sits beside Save and says what Save is waiting for.
 * Repeating one sentence in both places reads as a stutter on a short sheet.
 */
data class SaveBlocker(val field: EditorField, val message: String, val summary: String)

/**
 * Turns "Save is disabled" into "Save is disabled *because*".
 *
 * Preventing an invalid save is the right default, but a dead button with no diagnosis leaves the
 * person guessing — the reason has to be visible, attached to the field that owns it, and announced
 * when it appears. Order matters: the first blocker is the one shown beside Save, so it runs from
 * the field people fill first to the ones they rarely touch.
 */
object EditorSaveGuard {
  fun forEvent(title: String, hasValidDestination: Boolean): SaveBlocker? =
    when {
      title.isBlank() ->
        SaveBlocker(EditorField.TITLE, "Add a title to save this event.", "Save needs a title.")
      !hasValidDestination ->
        SaveBlocker(
          EditorField.DESTINATION,
          "That board or column is no longer available. Choose another.",
          "Save needs a board and column that still exist.",
        )
      else -> null
    }

  fun forTask(
    title: String,
    hasValidDestination: Boolean,
    hasValidParent: Boolean,
    effortMinutes: Int?,
    isMilestone: Boolean,
  ): SaveBlocker? =
    when {
      title.isBlank() ->
        SaveBlocker(EditorField.TITLE, "Add a title to save this task.", "Save needs a title.")
      !hasValidDestination ->
        SaveBlocker(
          EditorField.DESTINATION,
          "Choose a plan board that is still available.",
          "Save needs a plan board.",
        )
      !hasValidParent ->
        SaveBlocker(
          EditorField.PARENT,
          "That parent task is no longer available. Choose another or clear it.",
          "Save needs a parent task that still exists.",
        )
      effortMinutes != null && effortMinutes <= 0 ->
        SaveBlocker(
          EditorField.EFFORT,
          "Effort must be more than zero minutes.",
          "Save needs effort above zero.",
        )
      // A milestone marks a moment, so duration would contradict it rather than refine it.
      isMilestone && effortMinutes != null ->
        SaveBlocker(
          EditorField.MILESTONE,
          "A milestone has no duration. Clear the effort, or turn off Milestone.",
          "Save needs either the milestone or the effort, not both.",
        )
      else -> null
    }
}
