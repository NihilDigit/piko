package dev.piko.shared.util

/** Capture indices in the original input; an empty capture retains its insertion position. */
internal expect val MatchGroup.matchRange: IntRange
