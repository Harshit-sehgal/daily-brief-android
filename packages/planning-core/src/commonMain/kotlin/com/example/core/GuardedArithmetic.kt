package com.example.core

/**
 * Guarded integer arithmetic for the common half of the engine.
 *
 * `java.lang.Math`'s `addExact`/`subtractExact`/`multiplyExact` exist only on the JVM, and the
 * engine uses them to fail closed on overflow rather than silently wrapping — a wrapped schedule
 * time would be indistinguishable from a real one. These helpers keep that contract in common
 * code, throwing `ArithmeticException` at exactly the points `Math.*Exact` would, so the existing
 * fail-closed tests stay green.
 */
internal object GuardedArithmetic {
  fun addExact(left: Long, right: Long): Long {
    val result = left + right
    if ((left xor result) and (right xor result) < 0) throw ArithmeticException("long overflow")
    return result
  }

  fun subtractExact(left: Long, right: Long): Long {
    val result = left - right
    if ((left xor right) and (left xor result) < 0) throw ArithmeticException("long overflow")
    return result
  }

  fun multiplyExact(left: Long, right: Long): Long {
    if (right == 0L) return 0L
    val result = left * right
    if (right == -1L && left == Long.MIN_VALUE) throw ArithmeticException("long overflow")
    if (result / right != left) throw ArithmeticException("long overflow")
    return result
  }
}