package dev.piko.shared.util

/**
 * Capture indices in the original input; an empty capture retains its insertion position.
 *
 * Retained as `expect`/`actual` because Kotlin stdlib's common `MatchGroup` does not expose `range`
 * on non-JVM targets (only platform `MatchGroup` declarations provide it on JVM/Android and Native).
 */
internal expect val MatchGroup.matchRange: IntRange
