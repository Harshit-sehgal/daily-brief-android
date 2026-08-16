package com.example.ui.screens

/** Pure edge policy used by the compact Board's long-press drag accelerator. */
internal object BoardLaneAutoScrollPolicy {
  fun delta(
    pointerX: Float,
    pointerY: Float,
    viewportLeft: Float,
    viewportTop: Float,
    viewportRight: Float,
    viewportBottom: Float,
    edgeWidth: Float,
    maxStep: Float,
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
  ): Float {
    require(viewportRight >= viewportLeft && viewportBottom >= viewportTop) {
      "Invalid Board viewport"
    }
    require(edgeWidth >= 0f && maxStep >= 0f) { "Scroll bounds must be non-negative" }
    if (
      pointerX !in viewportLeft..viewportRight ||
        pointerY !in viewportTop..viewportBottom ||
        edgeWidth == 0f ||
        maxStep == 0f
    ) {
      return 0f
    }
    return when {
      pointerX <= viewportLeft + edgeWidth && canScrollBackward -> -maxStep
      pointerX >= viewportRight - edgeWidth && canScrollForward -> maxStep
      else -> 0f
    }
  }
}
