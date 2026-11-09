package dev.piko.shared.util

/**
 * `MutableMap.putIfAbsent` 是 JVM 的默认方法，commonMain 里编译不过（Kotlin/Native 的 HashMap 没有）。
 * 语义对齐 `java.util.Map.putIfAbsent`：键不存在（含值为 null）才写入，并返回旧值。
 */
internal fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V): V? {
    val existing = this[key]
    if (existing == null) {
        this[key] = value
        return null
    }
    return existing
}
